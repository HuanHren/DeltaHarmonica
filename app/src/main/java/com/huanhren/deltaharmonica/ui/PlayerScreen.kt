package com.huanhren.deltaharmonica.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.data.DocumentTransfer
import com.huanhren.deltaharmonica.input.displaySnapshot
import com.huanhren.deltaharmonica.midi.ScriptConverter
import com.huanhren.deltaharmonica.model.DefaultProfiles
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.SyncStatus
import com.huanhren.deltaharmonica.overlay.CalibrationOverlayService
import com.huanhren.deltaharmonica.overlay.OverlayGeometry
import com.huanhren.deltaharmonica.playback.*
import com.huanhren.deltaharmonica.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun PlayerScreen(activity: Activity, state: AppUiState, model: AppViewModel, onLibrary: () -> Unit,
                 onCalibrate: () -> Unit, onOverlay: () -> Unit) {
    val workspace = model.midiWorkspace
    val progress by PlaybackPreparation.progress.collectAsStateWithLifecycle()
    val runtime by PlaybackPreparation.runtimeState.collectAsStateWithLifecycle()
    val importState by model.imports.state.collectAsStateWithLifecycle()
    val libraryBusy by model.libraryBusy.collectAsStateWithLifecycle()
    val controls = playbackControls(progress, PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null)
    val editable = controls.canStart && !importState.active && !libraryBusy
    val report = workspace.report
    val script = report?.script
    var advanced by rememberSaveable { mutableStateOf(false) }
    var syncOpen by rememberSaveable { mutableStateOf(false) }
    var startAfterSync by rememberSaveable { mutableStateOf(false) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }
    var region by rememberSaveable { mutableStateOf<Region?>(null) }
    var semitone by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var calibrated by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val layout = state.saved
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && script != null) scope.launch {
            exporting = true
            try {
                DocumentTransfer.writeScript(activity.contentResolver, uri, script)
                workspace.message = "脚本已导出并重新读取验证"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { workspace.message = "导出失败：${e.localizedMessage}" }
            finally { exporting = false }
        }
    }
    fun preflight() {
        if (!Settings.canDrawOverlays(activity)) {
            activity.openOverlayPermission()
            return
        }
        if (model.startPreflight(layout, true)) {
            calibrated = PlaybackPreparation.controlsConfirmed
            confirmOpen = true
        } else advanced = true
    }
    fun openSync(start: Boolean) {
        region = runtime.pitchRegion
        semitone = runtime.semitoneEnabled
        startAfterSync = start
        syncOpen = true
    }
    Page("演奏") {
        if (script == null) {
            StatusCard("选择要演奏的曲目", "从曲库打开 MIDI 转换结果或 JSON 脚本。") {
                PrimaryButton("前往曲库", onLibrary)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 570.dp
                val overview: @Composable ColumnScope.() -> Unit = {
                    Text(workspace.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    SupportingText("${script.events.size} 个事件 · ${workspace.sourceFormat} · ${songDuration(script.events.lastOrNull()?.let { it.startTimeMs + it.durationMs })}")
                    PlayerProgress(progress, workspace.range.count(script.events.size))
                    controls.nextUnfinished?.let { SupportingText("未完成事件从第 $it 个开始", error = true) }
                }
                val options: @Composable ColumnScope.() -> Unit = {
                    SectionTitle("播放选项")
                    Choices(PlaybackRange.entries, workspace.range, { it.label },
                        { value -> model.changePlan { range = value } }, enabled = editable)
                    Choices(listOf(1.0, 2.0), workspace.speed, { "${it.toInt()}×" },
                        { value -> model.changePlan { speed = value } }, enabled = editable)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        PrimaryButton(if (progress.phase == PlaybackStatus.COMPLETED) "重新演奏" else "开始演奏", {
                            if (runtime.syncStatus == SyncStatus.USER_CONFIRMED) preflight() else openSync(true)
                        }, Modifier.testTag("player/start"), enabled = editable && model.rootUiAllowed && layout != null)
                        OutlinedButton(onClick = { PlaybackPreparation.stopPlayback() },
                            modifier = Modifier.testTag("player/stop"), enabled = controls.canStop) { Text("停止") }
                    }
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.card)) {
                        ProductCard(Modifier.weight(1.15f), content = overview)
                        ProductCard(Modifier.weight(1f), content = options)
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(Spacing.card)) {
                    ProductCard(content = overview)
                    ProductCard(content = options)
                }
            }
            ProductCard {
                SectionTitle("演奏准备")
                val display = displaySnapshot(activity)
                SupportingText(playbackLayoutStatus(layout?.referenceDisplay != null, activity.isInMultiWindowMode,
                    layout != null && display.supported && OverlayGeometry.compatible(layout, display.size)))
                SupportingText("${state.selectedBackend} · ${state.selectedBackendStatus?.message ?: "正在读取"}")
                SupportingText("游戏状态 · ${runtime.pitchRegion?.label ?: "未同步"} · 半音${runtime.semitoneEnabled?.let { if (it) "开" else "关" } ?: "未同步"}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    TextButton(onClick = { openSync(false) }, enabled = editable) { Text("同步游戏状态") }
                    if (layout == null) TextButton(onClick = onCalibrate, enabled = !state.loading) { Text("校准布局") }
                    if (model.rootUiAllowed) TextButton(onClick = onOverlay) { Text("打开游戏悬浮窗") }
                }
            }
            workspace.message.takeIf { it.isNotBlank() }?.let { SupportingText(it) }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置与诊断") }
            if (advanced) {
                PlaybackDiagnostics(model, editable)
                OutlinedButton(enabled = editable && !exporting, onClick = {
                    if (ScriptConverter.matchesProfile(script, layout?.profile ?: DefaultProfiles.delta(), layout))
                        export.launch("harmonica-script.json")
                    else workspace.message = "乐器配置或键位映射已改变，请重新转换"
                }) { Text("导出 JSON") }
            }
        }
    }
    if (syncOpen) ProductDialog("同步游戏状态", { syncOpen = false }, "确认", {
        if (currentPlaybackControls().canStart && region != null && semitone != null) {
            PlaybackPreparation.runtimeState.value = PlaybackPreparation.runtimeState.value
                .confirmRegion(requireNotNull(region)).confirmSemitone(requireNotNull(semitone))
            syncOpen = false
            if (startAfterSync) preflight()
        }
    }, enabled = editable && region != null && semitone != null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            SupportingText("根据游戏口琴界面选择当前音区和半音状态。")
            Choices<Region?>(Region.entries, region, { it?.label.orEmpty() }, { region = it })
            Choices<Boolean?>(listOf(false, true), semitone, { if (it == true) "半音开" else "半音关" }, { semitone = it })
        }
    }
    val plan = workspace.dryRun
    if (confirmOpen && progress.phase == PlaybackStatus.CONFIRMING && plan != null) {
        val close = { confirmOpen = false; model.cancelPreflight() }
        ProductDialog("确认演奏", close, "确认并准备", {
            confirmOpen = false
            model.confirmPreflight {
                activity.startService(Intent(activity, CalibrationOverlayService::class.java)
                    .setAction(CalibrationOverlayService.ACTION_START_PLAYBACK))
            }
        }, enabled = calibrated) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                SupportingText("${plan.events.size} 个事件 · 预计 ${songDuration(plan.estimatedTotalMs)} · 确认后切回游戏口琴界面")
                if (plan.warnings.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 145.dp)) {
                    items(plan.warnings) { SupportingText(it, error = true) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(calibrated, { calibrated = it })
                    SupportingText("我已校准所有目标，并确认游戏处于口琴界面。")
                }
            }
        }
    }
}

