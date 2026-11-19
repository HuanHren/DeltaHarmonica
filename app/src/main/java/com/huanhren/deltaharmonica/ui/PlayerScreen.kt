package com.huanhren.deltaharmonica.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.R
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
import top.yukonga.miuix.kmp.basic.*

@Composable
fun PlayerScreen(activity: Activity, state: AppUiState, model: AppViewModel, onLibrary: () -> Unit,
                 onCalibrate: () -> Unit, onOverlay: () -> Unit, onCollapse: () -> Unit = onLibrary,
                 startRequested: Boolean = false, consumeStartRequest: () -> Unit = {}) {
    val workspace = model.midiWorkspace
    val progress by rememberControlsProgress()
    val runtime by PlaybackPreparation.runtimeState.collectAsStateWithLifecycle()
    val importState by model.imports.state.collectAsStateWithLifecycle()
    val libraryBusy by model.libraryBusy.collectAsStateWithLifecycle()
    val controls = playbackControls(progress, PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null)
    val editable = controls.canStart && !importState.active && !libraryBusy
    val report = workspace.report
    val script = report?.script
    var advanced by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var syncOpen by rememberSaveable { mutableStateOf(false) }
    var startAfterSync by rememberSaveable { mutableStateOf(false) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }
    var region by rememberSaveable { mutableStateOf<Region?>(null) }
    var semitone by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var calibrated by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportNotice by rememberSaveable(workspace.songId) { mutableIntStateOf(0) }
    var exportFailure by rememberSaveable(workspace.songId) { mutableStateOf<String?>(null) }
    val layout = state.saved
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && script != null) scope.launch {
            exporting = true
            try {
                DocumentTransfer.writeScript(activity.contentResolver, uri, script)
                exportNotice = R.string.player_export_done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exportFailure = e.localizedMessage
                exportNotice = R.string.player_export_error
            } finally {
                exporting = false
            }
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
    fun performMainAction() {
        when (progress.phase) {
            PlaybackStatus.PLAYING, PlaybackStatus.PAUSED -> PlaybackPreparation.togglePause()
            else -> {
                if (!editable || !model.rootUiAllowed) return
                if (layout == null) onCalibrate()
                else if (runtime.syncStatus == SyncStatus.USER_CONFIRMED) preflight()
                else openSync(true)
            }
        }
    }
    LaunchedEffect(startRequested) {
        if (startRequested) {
            consumeStartRequest()
            performMainAction()
        }
    }
    val colors = StudioTheme.colorScheme
    val id = workspace.songId ?: workspace.name
    val gutters = LocalStudioGutters.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicIconButton(ProductIcon.Down, stringResource(R.string.session_collapse), onCollapse, Modifier.testTag("player/collapse"))
            Text(stringResource(R.string.nav_player), Modifier.weight(1f).padding(horizontal = 6.dp),
                color = colors.onSurface, style = StudioTypography.titleMedium)
            if (script != null) MusicIconButton(ProductIcon.More, stringResource(R.string.player_more_details),
                { details = true }, Modifier.testTag("player/details"))
        }
        if (script == null) {
            Column(Modifier.fillMaxWidth().padding(Spacing.page), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(24.dp))
                MusicArtwork("DeltaHarmonica", Modifier.size(100.dp), 20.dp)
                SectionTitle(stringResource(R.string.player_pick))
                SupportingText(stringResource(R.string.player_pick_detail))
                PrimaryButton(stringResource(R.string.player_open_library), onLibrary)
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(bottom = gutters.calculateBottomPadding()).clipToBounds(),
                contentAlignment = Alignment.TopCenter) {
                val wide = maxWidth >= 580.dp
                val coverSize = if (wide) (maxHeight - 4.dp).coerceIn(112.dp, 290.dp)
                    else (maxHeight - 365.dp).coerceIn(138.dp, 286.dp).coerceAtMost((maxWidth - 48.dp).coerceAtLeast(96.dp))
                val plannedCount = workspace.range.count(script.events.size)
                val plannedMs = workspace.dryRun?.estimatedTotalMs ?: script.events.getOrNull(plannedCount - 1)?.let {
                    ((it.startTimeMs + it.durationMs - script.events.first().startTimeMs) / workspace.speed).toLong()
                } ?: 0L
                val controlsBody: @Composable ColumnScope.() -> Unit = {
                    Text(workspace.name, Modifier.coverTransition(id, "player", "title"), style = StudioTypography.headlineMedium,
                        color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val count = pluralStringResource(R.plurals.music_note_count, script.events.size, script.events.size)
                    SupportingText(stringResource(R.string.library_track_meta, workspace.sourceFormat, count))
                    NowPlayingTimeline(plannedCount, plannedMs)
                    val isPausing = progress.phase == PlaybackStatus.PAUSING
                    val isPlaying = progress.phase == PlaybackStatus.PLAYING
                    val mainAction = when (progress.phase) {
                        PlaybackStatus.PLAYING, PlaybackStatus.PAUSING -> R.string.session_pause
                        PlaybackStatus.PAUSED -> R.string.session_resume
                        PlaybackStatus.COMPLETED -> R.string.player_restart
                        else -> R.string.studio_play
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        MusicIconButton(if (isPlaying || isPausing) ProductIcon.Pause else ProductIcon.Play, stringResource(mainAction),
                            ::performMainAction, Modifier.testTag("player/start"),
                            enabled = isPlaying || progress.phase == PlaybackStatus.PAUSED || (editable && model.rootUiAllowed && !state.loading),
                            background = colors.primary, foreground = colors.onPrimary,
                            extent = maxOf(if (wide) 56.dp else 64.dp, Spacing.touchTarget), glyphSize = 29.dp, animateGlyph = true)
                        MusicIconButton(ProductIcon.Stop, stringResource(R.string.overlay_stop), { PlaybackPreparation.stopPlayback() },
                            Modifier.align(Alignment.CenterEnd).testTag("player/stop"), enabled = controls.canStop,
                            background = if (controls.canStop) colors.errorContainer else colors.onSurface.copy(alpha = .06f),
                            foreground = if (controls.canStop) colors.onErrorContainer else colors.onSurface)
                    }
                    if (layout == null && model.rootUiAllowed) SupportingText(stringResource(R.string.player_not_calibrated))
                    val rangeLabels = PlaybackRange.entries.map { stringResource(it.rangeLabel()) }
                    val speedLabels = listOf(stringResource(R.string.player_speed, 1), stringResource(R.string.player_speed, 2))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SupportingText(stringResource(R.string.player_range_title))
                            Choices(PlaybackRange.entries, workspace.range, { rangeLabels[it.ordinal] },
                                { value -> model.changePlan { range = value } }, enabled = editable)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SupportingText(stringResource(R.string.player_speed_title))
                            Choices(listOf(1.0, 2.0), workspace.speed, { if (it == 1.0) speedLabels.first() else speedLabels.last() },
                                { value -> model.changePlan { speed = value } }, enabled = editable)
                        }
                    }
                    if (model.rootUiAllowed) TextAction(onClick = onOverlay) { Text(stringResource(R.string.player_open_game_controls)) }
                }
                Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)
                    .padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        MusicArtwork(id, Modifier.size(coverSize), 24.dp, "player")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = controlsBody)
                    } else {
                        MusicArtwork(id, Modifier.size(coverSize).align(Alignment.CenterHorizontally), 24.dp, "player")
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = controlsBody)
                    }
                    if (exportNotice != 0) {
                        if (exportNotice == R.string.player_export_error) {
                            val reason = exportFailure?.let { musicDiagnostic(it) } ?: stringResource(R.string.music_unknown)
                            SupportingText(stringResource(R.string.player_export_error, reason), error = true)
                        } else SupportingText(stringResource(exportNotice), error = exportNotice == R.string.player_profile_changed)
                    }
                    if (advanced) {
                        TextAction({ advanced = false }) { Text(stringResource(R.string.player_hide_advanced)) }
                        PlaybackDiagnostics(model, progress, editable)
                        SecondaryAction(enabled = editable && !exporting, onClick = {
                            if (ScriptConverter.matchesProfile(script, layout?.profile ?: DefaultProfiles.delta(), layout))
                                export.launch("harmonica-script.json")
                            else exportNotice = R.string.player_profile_changed
                        }) { Text(stringResource(R.string.player_export)) }
                    }
                }
            }
        }
    }
    if (details && script != null) ProductSheet(stringResource(R.string.player_more_details), { details = false }) {
        val display = displaySnapshot(activity)
        val layoutMessage = when {
            layout?.referenceDisplay == null -> R.string.player_layout_missing
            activity.isInMultiWindowMode -> R.string.player_layout_check_game
            display.supported && OverlayGeometry.compatible(layout, display.size) -> R.string.player_layout_match
            else -> R.string.player_layout_mismatch
        }
        SupportingText(stringResource(layoutMessage))
        SupportingText(stringResource(R.string.music_backend_status, state.selectedBackend,
            musicBackendStatus(state.selectedBackendStatus?.message)))
        SupportingText(stringResource(R.string.player_runtime, musicRegion(runtime.pitchRegion), musicSemitone(runtime.semitoneEnabled)))
        top.yukonga.miuix.kmp.preference.ArrowPreference(stringResource(R.string.overlay_sync), enabled = editable,
            onClick = { details = false; openSync(false) })
        top.yukonga.miuix.kmp.preference.ArrowPreference(stringResource(R.string.calibrate), enabled = editable,
            onClick = { details = false; onCalibrate() })
        top.yukonga.miuix.kmp.preference.ArrowPreference(stringResource(R.string.player_show_advanced),
            onClick = { details = false; advanced = true })
    }
    if (syncOpen) ProductDialog(stringResource(R.string.overlay_sync), { syncOpen = false }, stringResource(R.string.action_confirm), {
        if (currentPlaybackControls().canStart && region != null && semitone != null) {
            PlaybackPreparation.runtimeState.value = PlaybackPreparation.runtimeState.value
                .confirmRegion(requireNotNull(region)).confirmSemitone(requireNotNull(semitone))
            syncOpen = false
            if (startAfterSync) preflight()
        }
    }, enabled = editable && region != null && semitone != null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            SupportingText(stringResource(R.string.player_sync_detail))
            val regions = Region.entries.map { it to musicRegion(it) }.toMap()
            val on = musicSemitone(true)
            val off = musicSemitone(false)
            Choices<Region?>(Region.entries, region, { regions[it].orEmpty() }, { region = it })
            Choices<Boolean?>(listOf(false, true), semitone, { if (it == true) on else off }, { semitone = it })
        }
    }
    val plan = workspace.dryRun
    if (confirmOpen && progress.phase == PlaybackStatus.CONFIRMING && plan != null) {
        val close = { confirmOpen = false; model.cancelPreflight() }
        ProductDialog(stringResource(R.string.overlay_confirm), close, stringResource(R.string.player_confirm_prepare), {
            confirmOpen = false
            model.confirmPreflight {
                activity.startService(Intent(activity, CalibrationOverlayService::class.java)
                    .setAction(CalibrationOverlayService.ACTION_START_PLAYBACK))
            }
        }, enabled = calibrated) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                val eventCount = pluralStringResource(R.plurals.music_event_count, plan.events.size, plan.events.size)
                SupportingText(stringResource(R.string.player_confirm_meta, eventCount, musicDuration(plan.estimatedTotalMs)))
                if (plan.warnings.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 145.dp)) {
                    items(plan.warnings) { SupportingText(musicDiagnostic(it), error = true) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ToggleableState(calibrated), { calibrated = !calibrated })
                    SupportingText(stringResource(R.string.player_confirm_calibrated))
                }
            }
        }
    }
}

