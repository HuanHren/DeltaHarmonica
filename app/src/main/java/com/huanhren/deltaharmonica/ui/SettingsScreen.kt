package com.huanhren.deltaharmonica.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import kotlin.math.roundToInt

internal enum class SettingsPage(
    val path: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val icon: ProductIcon,
) {
    PLAYBACK("playback", R.string.settings_playback, R.string.settings_playback_description, ProductIcon.Wave),
    OVERLAY("overlay", R.string.settings_overlay, R.string.settings_overlay_description, ProductIcon.Overlay),
    CALIBRATION("calibration", R.string.settings_calibration, R.string.settings_calibration_description, ProductIcon.Calibration),
    LIBRARY("library", R.string.settings_library, R.string.settings_library_description, ProductIcon.Library),
    SYSTEM("system", R.string.settings_system, R.string.settings_system_description, ProductIcon.Shield),
    ABOUT("about", R.string.settings_about, R.string.settings_about_description, ProductIcon.Info),
    APPEARANCE("appearance", R.string.appearance, R.string.settings_appearance_description, ProductIcon.Settings);

    val route: String get() = "settings/$path"

    companion object {
        val categories = entries.filter { it != APPEARANCE }
    }
}

@Composable
internal fun SettingsScreen(
    state: AppUiState,
    model: AppViewModel,
    overlayGranted: Boolean,
    onMenu: () -> Unit,
    onCategory: (SettingsPage) -> Unit,
) {
    val speed = model.preferences?.speed?.collectAsStateWithLifecycle()?.value ?: 1.0
    Page(stringResource(R.string.nav_settings), modifier = Modifier.testTag("studio/settings-scroll"), actions = {
        MusicIconButton(ProductIcon.More, stringResource(R.string.studio_routes), onMenu, Modifier.testTag("studio/menu"))
    }) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 4.dp)) {
            SettingsPage.categories.forEach { category ->
                val current = when (category) {
                    SettingsPage.PLAYBACK -> stringResource(R.string.settings_playback_summary,
                        selectedBackendLabel(state, model.rootUiAllowed), speed.roundToInt())
                    SettingsPage.OVERLAY -> overlayStatus(model.rootUiAllowed, overlayGranted)
                    SettingsPage.CALIBRATION -> settingsCalibrationStatus(state)
                    SettingsPage.LIBRARY -> settingsLibraryStatus(model)
                    SettingsPage.SYSTEM -> accessibilityStatus(state)
                    SettingsPage.ABOUT -> versionSummary()
                    SettingsPage.APPEARANCE -> stringResource(LocalAppearance.current.theme.labelRes())
                }
                val colors = StudioTheme.colorScheme
                ArrowPreference(
                    title = stringResource(category.title),
                    summary = "${stringResource(category.description)}\n$current",
                    modifier = Modifier.testTag("studio/settings/category/${category.path}").heightIn(min = Spacing.touchTarget),
                    startAction = {
                        Box(Modifier.size(40.dp).background(colors.primaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            ProductGlyph(category.icon, colors.onPrimaryContainer, Modifier.size(20.dp))
                        }
                    },
                    insideMargin = PaddingValues(horizontal = Spacing.card, vertical = 10.dp),
                    onClick = { onCategory(category) },
                )
            }
        }
    }
}

@Composable
internal fun SettingsDetailPage(page: SettingsPage, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize().background(StudioTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Spacing.contentWidth).fillMaxSize().testTag("studio/settings/page/${page.path}")) {
            SmallTopAppBar(
                title = stringResource(page.title),
                color = StudioTheme.colorScheme.background,
                defaultWindowInsetsPadding = false,
                navigationIcon = {
                    val label = stringResource(R.string.action_back)
                    IconButton(onClick = onBack, modifier = Modifier.size(Spacing.touchTarget)
                        .testTag("studio/settings/back").semantics { contentDescription = label }) {
                        ProductGlyph(ProductIcon.Back, StudioTheme.colorScheme.onSurface, Modifier.size(22.dp)
                            .scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f))
                    }
                },
            )
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page).padding(top = Spacing.small, bottom = Spacing.section),
                verticalArrangement = Arrangement.spacedBy(Spacing.card), content = content)
        }
    }
}

@Composable
internal fun overlayStatus(rootAllowed: Boolean, overlayGranted: Boolean) = stringResource(when {
    !rootAllowed -> R.string.settings_overlay_unavailable
    overlayGranted -> R.string.settings_overlay_allowed
    else -> R.string.settings_overlay_blocked
})

@Composable
internal fun settingsCalibrationStatus(state: AppUiState) = when {
    state.loadError != null -> stringResource(R.string.settings_calibration_error)
    state.loading -> stringResource(R.string.layout_loading)
    state.saved != null -> stringResource(R.string.layout_ready, state.saved.targets.size)
    else -> stringResource(R.string.layout_missing)
}

@Composable
internal fun settingsLibraryStatus(model: AppViewModel): String {
    val count = model.songs.items.collectAsStateWithLifecycle().value.size
    val loading = model.libraryLoading.collectAsStateWithLifecycle().value
    val error = model.libraryError.collectAsStateWithLifecycle().value
    return when {
        error != null -> stringResource(R.string.studio_error_library)
        loading -> stringResource(R.string.studio_library_wait)
        else -> pluralStringResource(R.plurals.settings_library_count, count, count)
    }
}

@Composable
internal fun accessibilityStatus(state: AppUiState) = stringResource(
    if (state.backends.any { (name, status) -> name == "Accessibility" && status.available })
        R.string.settings_accessibility_connected else R.string.settings_accessibility_disconnected
)

@Composable
internal fun versionSummary(): String {
    val context = LocalContext.current
    val version = androidx.compose.runtime.remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    return stringResource(R.string.version_info, version)
}