internal val Region.label: String get() = when (this) {
    Region.HIGH -> "升调"
    Region.NATURAL -> "自然"
    Region.LOW -> "降调"
}

@Composable
internal fun PlayerProgress(progress: PlaybackProgress, planned: Int) {
    val fraction by animateFloatAsState(playbackFraction(progress), tween(140), label = "playback-progress")
    val colors = MaterialTheme.colorScheme
    val statusColor = if (progress.phase == PlaybackStatus.FAILED) colors.error else colors.primary
    Text(progress.current ?: "—", color = statusColor, style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.testTag("player/current-note"))
    Text(progress.state, color = statusColor, style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).testTag("player/progress"))
    SupportingText(if (progress.total > 0) "${progress.completed} / ${progress.total} 个事件" else "已选 $planned 个事件")
}

@Composable
private fun PlaybackDiagnostics(model: AppViewModel, editable: Boolean) {
    val workspace = model.midiWorkspace
    val report = workspace.report ?: return
    var page by rememberSaveable(workspace.songId, workspace.dryRun?.events?.size) { mutableIntStateOf(0) }
    ProductCard {
        SectionTitle("演奏参数")
        TextButton(onClick = { model.changePlan { speed = .5 } }, enabled = editable) {
            Text(if (workspace.speed == .5) "✓ 0.5× 慢速" else "0.5× 慢速")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(!workspace.combinedMode, { separate -> model.changePlan { combinedMode = !separate } }, enabled = editable)
            SupportingText("兼容模式 · 控制与音符分开触摸")
        }
        if (workspace.combinedMode) MillisecondControl("组合手势控制提前", workspace.combinedLeadMs, 0L..70L, editable) {
            model.changePlan { combinedLeadMs = it }
        }
        MillisecondControl("最短触摸", workspace.minimumTouchMs, 1L..500L, editable) {
            model.changePlan { minimumTouchMs = it }
        }
    }
    ProductCard {
        SectionTitle("脚本与时序")
        val provenance = report.script.provenance
        SupportingText("脚本 v${report.script.schemaVersion} · 原轨道音符 ${provenance?.sourceNoteCount ?: "未知"} · 未映射 ${provenance?.unmappedCount ?: "未知"}")
        (report.warnings + workspace.dryRun?.warnings.orEmpty()).distinct().take(100).forEach { SupportingText(it, error = true) }
        workspace.dryRun?.let { plan ->
            plan.fatalErrors.forEach { SupportingText(it, error = true) }
            SupportingText("预计切换音区 ${plan.regionSwitches} 次 · 半音 ${plan.semitoneSwitches} 次 · 延长短音 ${plan.extendedNotes} 个")
            val lastPage = ((plan.events.size - 1) / 50).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
                Text("${page + 1} / ${lastPage + 1}")
                TextButton(onClick = { page++ }, enabled = page < lastPage) { Text("下一页") }
            }
            plan.events.drop(page * 50).take(50).forEach { event ->
                SupportingText("${event.sourceIndex + 1}. ${event.event.noteKeyId} · 原始 ${event.event.startTimeMs} ms · 预计 ${event.predictedStartMs}–${event.predictedEndMs} ms")
                PlaybackPreparation.actualTimings[event.sourceIndex]?.let { actual ->
                    SupportingText("派发 ${actual.dispatchMs} ms · 回调 / 抬起 ${actual.callbackMs ?: "—"} ms · ${actual.result}")
                }
            }
        }
    }
}

@Composable
private fun MillisecondControl(title: String, value: Long, limits: LongRange, enabled: Boolean, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange((value - 10).coerceIn(limits)) }, enabled = enabled && value > limits.first) { Text("−") }
        SupportingText("$title $value ms")
        TextButton(onClick = { onChange((value + 10).coerceIn(limits)) }, enabled = enabled && value < limits.last) { Text("＋") }
    }
}
