package com.huanhren.deltaharmonica.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.input.BackendAvailability
import com.huanhren.deltaharmonica.input.BackendStatus
import com.huanhren.deltaharmonica.input.RootAuthorizationState
import com.huanhren.deltaharmonica.input.RootCleanup
import com.huanhren.deltaharmonica.input.RootInputReadiness
import com.huanhren.deltaharmonica.input.RootPlaybackLockState
import com.huanhren.deltaharmonica.input.RootRecoveryResult
import com.huanhren.deltaharmonica.input.RootSessionPhase
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import kotlin.math.roundToInt

@Composable
internal fun PlaybackPreferences(state: AppUiState, model: AppViewModel) {
    val preferences = model.preferences
    val speed = preferences?.speed?.collectAsStateWithLifecycle()?.value ?: 1.0
    val range = preferences?.range?.collectAsStateWithLifecycle()?.value ?: PlaybackRange.FIRST_8
    val volumeStop = preferences?.volumeStop?.collectAsStateWithLifecycle()?.value ?: true
    val volumePause = preferences?.volumePause?.collectAsStateWithLifecycle()?.value ?: false
    val idle = settingsPlaybackIdle()
    PreferenceGroup(R.string.performance_settings) {
        StudioDropdown(PlaybackRange.entries, range, { stringResource(it.rangeLabel()) }, R.string.studio_default_range,
            "studio/range", idle, model::setDefaultRange)
        StudioDropdown(listOf(1.0, 2.0), speed, { "${it.toInt()}×" }, R.string.default_speed, "studio/speed",
            idle, model::setDefaultSpeed)
    }
    PreferenceGroup(R.string.backend) {
        val choices = settingsBackends(state, model.rootUiAllowed)
        val selected = choices.indexOfFirst { it.first == state.preferredBackend }
        if (selected >= 0) {
            OverlayDropdownPreference(choices.map { (name, _) -> backendLabel(name) }, selected, stringResource(R.string.backend),
                onSelectedIndexChange = { index -> model.selectBackend(choices[index].first) }, enabled = idle)
            state.selectedBackendStatus?.let { status ->
                SupportingText(backendSummary(state.selectedBackend, status), Modifier.padding(horizontal = Spacing.card))
            }
        } else {
            SupportingText(stringResource(R.string.settings_no_backend), Modifier.padding(Spacing.card))
        }
    }
    PreferenceGroup(R.string.settings_volume_shortcuts) {
        SwitchPreference(volumeStop, { preferences?.setVolumeStop(it) }, stringResource(R.string.volume_stop),
            summary = stringResource(R.string.volume_stop_summary), enabled = idle)
        SwitchPreference(volumePause, { preferences?.setVolumePause(it) }, stringResource(R.string.volume_pause),
            summary = stringResource(R.string.volume_pause_summary), enabled = idle, modifier = Modifier.testTag("studio/volume-pause"))
    }
}

@Composable
internal fun OverlayPreferences(rootAllowed: Boolean, overlayGranted: Boolean, onControls: () -> Unit, onPermission: () -> Unit) {
    PreferenceGroup {
        if (rootAllowed) {
            ArrowPreference(stringResource(R.string.player_open_game_controls), onClick = onControls)
            ArrowPreference(stringResource(R.string.manage_overlay),
                summary = stringResource(if (overlayGranted) R.string.overlay_granted else R.string.overlay_missing),
                onClick = onPermission)
        } else {
            SupportingText(overlayStatus(rootAllowed, overlayGranted), Modifier.padding(Spacing.card))
        }
    }
}

@Composable
internal fun CalibrationPreferences(
    state: AppUiState,
    model: AppViewModel,
    onCalibrate: () -> Unit,
    onCalibrationOverlay: () -> Unit,
    onTest: () -> Unit,
) {
    val idle = settingsPlaybackIdle()
    PreferenceGroup {
        ArrowPreference(stringResource(R.string.calibrate), summary = state.loadError ?: settingsCalibrationStatus(state),
            enabled = idle && !state.loading && !state.saving && state.loadError == null, onClick = onCalibrate)
        if (state.loadError != null) ArrowPreference(stringResource(R.string.retry_read), onClick = model::load)
        if (model.rootUiAllowed) ArrowPreference(stringResource(R.string.calibration_overlay), enabled = idle, onClick = onCalibrationOverlay)
        ArrowPreference(stringResource(R.string.calibration_test), enabled = idle && state.saved != null, onClick = onTest)
    }
}

