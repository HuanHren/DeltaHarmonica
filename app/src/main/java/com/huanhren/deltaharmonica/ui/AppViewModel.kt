package com.huanhren.deltaharmonica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.huanhren.deltaharmonica.playback.*
import com.huanhren.deltaharmonica.data.DocumentTransfer
import com.huanhren.deltaharmonica.data.ImportCoordinator
import com.huanhren.deltaharmonica.data.ImportPhase
import com.huanhren.deltaharmonica.data.OnlineSongOrigin
import com.huanhren.deltaharmonica.data.SongLibraryItem
import com.huanhren.deltaharmonica.data.SongLibraryRepository
import com.huanhren.deltaharmonica.data.SongSourceType
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.accessibility.AccessibilityConnection
import com.huanhren.deltaharmonica.data.LayoutRepository
import com.huanhren.deltaharmonica.input.*
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.online.DfhsDecoder
import com.huanhren.deltaharmonica.online.OnlineEntry
import com.huanhren.deltaharmonica.online.OnlineLibraryRepository
import com.huanhren.deltaharmonica.online.OnlineScore
import com.huanhren.deltaharmonica.online.OnlineSongConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive

internal enum class OnlineSongStage { DOWNLOADING, CONVERTING, SAVING, ACTIVATING }

internal data class OnlineConversionOptions(val policy: MonophonyPolicy, val transpose: Int)

internal class OnlineSongException(message: String, cause: Exception? = null) : IllegalStateException(message, cause)

private const val SCORE_TIMING_TAG = "DeltaScore"

private fun logScoreTiming(operation: String, startedNs: Long) {
    if (Log.isLoggable(SCORE_TIMING_TAG, Log.DEBUG))
        Log.println(Log.DEBUG, SCORE_TIMING_TAG, "$operation ms=${(System.nanoTime() - startedNs) / 1_000_000}")
}

data class AppUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val draft: InstrumentLayout? = null,
    val saved: InstrumentLayout? = null,
    val selectedId: String? = null,
    val saving: Boolean = false,
    val message: String = "",
    val backends: List<Pair<String, BackendStatus>> = emptyList(),
    val selectedBackend: String = "Root",
    val preferredBackend: String = selectedBackend,
    val checkingRoot: Boolean = false,
    val enablingAccessibility: Boolean = false,
    val accessibilityGrantMessage: String = "",
    val testing: Boolean = false,
    val testMessage: String = "尚未测试",
    val hitMessage: String = "尚未收到测试触摸",
    val canTestSelectedInput: Boolean = false,
) {
    val dirty: Boolean get() = draft != saved
    val selected: TouchTarget? get() = draft?.targets?.find { it.id == selectedId }
    val selectedBackendStatus: BackendStatus? get() = backends.firstOrNull { it.first == selectedBackend }?.second
}