@Composable
private fun NowPlayingTimeline(planned: Int, plannedMs: Long) {
    val progress by PlaybackPreparation.progress.collectAsStateWithLifecycle()
    PlayerProgress(progress, planned, plannedMs = plannedMs)
}

@Composable
internal fun PlayerProgress(progress: PlaybackProgress, planned: Int, noteLabel: String? = progress.current, plannedMs: Long = 0L) {
    val colors = StudioTheme.colorScheme
    val error = progress.phase == PlaybackStatus.FAILED
    val duration = progress.durationMs.takeIf { it > 0 } ?: plannedMs
    val elapsed = if (progress.phase == PlaybackStatus.COMPLETED && !progress.stopped && progress.total > 0)
        duration else progress.elapsedMs.coerceIn(0L, duration)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(sessionStatus(progress)), style = StudioTypography.labelLarge,
                color = if (error) colors.error else colors.primary)
            Spacer(Modifier.weight(1f))
            val note = noteLabel?.removePrefix("NOTE_")?.toIntOrNull()
            val displayNote = when {
                note != null && plannedMs > 0 -> stringResource(R.string.music_numbered_key, note)
                noteLabel != null -> noteLabel
                else -> ""
            }
            SupportingText(displayNote, Modifier.testTag("player/current-note"), maxLines = 1)
        }
        SessionProgressLine(sessionFraction(progress), Modifier.testTag("player/progress"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SupportingText(musicDuration(elapsed))
            SupportingText(musicDuration(duration))
        }
        if (progress.phase == PlaybackStatus.WAITING_FOR_GAME) SupportingText(stringResource(R.string.player_phase_waiting_detail))
        if (error && progress.state != "演奏异常") SupportingText(musicDiagnostic(progress.state), error = true, maxLines = 3)
    }
}