@Composable
internal fun LibraryPreferences(model: AppViewModel, onLibrary: () -> Unit, onImport: () -> Unit) {
    PreferenceGroup {
        ArrowPreference(stringResource(R.string.open_library), summary = settingsLibraryStatus(model), onClick = onLibrary)
        ArrowPreference(stringResource(R.string.library_import_track), modifier = Modifier.testTag("studio/settings/import"),
            summary = stringResource(R.string.import_music), onClick = onImport)
    }
}

@Composable
internal fun SystemPreferences(state: AppUiState, model: AppViewModel, onAppearance: () -> Unit) {
    val context = LocalContext.current
    val appearance = LocalAppearance.current
    val idle = settingsPlaybackIdle()
    var accessibilityError by rememberSaveable { mutableStateOf(false) }
    PreferenceGroup(R.string.permissions) {
        ArrowPreference(stringResource(R.string.accessibility_settings), summary = accessibilityStatus(state), onClick = {
            accessibilityError = try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                false
            } catch (_: ActivityNotFoundException) {
                true
            }
        })
        if (accessibilityError) SupportingText(stringResource(R.string.accessibility_settings_error), Modifier.padding(horizontal = Spacing.card), error = true)
        if (state.accessibilityGrantMessage.isNotEmpty()) SupportingText(state.accessibilityGrantMessage, Modifier.padding(horizontal = Spacing.card))
    }
    if (model.rootUiAllowed) RootAuthorizationPreferences(state, model, idle)
    PreferenceGroup {
        ArrowPreference(stringResource(R.string.appearance), summary = stringResource(appearance.theme.labelRes()),
            modifier = Modifier.testTag("studio/settings/appearance"), onClick = onAppearance)
    }
    if (model.rootUiAllowed && state.backends.any { it.first == "Root" && it.second.available }) {
        ExpandablePreferences(R.string.settings_advanced) {
            ArrowPreference(stringResource(if (state.enablingAccessibility) R.string.enabling_accessibility_root else R.string.enable_accessibility_root),
                enabled = idle && !state.enablingAccessibility, onClick = model::enableAccessibilityWithRoot)
        }
    }
}

