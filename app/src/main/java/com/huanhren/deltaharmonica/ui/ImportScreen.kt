package com.huanhren.deltaharmonica.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.data.ImportPhase
import com.huanhren.deltaharmonica.data.ImportUiState
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun ImportScreen(activity: Activity, layout: InstrumentLayout?, model: AppViewModel, onBack: () -> Unit,
                 onPlayer: () -> Unit, onLibrary: () -> Unit) {
    val workspace = model.midiWorkspace
    val importState by model.imports.state.collectAsStateWithLifecycle()
    val busy by model.libraryBusy.collectAsStateWithLifecycle()
    val progress by rememberControlsProgress()
    val allowed = !busy && !importState.active && playbackControls(progress,
        PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null).canStart
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importDocument(uri)
    }
    var recoveryAccepted by rememberSaveable(workspace.candidateToken) { mutableStateOf(false) }
    var tracksOpen by rememberSaveable { mutableStateOf(false) }
    Page(stringResource(R.string.import_title), onBack = onBack) {
        ProductCard {
            SectionTitle(stringResource(R.string.import_from_file))
            SupportingText(stringResource(R.string.import_from_file_detail))
            PrimaryButton(stringResource(R.string.import_pick_file), { picker.launch(arrayOf("*/*")) },
                Modifier.fillMaxWidth().testTag("import/pick"), enabled = allowed)
        }
        if (importState.phase != ImportPhase.IDLE) {
            ImportStatusPanel(importState, onCancel = { model.imports.cancel() }, onPlayer = onPlayer, onLibrary = onLibrary)
        }
        val midi = workspace.parsed
        if (midi != null) {
            ProductCard {
                SectionTitle(workspace.candidateName)
                SupportingText(stringResource(R.string.import_midi_meta, midi.format, midi.tracks.size, musicDuration(midi.durationMs)))
                midi.warnings.take(10).forEach { SupportingText(musicDiagnostic(it), error = true) }
                SecondaryAction(onClick = { tracksOpen = true }, enabled = allowed) {
                    val index = workspace.candidateTrack
                    Text(midi.tracks.getOrNull(index)?.let { trackTitle(index, it.name) }
                        ?: stringResource(R.string.import_select_track))
                }
                Text(stringResource(R.string.import_monophony), style = StudioTheme.typography.titleMedium)
                val policies = MonophonyPolicy.entries.associateWith {
                    stringResource(when (it) {
                        MonophonyPolicy.HIGHEST_NOTE -> R.string.import_highest
                        MonophonyPolicy.LOWEST_NOTE -> R.string.import_lowest
                        MonophonyPolicy.LONGEST_NOTE -> R.string.import_longest
                        MonophonyPolicy.TRACK_SELECTION -> R.string.import_track_policy
                    })
                }
                Choices(MonophonyPolicy.entries, workspace.candidatePolicy, { policies.getValue(it) },
                    { workspace.candidatePolicy = it }, enabled = allowed)
                QuantityControl(
                    stringResource(R.string.import_transpose_label),
                    stringResource(R.string.import_transpose, workspace.candidateTranspose),
                    allowed && workspace.candidateTranspose > -24,
                    allowed && workspace.candidateTranspose < 24,
                    { workspace.candidateTranspose-- },
                    { workspace.candidateTranspose++ },
                )
                if (midi.requiresConfirmation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(ToggleableState(recoveryAccepted),
                            { recoveryAccepted = !recoveryAccepted }, enabled = allowed)
                        SupportingText(stringResource(R.string.import_recovery_accept))
                    }
                }
                PrimaryButton(stringResource(R.string.import_convert_save), { model.convertMidi(layout) }, Modifier.fillMaxWidth(),
                    enabled = allowed && (!midi.requiresConfirmation || recoveryAccepted))
            }
            if (tracksOpen) ProductDialog(stringResource(R.string.import_select_track), { tracksOpen = false },
                stringResource(R.string.import_done), { tracksOpen = false }) {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    itemsIndexed(midi.tracks) { index, track ->
                        TextAction(enabled = track.notes.isNotEmpty(), onClick = {
                            workspace.candidateTrack = index
                            tracksOpen = false
                        }) {
                            val noteCount = pluralStringResource(R.plurals.music_note_count, track.notes.size, track.notes.size)
                            val label = stringResource(R.string.import_track_note_count, trackTitle(index, track.name), noteCount)
                            Text(if (index == workspace.candidateTrack) stringResource(R.string.music_checked, label) else label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun trackTitle(index: Int, title: String): String =
    if (title == "轨道 ${index + 1}") stringResource(R.string.import_track, index + 1)
    else stringResource(R.string.import_named_track, index + 1, title)

@Composable
internal fun ImportStatusPanel(state: ImportUiState, onCancel: () -> Unit, onPlayer: () -> Unit, onLibrary: () -> Unit) {
    val title = when (state.phase) {
        ImportPhase.IDLE -> R.string.import_phase_idle
        ImportPhase.READING -> R.string.import_phase_reading
        ImportPhase.PREFLIGHT -> R.string.import_phase_checking
        ImportPhase.PARSING -> R.string.import_phase_parsing
        ImportPhase.CONVERTING -> R.string.import_phase_converting
        ImportPhase.VALIDATING -> R.string.import_phase_validating
        ImportPhase.SAVING -> R.string.import_phase_saving
        ImportPhase.COMMITTED -> if (state.active) R.string.import_phase_loading else R.string.import_phase_committed
        ImportPhase.READY -> R.string.import_select_track
        ImportPhase.CANCELLED -> if (state.committed) R.string.import_phase_saved else R.string.import_phase_cancelled
        ImportPhase.FAILED -> if (state.committed) R.string.import_phase_failed_saved else R.string.import_phase_failed
    }
    StatusCard(stringResource(title), importDetail(state), loading = state.active, error = state.phase == ImportPhase.FAILED) {
        if (state.rejection != null) SupportingText(stringResource(R.string.import_other_active), error = true)
        if (state.active) {
            TextAction(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) }
        } else if (state.committed) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                if (state.phase == ImportPhase.COMMITTED) PrimaryButton(stringResource(R.string.import_open_player), onPlayer)
                TextAction(onClick = onLibrary) { Text(stringResource(R.string.import_view_library)) }
            }
        }
    }
}

@Composable
private fun importDetail(state: ImportUiState): String {
    val detail = when (state.phase) {
        ImportPhase.READING -> R.string.import_detail_reading
        ImportPhase.PREFLIGHT -> R.string.import_detail_checking
        ImportPhase.PARSING -> if (state.message.contains("JSON")) R.string.import_detail_parsing_json else R.string.import_detail_parsing_midi
        ImportPhase.CONVERTING -> R.string.import_detail_converting
        ImportPhase.SAVING -> R.string.import_detail_saving
        ImportPhase.COMMITTED -> when {
            state.active -> R.string.import_detail_loading
            state.message.startsWith("转换") -> R.string.import_detail_midi_saved
            else -> R.string.import_detail_json_saved
        }
        ImportPhase.READY -> if (state.message.startsWith("已恢复")) R.string.import_detail_recovered else R.string.import_detail_track_select
        ImportPhase.CANCELLED -> when {
            state.committed -> R.string.import_detail_cancelled_saved
            state.message.contains("恢复") -> R.string.import_detail_cancelled_recovery
            else -> R.string.import_detail_cancelled
        }
        ImportPhase.FAILED -> {
            val reason = musicDiagnostic(state.message.substringAfter('：').ifBlank { "未知错误" })
            return stringResource(if (state.committed) R.string.import_detail_failed_saved else R.string.import_detail_failed, reason)
        }
        ImportPhase.VALIDATING, ImportPhase.IDLE -> return musicDiagnostic(state.message)
    }
    return stringResource(detail)
}

@Composable
internal fun QuantityControl(label: String, value: String, canDecrease: Boolean, canIncrease: Boolean,
                             onDecrease: () -> Unit, onIncrease: () -> Unit) {
    val decrease = stringResource(R.string.music_decrease, label)
    val increase = stringResource(R.string.music_increase, label)
    FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        TextAction(onClick = onDecrease, modifier = Modifier.semantics { contentDescription = decrease }, enabled = canDecrease) {
            Text("−")
        }
        SupportingText(value)
        TextAction(onClick = onIncrease, modifier = Modifier.semantics { contentDescription = increase }, enabled = canIncrease) {
            Text("+")
        }
    }
}
