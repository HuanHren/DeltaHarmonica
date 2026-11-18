package com.huanhren.deltaharmonica.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.data.ImportPhase
import com.huanhren.deltaharmonica.data.ImportUiState
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun ImportScreen(activity: Activity, layout: InstrumentLayout?, model: AppViewModel, onBack: () -> Unit,
                 onPlayer: () -> Unit, onLibrary: () -> Unit) {
    val workspace = model.midiWorkspace
    val importState by model.imports.state.collectAsStateWithLifecycle()
    val busy by model.libraryBusy.collectAsStateWithLifecycle()
    val progress by PlaybackPreparation.progress.collectAsStateWithLifecycle()
    val allowed = !busy && !importState.active && playbackControls(progress,
        PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null).canStart
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importDocument(uri)
    }
    var recoveryAccepted by rememberSaveable(workspace.candidateToken) { mutableStateOf(false) }
    var tracksOpen by rememberSaveable { mutableStateOf(false) }
    Page("导入曲目", onBack = onBack) {
        ProductCard {
            SectionTitle("从文件添加")
            SupportingText("MIDI 可选择旋律轨道后转换。JSON 演奏脚本可直接加入曲库。")
            PrimaryButton("选择 MIDI / JSON 文件", { picker.launch(arrayOf("*/*")) },
                Modifier.fillMaxWidth().testTag("import/pick"), enabled = allowed)
        }
        if (importState.phase != ImportPhase.IDLE) {
            ImportStatusPanel(importState, onCancel = { model.imports.cancel() }, onPlayer = onPlayer, onLibrary = onLibrary)
        }
        val midi = workspace.parsed
        if (midi != null) {
            ProductCard {
                SectionTitle(workspace.candidateName)
                SupportingText("MIDI Type ${midi.format} · ${midi.tracks.size} 条轨道 · ${songDuration(midi.durationMs)}")
                midi.warnings.take(10).forEach { SupportingText(it, error = true) }
                OutlinedButton(onClick = { tracksOpen = true }, enabled = allowed) {
                    val index = workspace.candidateTrack
                    Text(midi.tracks.getOrNull(index)?.let { "轨道 ${index + 1} · ${it.name}" } ?: "选择旋律轨道")
                }
                Text("单音化策略", style = MaterialTheme.typography.titleMedium)
                Choices(MonophonyPolicy.entries, workspace.candidatePolicy, { it.name },
                    { workspace.candidatePolicy = it }, enabled = allowed)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { workspace.candidateTranspose-- }, enabled = allowed && workspace.candidateTranspose > -24) { Text("−") }
                    Text("移调 ${workspace.candidateTranspose} 半音")
                    TextButton(onClick = { workspace.candidateTranspose++ }, enabled = allowed && workspace.candidateTranspose < 24) { Text("＋") }
                }
                if (midi.requiresConfirmation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(recoveryAccepted, { recoveryAccepted = it }, enabled = allowed)
                        SupportingText("我已了解恢复过程中可能丢失音符")
                    }
                }
                PrimaryButton("转换并保存", { model.convertMidi(layout) }, Modifier.fillMaxWidth(),
                    enabled = allowed && (!midi.requiresConfirmation || recoveryAccepted))
            }
            if (tracksOpen) ProductDialog("选择旋律轨道", { tracksOpen = false }, "完成", { tracksOpen = false }) {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    itemsIndexed(midi.tracks) { index, track ->
                        androidx.compose.material3.TextButton(enabled = track.notes.isNotEmpty(), onClick = {
                            workspace.candidateTrack = index
                            tracksOpen = false
                        }) { Text("${if (index == workspace.candidateTrack) "✓ " else ""}${index + 1}. ${track.name} · ${track.notes.size} 音") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImportStatusPanel(state: ImportUiState, onCancel: () -> Unit, onPlayer: () -> Unit, onLibrary: () -> Unit) {
    StatusCard(importStage(state), state.message, loading = state.active, error = state.phase == ImportPhase.FAILED) {
        state.rejection?.let { SupportingText(it, error = true) }
        if (state.active) {
            TextButton(onClick = onCancel) { Text("取消导入") }
        } else if (state.committed) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                if (state.phase == ImportPhase.COMMITTED) PrimaryButton("打开播放器", onPlayer)
                TextButton(onClick = onLibrary) { Text("查看曲库") }
            }
        }
    }
}