class AppViewModel(
    private val repository: LayoutRepository,
    private val rootBackend: RootBackend,
    private val accessibilityBackend: AccessibilityBackend,
    private val allBackends: List<InputBackend>,
    val songs: SongLibraryRepository,
    private val resolver: ContentResolver,
    val preferences: PlayerPreferences? = null,
    val rootUiAllowed: Boolean = true,
    val onlineLibrary: OnlineLibraryRepository,
) : ViewModel() {
    val midiWorkspace = MidiWorkspace().apply {
        preferences?.let {
            speed = it.speed.value
            range = it.range.value
        }
    }
    private val mutableLibraryLoading = MutableStateFlow(true)
    val libraryLoading = mutableLibraryLoading.asStateFlow()
    private val mutableLibraryError = MutableStateFlow<String?>(null)
    val libraryError = mutableLibraryError.asStateFlow()
    private val mutableLibraryBusy = MutableStateFlow(false)
    val libraryBusy = mutableLibraryBusy.asStateFlow()

    fun refreshLibrary() {
        if (libraryRefresh?.isActive == true) return
        libraryRefresh = viewModelScope.launch {
            mutableLibraryLoading.value = true
            mutableLibraryError.value = null
            try {
                songs.cleanupExpiredCandidates(System.currentTimeMillis())
                songs.refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableLibraryError.value = "曲库读取失败：${e.localizedMessage}" }
            finally { mutableLibraryLoading.value = false }
        }
    }

    private var libraryRefresh: Job? = null

    fun activateSong(id: String, onFinished: (String?) -> Unit) = libraryAction(onFinished) {
        loadLibrarySong(songs, id, midiWorkspace, state.value.saved)
    }

    internal suspend fun inspectOnlineSong(
        score: OnlineScore,
        layout: InstrumentLayout?,
        options: OnlineConversionOptions = OnlineConversionOptions(midiWorkspace.candidatePolicy, midiWorkspace.candidateTranspose),
    ): SongCompatibilityReport {
        val conversionLayout = state.value.saved ?: layout ?: state.value.draft ?: DefaultLayouts.create()
        return try {
            convertOnlineScore(score, options, conversionLayout).second.compatibility
        } catch (e: IllegalArgumentException) {
            SongCompatibilityAnalyzer.failed(ScoreFormat.DFHS1, e.localizedMessage ?: "曲谱损坏或无法转换")
        }
    }

    private suspend fun convertOnlineScore(
        score: OnlineScore,
        options: OnlineConversionOptions,
        layout: InstrumentLayout,
    ): Pair<ByteArray, ConversionReport> = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val bytes = DfhsDecoder.decodeBase64(score.payload)
        val report = OnlineSongConverter.convertCompressed(bytes, "${score.entry.title}.dfhs",
            options.policy, options.transpose, layout.profile ?: DefaultProfiles.delta(), layout) { ensureActive() }
        require(report.compatibility.sourceNoteCount == score.entry.noteCount) { "曲谱与详情中的音符数不一致" }
        logScoreTiming("online_decode_convert", started)
        bytes to report
    }

    internal suspend fun prepareOnlineSong(
        entry: OnlineEntry,
        score: OnlineScore?,
        layout: InstrumentLayout?,
        play: Boolean,
        onStage: (OnlineSongStage) -> Unit,
        onProgress: (Long, Long?) -> Unit,
        update: Boolean = false,
        options: OnlineConversionOptions? = null,
    ): SongLibraryItem {
        if ((play && (!currentPlaybackControls().canStart || midiWorkspace.stateDialog || midiWorkspace.confirmPlayback)) ||
            imports.state.value.active ||
            !mutableLibraryBusy.compareAndSet(expect = false, update = true)) {
            throw OnlineSongException("请先结束当前演奏或曲目操作")
        }
        try {
            val defaultOptions = OnlineConversionOptions(midiWorkspace.candidatePolicy, midiWorkspace.candidateTranspose)
            val conversionLayout = state.value.saved ?: layout ?: state.value.draft ?: DefaultLayouts.create()
            val existing = songs.findOnline(entry.id)
            val item = if (existing != null && !update) existing else {
                val storedPolicy = MonophonyPolicy.entries.firstOrNull { it.name == existing?.monophonyPolicy }
                val policy = options?.policy ?: storedPolicy ?: defaultOptions.policy
                val userTranspose = options?.transpose ?: existing?.online?.userTranspose ?: defaultOptions.transpose
                onStage(OnlineSongStage.DOWNLOADING)
                val selected = score?.takeIf { !update && it.entry.id == entry.id }
                    ?: onlineLibrary.detail(entry.id, force = update, onProgress = onProgress)
                if (update && selected.stale) throw OnlineSongException("暂时无法取得新版曲谱，请联网后重试")
                onStage(OnlineSongStage.CONVERTING)
                val (original, report) = try {
                    convertOnlineScore(selected, OnlineConversionOptions(policy, userTranspose), conversionLayout)
                } catch (e: IllegalArgumentException) {
                    throw OnlineSongException(e.localizedMessage ?: "曲谱损坏或无法转换", e)
                }
                val meta = selected.entry
                val origin = OnlineSongOrigin(meta.id, meta.updatedAt, composer = meta.composer, uploader = meta.uploader,
                    tags = meta.tags, bpm = meta.bpm, difficulty = meta.difficulty, remoteDurationMs = meta.durationMs,
                    noteCount = meta.noteCount, sourceTranspose = report.script.transpose - userTranspose, userTranspose = userTranspose)
                onStage(OnlineSongStage.SAVING)
                try {
                    songs.addOnline(meta.title, original, report.script, report.warnings, origin, allowNewRevision = update)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw OnlineSongException("无法保存曲谱，请检查存储空间后重试", e)
                }
            }
            if (play) {
                onStage(OnlineSongStage.ACTIVATING)
                try {
                    loadLibrarySong(songs, item.id, midiWorkspace, state.value.saved ?: layout, options)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    throw OnlineSongException("曲谱已保存。请检查乐器配置和校准，再打开曲目", e)
                } catch (e: Exception) {
                    throw OnlineSongException("曲谱已保存，暂时无法打开，请从本地曲库重试", e)
                }
            }
            return item
        } finally {
            mutableLibraryBusy.value = false
        }
    }

    fun renameSong(id: String, name: String, onFinished: (String?) -> Unit) = libraryAction(onFinished) {
        val item = songs.rename(id, name)
        if (midiWorkspace.songId == id) midiWorkspace.name = item.displayName
    }

    fun deleteSong(id: String, onFinished: (String?) -> Unit) = libraryAction(onFinished) {
        removeLibrarySong(songs, id, midiWorkspace)
        preferences?.removeHistory(id)
    }

    private fun libraryAction(onFinished: (String?) -> Unit, action: suspend () -> Unit) {
        if (mutableLibraryBusy.value || imports.state.value.active || !currentPlaybackControls().canStart) {
            onFinished("请等待当前操作结束")
            return
        }
        mutableLibraryBusy.value = true
        viewModelScope.launch {
            val error = try {
                action()
                null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { e.localizedMessage ?: "曲目操作失败" }
            finally { mutableLibraryBusy.value = false }
            onFinished(error)
        }
    }

    suspend fun startPreflight(layout: InstrumentLayout?, overlayAllowed: Boolean): Boolean {
        if (!currentPlaybackControls().canStart || imports.state.value.active || mutableLibraryBusy.value) return false
        val workspace = midiWorkspace
        val script = workspace.report?.script ?: return false
        val count = workspace.range.count(script.events.size)
        PlaybackPreparation.clearCompletedHistory()
        workspace.dryRun = null
        PlaybackPreparation.pending = null
        val checking = PlaybackProgress(count, state = "正在检查脚本", phase = PlaybackStatus.CHECKING)
        PlaybackPreparation.progress.value = checking
        return try {
            require(rootUiAllowed) { "隔离测试宿主不执行演奏" }
            require(overlayAllowed) { "请先授权悬浮窗" }
            val backend = accessibilityBackend.status()
            require(!rootBackend.releaseUncertain || rootBackend.preferred) { rootBackend.status().message }
            require(rootBackend.preferred || backend.available) { backend.message }
            val runtime = PlaybackPreparation.runtimeState.value
            require(runtime.syncStatus == SyncStatus.USER_CONFIRMED) { "请先同步游戏音区与半音" }
            val calibratedLayout = requireNotNull(layout) { "请先保存校准布局" }
            val savedLayout = state.value.saved
            val speed = workspace.speed
            val minimumTouchMs = workspace.minimumTouchMs
            val rootTiming = if (rootBackend.preferred) {
                val candidate = com.huanhren.deltaharmonica.playback.RootPlaybackTiming()
                candidate.copy(minNoteHoldMs = maxOf(minimumTouchMs, candidate.minNoteHoldMs))
            } else null
            val combined = workspace.combinedMode && rootTiming == null
            val combinedLeadMs = workspace.combinedLeadMs
            val planStarted = System.nanoTime()
            val plan = withContext(Dispatchers.Default) {
                PlaybackPlanner.plan(script, calibratedLayout, runtime.pitchRegion, runtime.semitoneEnabled,
                    0, count, speed, combined = combined, minimumTouchMs = minimumTouchMs, combinedLeadMs = combinedLeadMs,
                    rootTiming = rootTiming,
                    checkActive = { ensureActive() })
            }
            logScoreTiming("playback_plan", planStarted)
            if (PlaybackPreparation.progress.value !== checking) return false
            require(workspace.report?.script === script && state.value.saved === savedLayout &&
                PlaybackPreparation.runtimeState.value == runtime) { "曲谱、布局或游戏状态已变化，请重新预检" }
            workspace.dryRun = plan
            require(plan.events.size == count && count > 0 && plan.fatalErrors.isEmpty()) {
                plan.fatalErrors.firstOrNull() ?: "选定范围没有完整的可播放事件"
            }
            PlaybackPreparation.progress.value = PlaybackProgress(count, state = "等待确认演奏", phase = PlaybackStatus.CONFIRMING)
            true
        } catch (e: CancellationException) {
            if (PlaybackPreparation.progress.value === checking) PlaybackPreparation.progress.value = PlaybackProgress()
            throw e
        } catch (e: Exception) {
            if (PlaybackPreparation.progress.value === checking)
                PlaybackPreparation.progress.value = PlaybackProgress(count, state = "预检失败：${e.localizedMessage}", phase = PlaybackStatus.FAILED)
            false
        }
    }

    fun confirmPreflight(onArmed: () -> Unit) {
        val progress = PlaybackPreparation.progress.value
        if (progress.phase != PlaybackStatus.CONFIRMING) return
        val workspace = midiWorkspace
        val script = workspace.report?.script ?: return
        val plan = workspace.dryRun ?: return
        val runtime = PlaybackPreparation.runtimeState.value
        val region = runtime.pitchRegion ?: return
        val semitone = runtime.semitoneEnabled ?: return
        PlaybackPreparation.pending = PreparedPlayback(script, plan, region, semitone, 0, plan.events.size, workspace.speed, true)
        PlaybackPreparation.controlsConfirmed = true
        PlaybackPreparation.progress.value = PlaybackProgress.waitingForGame(plan)
        try { onArmed() } catch (e: Exception) {
            PlaybackPreparation.pending = null
            PlaybackPreparation.progress.value = progress.copy(state = "无法启动悬浮窗：${e.localizedMessage}", phase = PlaybackStatus.FAILED)
        }
    }

    fun cancelPreflight() {
        if (PlaybackPreparation.progress.value.phase == PlaybackStatus.CONFIRMING) {
            PlaybackPreparation.pending = null
            PlaybackPreparation.progress.value = PlaybackProgress()
        }
    }

    fun changePlan(change: MidiWorkspace.() -> Unit) {
        if (!currentPlaybackControls().canStart) return
        midiWorkspace.change()
        midiWorkspace.dryRun = null
        PlaybackPreparation.pending = null
    }

    fun setDefaultRange(range: PlaybackRange) {
        preferences?.setRange(range)
        changePlan { this.range = range }
    }

    fun setDefaultSpeed(speed: Double) {
        preferences?.setSpeed(speed)
        changePlan { this.speed = speed }
    }
    val imports = ImportCoordinator(viewModelScope)

    private val importPipeline = ImportPipeline(songs, midiWorkspace, imports,
        activate = { loadLibrarySong(songs, it, midiWorkspace, state.value.saved) },
        selectedLayout = { state.value.saved }, recordTiming = ::logScoreTiming)
    fun importDocument(uri: Uri): Boolean = !libraryBusy.value &&
        importPipeline.importDocument { DocumentTransfer.read(resolver, uri) }
    fun convertMidi(layout: InstrumentLayout?): Boolean = !libraryBusy.value && importPipeline.convertMidi(layout)
    private var preferredBackend = if (rootUiAllowed && preferences?.inputBackend?.value == "Root") "Root" else "Accessibility"
    internal val rootAuthorization get() = rootBackend.authorization.state
    internal val rootSession get() = rootBackend.sessionState
    private val mutableState = MutableStateFlow(AppUiState(selectedBackend = preferredBackend))
    val state = mutableState.asStateFlow()
    private var testJob: Job? = null

    init {
        refreshBackends()
        load()
        refreshLibrary()
        viewModelScope.launch {
            var recorded = false
            PlaybackPreparation.progress.collect { progress ->
                if (progress.phase == PlaybackStatus.READY) recorded = false
                if (!recorded && progress.completed > 0) {
                    midiWorkspace.songId?.let { preferences?.recordSession(it) }
                    recorded = true
                }
            }
        }
        viewModelScope.launch { AccessibilityConnection.connected.collect { refreshBackends() } }
        if (rootUiAllowed) {
            viewModelScope.launch { rootAuthorization.collect { refreshBackends() } }
            viewModelScope.launch { rootSession.collect { refreshBackends() } }
        }
    }

    fun refreshBackends() {
        val useRoot = rootUiAllowed && preferredBackend == "Root" &&
            (rootAuthorization.value.canAttemptUse || rootBackend.releaseUncertain)
        val active = if (useRoot) "Root" else "Accessibility"
        val backends = allBackends.map { backend -> backend.name to backend.status() }
        rootBackend.preferred = useRoot
        mutableState.update {
            it.copy(
                backends = backends,
                selectedBackend = active,
                preferredBackend = preferredBackend,
                canTestSelectedInput = if (useRoot) !rootAuthorization.value.checking && !rootBackend.releaseUncertain
                else backends.any { (name, status) -> name == active && status.available },
            )
        }
    }

    fun selectBackend(name: String) {
        val backend = allBackends.firstOrNull { it.name == name } ?: return
        if ((name != "Root" && name != "Accessibility") || name == "Root" && !rootUiAllowed ||
            backend.status().availability == BackendAvailability.NOT_IMPLEMENTED || state.value.testing
        ) return
        preferredBackend = name
        preferences?.setInputBackend(name)
        refreshBackends()
        if (name == "Root" &&
            rootAuthorization.value.authorization in setOf(RootAuthorizationState.UNKNOWN, RootAuthorizationState.NOT_AUTHORIZED)
        ) launchRootCheck(rootBackend::checkAccessForUse)
    }

    fun checkRoot() = launchRootCheck(rootBackend::checkAccess)

    internal fun recoverRootInput() = launchRootCheck(rootBackend::recover)

    internal fun rootDiagnosticReport(): String = rootBackend.diagnosticReport()

    internal fun detectRootEnvironment() {
        if (!rootUiAllowed || state.value.checkingRoot) return
        viewModelScope.launch { rootBackend.authorization.refreshEnvironment() }
    }

    private fun launchRootCheck(check: suspend () -> BackendStatus) {
        if (!rootUiAllowed || state.value.checkingRoot) return
        mutableState.update { it.copy(checkingRoot = true) }
        viewModelScope.launch {
            try {
                check()
            } finally {
                mutableState.update { it.copy(checkingRoot = false) }
                refreshBackends()
            }
        }
    }

    fun enableAccessibilityWithRoot() {
        if (!rootUiAllowed || state.value.enablingAccessibility || !rootBackend.status().available) return
        mutableState.update { it.copy(enablingAccessibility = true, accessibilityGrantMessage = "正在启用无障碍服务…") }
        viewModelScope.launch {
            try {
                val existing = accessibilityBackend.configuredServices()
                val reconnect = !accessibilityBackend.status().available
                when (val result = rootBackend.enableAccessibility(existing, accessibilityBackend.componentName, reconnect,
                    accessibilityBackend.isSystemUser)) {
                    is RootCommandResult.Success -> {
                        selectBackend("Accessibility")
                        repeat(20) {
                            refreshBackends()
                            if (accessibilityBackend.status().available) {
                                mutableState.update { state -> state.copy(accessibilityGrantMessage = "无障碍服务已启用并连接") }
                                return@launch
                            }
                            delay(250)
                        }
                        mutableState.update { it.copy(accessibilityGrantMessage = "设置已写入，但服务尚未连接；请刷新状态或检查系统设置") }
                    }
                    is RootCommandResult.Failure -> mutableState.update { it.copy(accessibilityGrantMessage = "启用失败：${result.reason}") }
                    RootCommandResult.TimedOut -> mutableState.update { it.copy(accessibilityGrantMessage = "启用超时，请刷新状态后重试") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(accessibilityGrantMessage = "启用失败：${e.localizedMessage}") }
            } finally {
                mutableState.update { it.copy(enablingAccessibility = false) }
                refreshBackends()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, loadError = null) }
            try {
                val saved = repository.load()
                val draft = saved ?: DefaultLayouts.create()
                mutableState.update {
                    if (it.draft != null && it.dirty && it.saved == saved) it.copy(loading = false)
                    else it.copy(loading = false, saved = saved, draft = draft, selectedId = draft.targets.first().id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, loadError = "读取失败，原数据未覆盖：${e.localizedMessage}") }
            }
        }
    }

    fun beginCalibration() {
        if (state.value.saving) return
        val draft = state.value.saved ?: DefaultLayouts.create()
        mutableState.update { it.copy(draft = draft, selectedId = draft.targets.first().id, message = "") }
    }

    fun select(id: String) { mutableState.update { it.copy(selectedId = id) } }

    fun move(id: String, position: NormalizedPoint) {
        if (state.value.saving) return
        mutableState.update { current ->
            current.copy(draft = current.draft?.let { layout ->
                layout.copy(targets = layout.targets.map { if (it.id == id) it.copy(position = position) else it })
            }, selectedId = id, message = "未保存")
        }
    }

    fun reset(orientation: LayoutOrientation? = null) {
        if (state.value.saving) return
        mutableState.update { current ->
            current.copy(draft = current.draft?.let {
                it.copy(targets = DefaultLayouts.arrange(it.targets), orientation = orientation ?: it.orientation, referenceDisplay = if (orientation != null && orientation != it.orientation) null else it.referenceDisplay)
            }, message = "已恢复默认草稿 · 尚未保存")
        }
    }

    fun save(screenSize: ScreenSize) {
        val current = state.value
        if (current.loading || current.loadError != null || current.saving) return
        val draft = current.draft ?: return
        if (screenSize.orientation != draft.orientation) return
        val saved = draft.copy(updatedAt = maxOf(System.currentTimeMillis(), draft.createdAt), referenceDisplay = ReferenceDisplay(screenSize.width, screenSize.height))
        mutableState.update { it.copy(saving = true, message = "正在保存…") }
        viewModelScope.launch {
            try {
                repository.save(saved)
                mutableState.update { it.copy(saved = saved, draft = saved, saving = false, message = "已保存") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(saving = false, message = "保存失败：${e.localizedMessage}") }
            }
        }
    }

    fun test(targetId: String, before: DisplaySnapshot, currentDisplay: () -> DisplaySnapshot) {
        if (state.value.testing) return
        val layout = state.value.saved ?: return
        val target = layout.targets.find { it.id == targetId } ?: return
        if (rootBackend.releaseUncertain) {
            mutableState.update { it.copy(testMessage = rootBackend.status().message) }
            return
        }
        val requested = allBackends.firstOrNull { it.name == state.value.selectedBackend } ?: return
        if (requested !== rootBackend && !requested.status().available) {
            mutableState.update { it.copy(testMessage = requested.status().message) }
            return
        }
        if (!before.supported || before.size.orientation != layout.orientation) {
            mutableState.update { it.copy(testMessage = "请使用布局对应方向的全屏主显示屏") }
            return
        }
        mutableState.update { it.copy(testing = true, hitMessage = "等待测试触摸…") }
        testJob = viewModelScope.launch {
            try {
                val inputBackend = if (requested === rootBackend && !rootBackend.checkAccessForUse().available) {
                    if (!accessibilityBackend.status().available) {
                        mutableState.update { it.copy(testMessage = rootBackend.status().message) }
                        return@launch
                    }
                    accessibilityBackend
                } else requested
                for (seconds in 3 downTo 1) {
                    mutableState.update { it.copy(testMessage = "$seconds 秒后测试 ${target.label}；返回可取消") }
                    delay(1_000)
                }
                if (currentDisplay() != before) {
                    mutableState.update { it.copy(testMessage = "屏幕配置变化，测试已取消") }
                    return@launch
                }
                val result = inputBackend.tap(Coordinates.toScreen(target.position, before.size))
                refreshBackends()
                mutableState.update { it.copy(testMessage = when (result) {
                    TapResult.Completed -> if (inputBackend.name == "Root") "Root 触摸已派发 · 请核对下方实际命中"
                        else "系统手势完成 · 请核对下方实际命中"
                    TapResult.Cancelled -> "系统取消了手势"
                    TapResult.Rejected -> "系统拒绝了手势"
                    TapResult.TimedOut -> "等待系统回调超时"
                    TapResult.Busy -> "已有手势正在执行"
                    is TapResult.Unavailable -> result.reason
                    is TapResult.InvalidPoint -> result.reason
                }) }
                // Leave the receiving canvas unobstructed through the UP event.
                delay(350)
            } finally {
                mutableState.update { it.copy(testing = false) }
            }
        }
    }

    fun recordHit(label: String?) {
        if (state.value.testing) mutableState.update {
            it.copy(hitMessage = label?.let { "测试期间收到触摸：$it" } ?: "测试期间收到触摸，但未命中目标")
        }
    }

    fun cancelTest() {
        if (state.value.testing) {
            testJob?.cancel()
            mutableState.update { it.copy(testing = false, testMessage = "测试已取消") }
        }
        testJob = null
    }
}

