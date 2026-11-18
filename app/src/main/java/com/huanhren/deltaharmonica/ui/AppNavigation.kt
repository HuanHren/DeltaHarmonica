package com.huanhren.deltaharmonica.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.huanhren.deltaharmonica.overlay.CalibrationOverlayService
import com.huanhren.deltaharmonica.ui.theme.ShapeTokens
import com.huanhren.deltaharmonica.ui.theme.Spacing
import top.yukonga.miuix.kmp.basic.Text

private enum class Destination(val route: String, val label: String, val icon: ProductIcon) {
    HOME("home", "首页", ProductIcon.Home),
    LIBRARY("library", "曲库", ProductIcon.Library),
    PLAYER("player", "演奏", ProductIcon.Play),
    SETTINGS("settings", "设置", ProductIcon.Settings),
}

internal fun Activity.openOverlayPermission() {
    try {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "请在系统设置中开启 DeltaHarmonica 的悬浮窗权限", Toast.LENGTH_LONG).show()
    }
}

private fun Activity.openOverlay(action: String?) {
    if (!Settings.canDrawOverlays(this)) openOverlayPermission()
    else startService(Intent(this, CalibrationOverlayService::class.java).setAction(action))
}

@Composable
fun AppNavigation(model: AppViewModel, activity: Activity) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(activity)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(activity)
                model.refreshBackends()
                model.load()
            }
            if (event == Lifecycle.Event.ON_PAUSE) model.cancelTest()
        }
        model.refreshBackends()
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.cancelTest() }
    }
    fun tab(next: Destination) {
        nav.navigate(next.route) {
            popUpTo(Destination.HOME.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun calibrate() {
        model.beginCalibration()
        nav.navigate("calibration") { launchSingleTop = true }
    }
    val primary = Destination.entries.firstOrNull { it.route == route }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val rail = maxWidth >= 680.dp && primary != null
        Column(Modifier.fillMaxSize().then(if (route == "calibration" || route == "test") Modifier else Modifier.safeDrawingPadding())) {
            Row(Modifier.weight(1f)) {
                if (rail) ProductNavigation(primary, true, ::tab)
                NavHost(navController = nav, startDestination = Destination.HOME.route,
                    modifier = Modifier.weight(1f),
                    enterTransition = { fadeIn(tween(160)) }, exitTransition = { fadeOut(tween(110)) }) {
                    composable(Destination.HOME.route) {
                        HomeScreen(state, model, onCalibrate = ::calibrate,
                            onLibrary = { tab(Destination.LIBRARY) }, onPlayer = { tab(Destination.PLAYER) },
                            onImport = { nav.navigate("import") }, onOverlay = { activity.openOverlay(CalibrationOverlayService.ACTION_SHOW_PLAYER) })
                    }
                    composable(Destination.LIBRARY.route) {
                        LibraryScreen(model, onImport = { nav.navigate("import") }, onPlayer = { tab(Destination.PLAYER) })
                    }
                    composable(Destination.PLAYER.route) {
                        PlayerScreen(activity, state, model, onLibrary = { tab(Destination.LIBRARY) },
                            onCalibrate = ::calibrate, onOverlay = { activity.openOverlay(CalibrationOverlayService.ACTION_SHOW_PLAYER) })
                    }
                    composable("import") {
                        ImportScreen(activity, state.saved, model, onBack = { nav.popBackStack() },
                            onPlayer = { tab(Destination.PLAYER) }, onLibrary = { tab(Destination.LIBRARY) })
                    }
                    composable("calibration") {
                        CalibrationScreen(state, activity, model::select, model::move, model::reset, model::save,
                            onBack = { nav.popBackStack() })
                    }
                    composable(Destination.SETTINGS.route) {
                        SettingsScreen(state, model, overlayGranted, onOverlayPermission = activity::openOverlayPermission,
                            onCalibrate = ::calibrate, onCalibrationOverlay = { activity.openOverlay(CalibrationOverlayService.ACTION_SHOW_CALIBRATION) },
                            onTest = { nav.navigate("test") })
                    }
                    composable("test") {
                        TestScreen(state, activity, model::test, model::recordHit, model::cancelTest) { nav.popBackStack() }
                    }
                }
            }
            if (primary != null && !rail) ProductNavigation(primary, false, ::tab)
        }
    }
}

@Composable
private fun ProductNavigation(selected: Destination?, rail: Boolean, onSelect: (Destination) -> Unit) {
    val palette = MaterialTheme.colorScheme
    val base = Modifier.background(palette.surface).selectableGroup()
    if (rail) {
        Column(base.fillMaxHeight().width(92.dp).padding(Spacing.small),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Destination.entries.forEach { NavigationItem(it, it == selected, Modifier.fillMaxWidth(), onSelect) }
        }
    } else {
        Row(base.fillMaxWidth().padding(horizontal = Spacing.small, vertical = 4.dp)) {
            Destination.entries.forEach { NavigationItem(it, it == selected, Modifier.weight(1f), onSelect) }
        }
    }
}

@Composable
private fun NavigationItem(item: Destination, selected: Boolean, modifier: Modifier, onSelect: (Destination) -> Unit) {
    val palette = MaterialTheme.colorScheme
    val tint = if (selected) palette.onPrimaryContainer else palette.onSurfaceVariant
    Column(modifier.testTag("nav/${item.route}").clip(ShapeTokens.card)
        .background(if (selected) palette.primaryContainer else palette.surface)
        .selectable(selected, role = Role.Tab, onClick = { onSelect(item) })
        .heightIn(min = 54.dp).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ProductGlyph(item.icon, tint)
        Text(item.label, color = tint, style = MaterialTheme.typography.bodySmall)
    }
}