@Composable
private fun RootAuthorizationPreferences(state: AppUiState, model: AppViewModel, idle: Boolean) {
    val context = LocalContext.current
    val root by model.rootAuthorization.collectAsStateWithLifecycle()
    val session by model.rootSession.collectAsStateWithLifecycle()
    val readyBackend = state.selectedBackend.takeIf { state.selectedBackendStatus?.available == true }
    val canChange = idle && !state.checkingRoot
    PreferenceGroup(R.string.root_preferences) {
        BasicComponent(
            title = stringResource(R.string.root_environment),
            summary = stringResource(when (root.environment.suVisible) {
                true -> R.string.root_environment_visible
                false -> R.string.root_environment_not_found
                null -> R.string.root_state_unknown
            }),
        )
        BasicComponent(
            title = stringResource(R.string.root_manager),
            summary = root.environment.manager?.kind?.label ?: stringResource(R.string.root_manager_unknown),
        )
        BasicComponent(
            title = stringResource(R.string.root_authorization),
            summary = stringResource(if (root.checking) R.string.root_checking else when (root.authorization) {
                RootAuthorizationState.UNKNOWN -> R.string.root_state_unknown
                RootAuthorizationState.NOT_ROOTED -> R.string.root_environment_not_found
                RootAuthorizationState.NOT_AUTHORIZED -> R.string.root_state_not_authorized
                RootAuthorizationState.PREVIOUSLY_AUTHORIZED -> R.string.root_state_cached
                RootAuthorizationState.AUTHORIZED -> R.string.root_state_authorized
                RootAuthorizationState.DENIED -> R.string.root_state_denied
                RootAuthorizationState.REVOKED -> R.string.root_state_revoked
                RootAuthorizationState.MANAGER_UNAVAILABLE -> R.string.root_state_manager_unavailable
                RootAuthorizationState.RECHECK_REQUIRED -> R.string.root_state_recheck
                RootAuthorizationState.ERROR -> R.string.root_state_error
            }),
        )
        BasicComponent(
            title = stringResource(R.string.root_input_readiness),
            summary = stringResource(when (root.input) {
                RootInputReadiness.UNKNOWN -> R.string.root_input_pending
                RootInputReadiness.READY -> R.string.root_input_ready
                RootInputReadiness.UNAVAILABLE -> R.string.root_input_unavailable
            }),
        )
        BasicComponent(
            title = stringResource(R.string.root_session_status),
            summary = stringResource(when (session.phase) {
                RootSessionPhase.IDLE -> R.string.root_session_idle
                RootSessionPhase.PREPARING -> R.string.root_session_preparing
                RootSessionPhase.ACTIVE -> R.string.root_session_active
                RootSessionPhase.CLEANING -> R.string.root_session_cleaning
                RootSessionPhase.RECOVERY_REQUIRED -> R.string.root_session_recovery_required
                RootSessionPhase.RECOVERING -> R.string.root_session_recovering
            }),
        )
        val none = stringResource(R.string.root_session_none)
        val unconfirmed = stringResource(R.string.root_session_unconfirmed)
        BasicComponent(title = stringResource(R.string.root_session_id), summary = session.id ?: none)
        val helperStatus = stringResource(when (session.phase) {
            RootSessionPhase.IDLE -> R.string.root_helper_inactive
            RootSessionPhase.PREPARING -> R.string.root_helper_starting
            RootSessionPhase.ACTIVE -> R.string.root_helper_connected
            RootSessionPhase.CLEANING -> R.string.root_helper_closing
            RootSessionPhase.RECOVERING -> R.string.root_helper_verifying
            RootSessionPhase.RECOVERY_REQUIRED -> if (session.recoveryResult == RootRecoveryResult.HELPER_ALIVE) {
                R.string.root_helper_observed_alive
            } else {
                R.string.root_session_unconfirmed
            }
        })
        BasicComponent(title = stringResource(R.string.root_session_helper), summary = session.helper?.let {
            stringResource(R.string.root_helper_status_pid, helperStatus, it.pid)
        } ?: helperStatus)
        val dispatcherStatus = session.dispatcherStatus ?: none
        BasicComponent(title = stringResource(R.string.root_session_dispatcher), summary = session.dispatcherId?.let {
            stringResource(R.string.root_dispatcher_status_id, dispatcherStatus, it)
        } ?: dispatcherStatus)
        BasicComponent(title = stringResource(R.string.root_session_lock), summary = stringResource(when (session.lockState) {
            RootPlaybackLockState.LOCK_FREE -> R.string.root_lock_free
            RootPlaybackLockState.LOCK_HELD -> R.string.root_lock_held
            RootPlaybackLockState.LOCK_STALE -> R.string.root_lock_stale
            RootPlaybackLockState.LOCK_RECOVERY_REQUIRED -> R.string.root_session_recovery_required
            RootPlaybackLockState.LOCK_RELEASE_UNCONFIRMED -> R.string.root_lock_release_unconfirmed
        }))
        BasicComponent(title = stringResource(R.string.root_session_touches),
            summary = session.activeTouchCount?.toString() ?: unconfirmed)
        BasicComponent(title = stringResource(R.string.root_session_cleanup), summary = stringResource(when (session.cleanup) {
            RootCleanup.ACKNOWLEDGED -> R.string.root_cleanup_acknowledged
            RootCleanup.OBSERVED_IDLE -> R.string.root_cleanup_observed_idle
            RootCleanup.REJECTED -> R.string.root_cleanup_rejected
            RootCleanup.NO_ACK -> R.string.root_cleanup_no_ack
            RootCleanup.JOURNAL_FAILURE -> R.string.root_cleanup_journal_failure
            null -> R.string.root_session_none
        }))
        session.terminationReason?.let { BasicComponent(title = stringResource(R.string.root_session_termination), summary = it) }
        session.lastFailureReason?.let { BasicComponent(title = stringResource(R.string.root_session_last_failure), summary = it) }
        BasicComponent(title = stringResource(R.string.root_session_recovery_result), summary = session.recoveryResult?.name ?: none)
        session.detail?.let { BasicComponent(title = stringResource(R.string.root_session_detail), summary = it) }
        if (session.recoveryAttempts > 0) BasicComponent(
            title = stringResource(R.string.root_session_recovery_attempts),
            summary = session.recoveryAttempts.toString(),
        )
        BasicComponent(title = stringResource(R.string.root_current_backend), summary =
            if (readyBackend != null) backendLabel(readyBackend) else stringResource(R.string.root_no_usable_backend))
        ArrowPreference(
            title = stringResource(R.string.root_detect_environment),
            enabled = canChange,
            onClick = model::detectRootEnvironment,
        )
        ArrowPreference(
            title = stringResource(R.string.root_request_authorization),
            enabled = canChange,
            onClick = model::checkRoot,
        )
        ArrowPreference(
            title = stringResource(R.string.root_recover_input),
            enabled = canChange && (session.phase == RootSessionPhase.IDLE || session.phase == RootSessionPhase.RECOVERY_REQUIRED),
            onClick = model::recoverRootInput,
        )
        val copyDiagnosticsLabel = stringResource(R.string.root_copy_diagnostics)
        ArrowPreference(
            title = copyDiagnosticsLabel,
            enabled = session.id != null || session.recoveryRequired,
            onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(copyDiagnosticsLabel, model.rootDiagnosticReport()),
                )
            },
        )
        OverlayDropdownPreference(
            listOf(backendLabel("Root"), backendLabel("Accessibility")),
            if (state.preferredBackend == "Root") 0 else 1,
            stringResource(R.string.root_preferred_backend),
            onSelectedIndexChange = { model.selectBackend(if (it == 0) "Root" else "Accessibility") },
            enabled = canChange,
        )
    }
}