internal fun PlaybackRange.rangeLabel() = when (this) {
    PlaybackRange.FIRST_8 -> R.string.player_range_8
    PlaybackRange.FIRST_16 -> R.string.player_range_16
    PlaybackRange.ALL -> R.string.player_range_all
}

@Composable
private fun PlaybackDiagnostics(model: AppViewModel, progress: PlaybackProgress, editable: Boolean) {
    val workspace = model.midiWorkspace
    val report = workspace.report ?: return
    var page by rememberSaveable(workspace.songId, workspace.dryRun?.events?.size) { mutableIntStateOf(0) }
    ProductCard {
        SectionTitle(stringResource(R.string.player_settings))
        TextAction(onClick = { model.changePlan { speed = .5 } }, enabled = editable) {
            val slow = stringResource(R.string.player_slow)
            Text(if (workspace.speed == .5) stringResource(R.string.music_checked, slow) else slow)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ToggleableState(!workspace.combinedMode),
                { model.changePlan { combinedMode = !combinedMode } }, enabled = editable)
            SupportingText(stringResource(R.string.player_separate))
        }
        if (workspace.combinedMode) MillisecondControl(stringResource(R.string.player_control_lead), workspace.combinedLeadMs, 0L..70L, editable) {
            model.changePlan { combinedLeadMs = it }
        }
        MillisecondControl(stringResource(R.string.player_min_touch), workspace.minimumTouchMs, 1L..500L, editable) {
            model.changePlan { minimumTouchMs = it }
        }
    }
    ProductCard {
        SectionTitle(stringResource(R.string.player_script_timing))
        SupportingText(musicDiagnostic(progress.state), error = progress.phase == PlaybackStatus.FAILED)
        val provenance = report.script.provenance
        val unknown = stringResource(R.string.music_unknown)
        val count = provenance?.sourceNoteCount?.let { musicNumber(it) } ?: unknown
        val unmapped = provenance?.unmappedCount?.let { musicNumber(it) } ?: unknown
        SupportingText(stringResource(R.string.player_script_metadata, report.script.schemaVersion, count, unmapped))
        (report.warnings + workspace.dryRun?.warnings.orEmpty()).distinct().take(100).forEach { SupportingText(musicDiagnostic(it), error = true) }
        workspace.dryRun?.let { plan ->
            plan.fatalErrors.forEach { SupportingText(musicDiagnostic(it), error = true) }
            SupportingText(stringResource(R.string.player_switch_counts, plan.regionSwitches, plan.semitoneSwitches, plan.extendedNotes))
            val lastPage = ((plan.events.size - 1) / 50).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction(onClick = { page-- }, enabled = page > 0) { Text(stringResource(R.string.player_prev_page)) }
                Text(stringResource(R.string.player_page_count, page + 1, lastPage + 1))
                TextAction(onClick = { page++ }, enabled = page < lastPage) { Text(stringResource(R.string.player_next_page)) }
            }
            plan.events.drop(page * 50).take(50).forEach { event ->
                SupportingText(stringResource(R.string.player_event_line, event.sourceIndex + 1, event.event.noteKeyId,
                    event.event.startTimeMs, event.predictedStartMs, event.predictedEndMs))
                PlaybackPreparation.actualTimings[event.sourceIndex]?.let { actual ->
                    SupportingText(stringResource(R.string.player_event_actual, actual.dispatchMs,
                        actual.callbackMs?.let { musicNumber(it) } ?: "—", actual.result))
                }
            }
        }
    }
}

@Composable
private fun MillisecondControl(title: String, value: Long, limits: LongRange, enabled: Boolean, onChange: (Long) -> Unit) {
    QuantityControl(title, stringResource(R.string.player_ms_control, title, value), enabled && value > limits.first,
        enabled && value < limits.last, { onChange((value - 10).coerceIn(limits)) }, { onChange((value + 10).coerceIn(limits)) })
}
