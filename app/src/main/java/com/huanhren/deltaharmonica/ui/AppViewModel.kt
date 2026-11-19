package com.huanhren.deltaharmonica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.ContentResolver
import android.net.Uri
import com.huanhren.deltaharmonica.playback.*
import com.huanhren.deltaharmonica.data.DocumentTransfer
import com.huanhren.deltaharmonica.data.ImportCoordinator
import com.huanhren.deltaharmonica.data.ImportPhase
import com.huanhren.deltaharmonica.data.SongLibraryRepository
import com.huanhren.deltaharmonica.data.SongSourceType
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.accessibility.AccessibilityConnection
import com.huanhren.deltaharmonica.data.LayoutRepository
import com.huanhren.deltaharmonica.input.*
import com.huanhren.deltaharmonica.model.*
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
    val checkingRoot: Boolean = false,
    val enablingAccessibility: Boolean = false,
    val accessibilityGrantMessage: String = "",
    val testing: Boolean = false,
    val testMessage: String = "尚未测试",
    val hitMessage: String = "尚未收到测试触摸",
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
        loadLibrarySong(songs, id, midiWorkspace)
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

    fun startPreflight(layout: InstrumentLayout?, overlayAllowed: Boolean): Boolean {
        if (!currentPlaybackControls().canStart || imports.state.value.active || mutableLibraryBusy.value) return false
        val workspace = midiWorkspace
        val script = workspace.report?.script ?: return false
        val count = workspace.range.count(script.events.size)
        PlaybackPreparation.clearCompletedHistory()
        workspace.dryRun = null
        PlaybackPreparation.pending = null
        PlaybackPreparation.progress.value = PlaybackProgress(count, state = "正在检查脚本", phase = PlaybackStatus.CHECKING)
        return try {
            require(rootUiAllowed) { "隔离测试宿主不执行演奏" }
            require(overlayAllowed) { "请先授权悬浮窗" }
            val backend = accessibilityBackend.status()
            require(rootBackend.preferred || backend.available) { backend.message }
            require(!rootBackend.releaseUncertain) { "上次 Root 触摸释放待确认，请检查游戏状态" }
            val runtime = PlaybackPreparation.runtimeState.value
            require(runtime.syncStatus == SyncStatus.USER_CONFIRMED) { "请先同步游戏音区与半音" }
            val plan = PlaybackPlanner.plan(script, requireNotNull(layout) { "请先保存校准布局" },
                runtime.pitchRegion, runtime.semitoneEnabled, 0, count, workspace.speed, combined = workspace.combinedMode,
                minimumTouchMs = workspace.minimumTouchMs, combinedLeadMs = workspace.combinedLeadMs)
            workspace.dryRun = plan
            require(plan.events.size == count && count > 0 && plan.fatalErrors.isEmpty()) {
                plan.fatalErrors.firstOrNull() ?: "选定范围没有完整的可播放事件"
            }
            PlaybackPreparation.progress.value = PlaybackProgress(count, state = "等待确认演奏", phase = PlaybackStatus.CONFIRMING)
            true
        } catch (e: Exception) {
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

    private val importPipeline = ImportPipeline(songs, midiWorkspace, imports)
    fun importDocument(uri: Uri): Boolean = importPipeline.importDocument { DocumentTransfer.read(resolver, uri) }
    fun convertMidi(layout: InstrumentLayout?): Boolean = importPipeline.convertMidi(layout)
    private val mutableState = MutableStateFlow(AppUiState(selectedBackend =
        if (accessibilityBackend.status().availability != BackendAvailability.NOT_ENABLED) "Accessibility" else "Root"))
    val state = mutableState.asStateFlow()
    private var testJob: Job? = null

    init {
        rootBackend.preferred = state.value.selectedBackend == "Root"
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
    }

    fun refreshBackends() {
        mutableState.update { it.copy(backends = allBackends.map { backend -> backend.name to backend.status() }) }
    }

    fun selectBackend(name: String) {
        val backend = allBackends.firstOrNull { it.name == name } ?: return
        if (backend.status().availability == BackendAvailability.NOT_IMPLEMENTED || state.value.testing) return
        rootBackend.preferred = name == "Root"
        mutableState.update { it.copy(selectedBackend = name) }
    }

    fun checkRoot() {
        if (!rootUiAllowed || state.value.checkingRoot) return
        mutableState.update { it.copy(checkingRoot = true) }
        viewModelScope.launch {
            try { rootBackend.checkAccess() } finally {
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
                        rootBackend.preferred = false
                        mutableState.update { it.copy(selectedBackend = "Accessibility") }
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
        val inputBackend = allBackends.firstOrNull { it.name == state.value.selectedBackend } ?: return
        if (!inputBackend.status().available) {
            mutableState.update { it.copy(testMessage = inputBackend.status().message) }
            return
        }
        if (!before.supported || before.size.orientation != layout.orientation) {
            mutableState.update { it.copy(testMessage = "请使用布局对应方向的全屏主显示屏") }
            return
        }
        mutableState.update { it.copy(testing = true, hitMessage = "等待测试触摸…") }
        testJob = viewModelScope.launch {
            try {
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