@Composable
internal fun AboutPreferences(state: AppUiState, model: AppViewModel) {
    val context = LocalContext.current
    val idle = settingsPlaybackIdle()
    var linkError by rememberSaveable { mutableStateOf(false) }
    fun openProjectLink(url: String) {
        linkError = try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            false
        } catch (_: ActivityNotFoundException) {
            true
        }
    }
    PreferenceGroup(R.string.about_studio) {
        BasicComponent(title = versionSummary())
        ArrowPreference(stringResource(R.string.studio_project_source), onClick = {
            openProjectLink("https://github.com/HuanHren/DeltaHarmonica")
        })
        ArrowPreference(stringResource(R.string.studio_miuix_license), summary = "Apache 2.0", onClick = {
            openProjectLink("https://github.com/compose-miuix-ui/miuix/blob/main/LICENSE")
        })
        if (linkError) SupportingText(stringResource(R.string.studio_no_browser), Modifier.padding(horizontal = Spacing.card), error = true)
    }
    ExpandablePreferences(R.string.settings_diagnostics, selectedBackendLabel(state, model.rootUiAllowed), "studio/settings/diagnostics") {
        settingsBackends(state, model.rootUiAllowed).forEach { (name, status) ->
            BasicComponent(title = backendLabel(name), summary = backendSummary(name, status))
        }
        FlowRow(Modifier.padding(horizontal = Spacing.small)) {
            if (model.rootUiAllowed) TextAction(model::checkRoot, enabled = idle && !state.checkingRoot) {
                Text(stringResource(if (state.checkingRoot) R.string.root_checking else R.string.root_check))
            }
            TextAction(model::refreshBackends) { Text(stringResource(R.string.refresh_state)) }
        }
    }
}

@Composable
internal fun AppearancePreferences(preferences: PlayerPreferences?) {
    val context = LocalContext.current
    val appearance = LocalAppearance.current
    var launcherError by rememberSaveable { mutableStateOf(false) }
    fun update(change: StudioAppearance.() -> StudioAppearance) { preferences?.updateAppearance(change) }
    PreferenceGroup(R.string.settings_appearance_description) {
        StudioDropdown(ThemeMode.entries, appearance.theme, { stringResource(it.labelRes()) }, R.string.theme_mode, "studio/theme") {
            preferences?.setTheme(it)
        }
        StudioDropdown(StudioLanguage.entries, appearance.language, { stringResource(it.labelRes()) }, R.string.language, "studio/language") { value ->
            update { copy(language = value) }
            context.setStudioLocale(value)
            if (Build.VERSION.SDK_INT < 33) (context as? Activity)?.recreate()
        }
    }
    PreferenceGroup(R.string.studio_workspace_look) {
        StudioDropdown(HomeLayout.entries, appearance.layout, { stringResource(it.labelRes()) }, R.string.home_layout, "studio/home-layout") {
            update { copy(layout = it) }
        }
        StudioDropdown(LauncherStyle.entries, appearance.launcher, { stringResource(it.labelRes()) }, R.string.launcher_icon, "studio/launcher") { value ->
            if (value != appearance.launcher) {
                if (runCatching { context.setLauncherStyle(value) }.isSuccess) update { copy(launcher = value) }
                else launcherError = true
            }
        }
        if (launcherError) SupportingText(stringResource(R.string.icon_update_error), error = true)
    }
    PreferenceGroup(R.string.dynamic_color) {
        SeedPicker(appearance.seed) { seed -> update { copy(seed = seed) } }
    }
    PreferenceGroup(R.string.visual_effects) {
        SwitchPreference(appearance.blur, { update { copy(blur = it) } }, stringResource(R.string.blur_live),
            modifier = Modifier.testTag("studio/blur"), summary = stringResource(R.string.blur_scope))
        SwitchPreference(appearance.glass, { update { copy(glass = it) } }, stringResource(R.string.glass_effect),
            modifier = Modifier.testTag("studio/glass"), summary = stringResource(R.string.glass_scope))
        SwitchPreference(appearance.reducedEffects, { update { copy(reducedEffects = it) } }, stringResource(R.string.reduced_effects),
            modifier = Modifier.testTag("studio/reduce-effects"))
        if (Build.VERSION.SDK_INT < 33) SupportingText(stringResource(R.string.visual_fallback), Modifier.padding(horizontal = Spacing.card))
    }
    PreferenceGroup(R.string.navigation_layout) {
        SwitchPreference(appearance.showLabels, { update { copy(showLabels = it) } }, stringResource(R.string.show_labels),
            modifier = Modifier.testTag("studio/labels"))
        SwitchPreference(appearance.hideOnScroll, { update { copy(hideOnScroll = it) } }, stringResource(R.string.hide_nav_scroll),
            modifier = Modifier.testTag("studio/hide-scroll"))
        StudioTab.entries.forEach { tab ->
            val isVisible = tab in appearance.tabs
            SwitchPreference(isVisible, { update { toggle(tab) } }, stringResource(tab.labelRes()),
                modifier = Modifier.testTag("studio/nav/${tab.name}"), enabled = !isVisible || appearance.tabs.size > 1)
        }
        SupportingText(stringResource(R.string.nav_visible_hint), Modifier.padding(horizontal = Spacing.card))
        SwitchPreference(appearance.predictiveBack, { update { copy(predictiveBack = it) } },
            stringResource(R.string.predictive_back), summary = stringResource(R.string.predictive_detail),
            modifier = Modifier.testTag("studio/predictive-back"), enabled = Build.VERSION.SDK_INT >= 34)
    }
    PreferenceGroup(R.string.interface_scale) {
        Column(Modifier.padding(horizontal = Spacing.card), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(stringResource(R.string.scale_label, (appearance.scale * 100).roundToInt()), style = StudioTypography.titleMedium)
            SupportingText(stringResource(R.string.scale_detail))
            Slider(appearance.scale, { value -> update { resize(value) } }, Modifier.fillMaxWidth()
                .heightIn(min = Spacing.touchTarget).testTag("studio/scale"), valueRange = .8f..1.1f)
            TextAction({ update { resize(1f) } }) { Text(stringResource(R.string.reset_scale)) }
        }
    }
}

@Composable
private fun ExpandablePreferences(
    @StringRes title: Int,
    summary: String? = null,
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    val animate = !LocalAppearance.current.reducedEffects
    PreferenceGroup {
        BasicComponent(
            title = stringResource(title),
            summary = summary,
            onClick = { expanded = !expanded },
            modifier = (if (tag == null) Modifier else Modifier.testTag(tag))
                .heightIn(min = Spacing.touchTarget).semantics { stateDescription = state },
            endActions = {
                ProductGlyph(ProductIcon.Down, StudioTheme.colorScheme.onSurfaceSecondary,
                    Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
            },
        )
        AnimatedVisibility(
            expanded,
            enter = if (animate) expandVertically() + fadeIn() else EnterTransition.None,
            exit = if (animate) shrinkVertically() + fadeOut() else ExitTransition.None,
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun settingsPlaybackIdle(): Boolean {
    val progress by rememberControlsProgress()
    return playbackControls(progress, PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null).canStart
}

private fun settingsBackends(state: AppUiState, rootAllowed: Boolean): List<Pair<String, BackendStatus>> =
    state.backends.filter { (name, status) ->
        status.availability != BackendAvailability.NOT_IMPLEMENTED && (name != "Root" || rootAllowed)
    }

@Composable
internal fun selectedBackendLabel(state: AppUiState, rootAllowed: Boolean): String {
    val backend = settingsBackends(state, rootAllowed).firstOrNull { it.first == state.selectedBackend }?.first
    return if (backend == null) stringResource(R.string.settings_no_backend) else backendLabel(backend)
}

@Composable
private fun backendLabel(name: String): String =
    if (name == "Accessibility") stringResource(R.string.settings_backend_accessibility) else name

@Composable
internal fun backendSummary(name: String, status: BackendStatus): String {
    val locales = androidx.compose.ui.platform.LocalConfiguration.current.locales
    if (locales[0]?.language == "zh") return status.message
    return stringResource(when {
        status.available && name == "Root" -> R.string.engine_root_enabled
        status.available && name == "Accessibility" -> R.string.engine_accessibility_ready
        status.available -> R.string.engine_ready
        status.availability == BackendAvailability.NOT_IMPLEMENTED -> R.string.engine_not_implemented
        status.availability == BackendAvailability.NOT_ENABLED -> R.string.engine_disabled
        else -> R.string.engine_checking
    })
}

@Composable
private fun <T> StudioDropdown(items: List<T>, value: T, label: @Composable (T) -> String, title: Int,
                               tag: String, enabled: Boolean = true, onChange: (T) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val heading = stringResource(title)
    ArrowPreference(title = heading, summary = label(value), modifier = Modifier.testTag(tag).heightIn(min = Spacing.touchTarget),
        enabled = enabled, onClick = { picking = true })
    if (picking) ProductSheet(heading, { picking = false }) {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
            items.forEachIndexed { index, item ->
                ArrowPreference(title = label(item), modifier = Modifier.testTag("$tag/option/$index").semantics {
                    this.selected = item == value
                }.heightIn(min = Spacing.touchTarget), onClick = {
                    picking = false
                    onChange(item)
                })
            }
        }
    }
}
@Composable
internal fun PreferenceGroup(@StringRes title: Int? = null, body: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        if (title != null) Text(stringResource(title), Modifier.padding(horizontal = Spacing.item),
            style = StudioTypography.labelLarge, color = StudioTheme.colorScheme.onSurfaceSecondary)
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 4.dp), content = body)
    }
}

@Composable
private fun SeedPicker(seed: StudioSeed?, onSelect: (StudioSeed?) -> Unit) {
    val colors = listOf(null) + StudioSeed.entries
    val automatic = stringResource(R.string.automatic_color)
    Column(Modifier.padding(horizontal = Spacing.card), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        SupportingText(stringResource(R.string.current_color, seed?.title ?: automatic))
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            colors.forEach { value ->
                val active = value == seed
                val label = value?.title ?: automatic
                val palette = StudioTheme.colorScheme
                Box(Modifier.size(Spacing.touchTarget).testTag("studio/seed/${value?.name ?: "AUTO"}")
                    .clip(ShapeTokens.pill).selectable(active, role = Role.RadioButton, onClick = { onSelect(value) })
                    .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(31.dp).background(value?.let { Color(it.hex) } ?: palette.primaryContainer, ShapeTokens.pill)
                        .border(if (active) 3.dp else 1.dp, if (active) palette.onSurface else palette.surface, ShapeTokens.pill))
                }
            }
        }
    }
}
