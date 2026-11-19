package com.huanhren.deltaharmonica.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.overlay.CalibrationOverlayService
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.math.abs

internal fun Activity.openOverlayPermission() {
    try {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.permission_overlay_hint), Toast.LENGTH_LONG).show()
    }
}

private fun Activity.openOverlay(action: String) {
    if (!Settings.canDrawOverlays(this)) openOverlayPermission()
    else startService(Intent(this, CalibrationOverlayService::class.java).setAction(action))
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation(model: AppViewModel, activity: Activity) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: StudioTab.LIBRARY.route
    val state by model.state.collectAsStateWithLifecycle()
    val appearance = LocalAppearance.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(activity)) }
    var quickMenu by rememberSaveable { mutableStateOf(false) }
    var coverOrigin by rememberSaveable { mutableStateOf("list") }
    var startRequested by rememberSaveable { mutableStateOf(false) }
    var scrollGeneration by remember(route) { mutableIntStateOf(0) }
    var capsuleVisible by remember(route) { mutableStateOf(true) }
    val capsuleTransition = remember(route) { MutableTransitionState(true) }
    capsuleTransition.targetState = capsuleVisible
    val effects = effectiveSurfaceEffects()
    var materialReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Let the first solid frame reach the screen before starting backdrop capture.
        repeat(2) { withFrameNanos {} }
        materialReady = true
    }
    val primary = StudioTab.entries.firstOrNull { it.route == route }
    val immersive = route == "calibration" || route == "test"
    val scroll = remember(appearance.hideOnScroll, route) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (appearance.hideOnScroll && abs(consumed.y) > 1f &&
                    (source == NestedScrollSource.UserInput || !capsuleVisible)) {
                    capsuleVisible = false
                    scrollGeneration++
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(scrollGeneration, appearance.hideOnScroll) {
        if (!appearance.hideOnScroll || scrollGeneration == 0) capsuleVisible = true
        else {
            delay(130)
            capsuleVisible = true
        }
    }
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
    fun tab(next: StudioTab) {
        if (nav.currentDestination?.route == next.route) return
        if (next == StudioTab.LIBRARY && nav.popBackStack(StudioTab.LIBRARY.route, inclusive = false, saveState = true)) return
        nav.navigate(next.route) {
            popUpTo(StudioTab.LIBRARY.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun openPlayer(origin: String, start: Boolean = false) {
        if (nav.currentDestination?.route == StudioTab.PLAYER.route) return
        coverOrigin = origin
        startRequested = start
        nav.navigate(StudioTab.PLAYER.route) { launchSingleTop = true }
    }
    fun collapsePlayer() {
        if (!nav.popBackStack()) tab(StudioTab.LIBRARY)
    }
    fun calibrate() {
        model.beginCalibration()
        nav.navigate("calibration") { launchSingleTop = true }
    }
    val landscapePlayer = route == StudioTab.PLAYER.route &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showNavigation = primary != null && !landscapePlayer
    val animate = !appearance.reducedEffects
    val settingsDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val capsuleSpace = Spacing.touchTarget + if (appearance.showLabels) 34.dp else 22.dp
    val bottomSpace by animateDpAsState(
        targetValue = if (showNavigation && capsuleVisible) capsuleSpace else 0.dp,
        animationSpec = if (animate) tween(if (capsuleVisible) MusicMotion.feedback else 96) else snap(),
        label = "navigation inset",
    )
    val gutters = PaddingValues(bottom = if (showNavigation) bottomSpace else 0.dp)
    val canvas = StudioTheme.colorScheme.background
    val captureNavigation = materialReady && showNavigation &&
        (capsuleTransition.currentState || capsuleTransition.targetState)
    val backdrop = if (captureNavigation && (effects.blur || effects.glass)) {
        rememberLayerBackdrop {
            // Content leaves an empty gutter while the capsule is visible.
            drawRect(canvas)
            drawContent()
        }
    } else null
    val workspace = model.midiWorkspace
    val surfaceTint = if (route == StudioTab.PLAYER.route && workspace.report != null)
        lerp(canvas, coverAmbient(workspace.songId ?: workspace.name), if (canvas.luminance() < .2f) .28f else .13f) else canvas
    val ambient by animateColorAsState(surfaceTint, tween(if (animate) MusicMotion.page else 0), label = "player background")
    CompositionLocalProvider(LocalStudioGutters provides gutters) {
        Scaffold(containerColor = StudioTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ambient, canvas, canvas)))
                .then(if (immersive) Modifier else Modifier.safeDrawingPadding())) {
                Box(Modifier.fillMaxSize().nestedScroll(scroll).then(if (backdrop == null) Modifier else Modifier.layerBackdrop(backdrop))) {
                    SharedTransitionLayout(Modifier.fillMaxSize()) {
                        NavHost(navController = nav, startDestination = StudioTab.LIBRARY.route, modifier = Modifier.fillMaxSize(),
                            enterTransition = { if (animate) fadeIn(tween(MusicMotion.page)) + slideInVertically(tween(MusicMotion.page)) { it / 40 } else EnterTransition.None },
                            exitTransition = { if (animate) fadeOut(tween(MusicMotion.page)) else ExitTransition.None },
                            popEnterTransition = { if (animate) fadeIn(tween(MusicMotion.page)) else EnterTransition.None },
                            popExitTransition = { if (animate) fadeOut(tween(MusicMotion.page)) + slideOutVertically(tween(MusicMotion.page)) { it / 40 } else ExitTransition.None }) {
                            composable(StudioTab.HOME.route) {
                                HomeScreen(state, model, onCalibrate = ::calibrate,
                                    onLibrary = { tab(StudioTab.LIBRARY) }, onPlayer = { tab(StudioTab.PLAYER) },
                                    onSettings = { tab(StudioTab.SETTINGS) }, onImport = { nav.navigate("import") },
                                    onOverlay = { activity.openOverlay(CalibrationOverlayService.ACTION_SHOW_PLAYER) }, onMenu = { quickMenu = true })
                            }
                            composable(StudioTab.LIBRARY.route) {
                                CompositionLocalProvider(LocalCoverTransition provides
                                    if (animate) CoverTransition(this@SharedTransitionLayout, this@composable, coverOrigin) else null) {
                                    WithPlayerDock(model, { openPlayer("mini", it) }) {
                                        LibraryScreen(model, onImport = { nav.navigate("import") { launchSingleTop = true } },
                                            onPlayer = { origin -> if (nav.currentDestination?.route == StudioTab.LIBRARY.route) openPlayer(origin) }, onMenu = { quickMenu = true })
                                    }
                                }
                            }
                            composable(StudioTab.PLAYER.route) {
                                CompositionLocalProvider(LocalCoverTransition provides
                                    if (animate) CoverTransition(this@SharedTransitionLayout, this@composable, coverOrigin) else null) {
                                    PlayerScreen(activity, state, model, onLibrary = { tab(StudioTab.LIBRARY) },
                                        onCalibrate = ::calibrate, onOverlay = { activity.openOverlay(CalibrationOverlayService.ACTION_SHOW_PLAYER) },
                                        onCollapse = ::collapsePlayer, startRequested = startRequested, consumeStartRequest = { startRequested = false })
                                }
                            }
                            composable("import") {
                                ImportScreen(activity, state.saved, model, onBack = { nav.popBackStack() },
                                    onPlayer = { tab(StudioTab.PLAYER) }, onLibrary = { tab(StudioTab.LIBRARY) })
                            }
                            composable("calibration") {
                                UnscaledCanvas {
                                    CalibrationScreen(state, activity, model::select, model::move, model::reset, model::save,
                                        onBack = { nav.popBackStack() })
                                }
                            }
                            composable(StudioTab.SETTINGS.route) {
                                CompositionLocalProvider(LocalCoverTransition provides
                                    if (animate) CoverTransition(this@SharedTransitionLayout, this@composable, coverOrigin) else null) {
                                    WithPlayerDock(model, { openPlayer("mini", it) }) {
                                        SettingsScreen(state, model, overlayGranted, onMenu = { quickMenu = true }, onCategory = { page ->
                                            if (nav.currentDestination?.route == StudioTab.SETTINGS.route) nav.navigate(page.route)
                                        })
                                    }
                                }
                            }
                            SettingsPage.entries.forEach { page ->
                                composable(page.route,
                                    enterTransition = {
                                        if (animate) fadeIn(tween(MusicMotion.page)) +
                                            slideInHorizontally(tween(MusicMotion.page)) { settingsDirection * it / 8 }
                                        else EnterTransition.None
                                    },
                                    popExitTransition = {
                                        if (animate) fadeOut(tween(MusicMotion.page)) +
                                            slideOutHorizontally(tween(MusicMotion.page)) { settingsDirection * it / 8 }
                                        else ExitTransition.None
                                    },
                                ) {
                                    SettingsDetailPage(page, onBack = {
                                        if (nav.currentDestination?.route == page.route) nav.popBackStack()
                                    }) {
                                        when (page) {
                                            SettingsPage.PLAYBACK -> PlaybackPreferences(state, model)
                                            SettingsPage.OVERLAY -> OverlayPreferences(model.rootUiAllowed, overlayGranted,
                                                onControls = { activity.openOverlay(CalibrationOverlayService.ACTION_SHOW_PLAYER) },
                                                onPermission = activity::openOverlayPermission)
                                            SettingsPage.CALIBRATION -> CalibrationPreferences(state, model, onCalibrate = ::calibrate,
                                                onCalibrationOverlay = { activity.openOverlay(CalibrationOverlayService.ACTION_SHOW_CALIBRATION) },
                                                onTest = { nav.navigate("test") { launchSingleTop = true } })
                                            SettingsPage.LIBRARY -> LibraryPreferences(model, onLibrary = {
                                                if (nav.currentDestination?.route == page.route) {
                                                    nav.popBackStack(StudioTab.SETTINGS.route, inclusive = false)
                                                    tab(StudioTab.LIBRARY)
                                                }
                                            }, onImport = { nav.navigate("import") { launchSingleTop = true } })
                                            SettingsPage.SYSTEM -> SystemPreferences(state, model, onAppearance = {
                                                if (nav.currentDestination?.route == page.route) nav.navigate(SettingsPage.APPEARANCE.route)
                                            })
                                            SettingsPage.ABOUT -> AboutPreferences(state, model)
                                            SettingsPage.APPEARANCE -> AppearancePreferences(model.preferences)
                                        }
                                    }
                                }
                            }
                            composable("test") {
                                UnscaledCanvas {
                                    TestScreen(state, activity, model::test, model::recordHit, model::cancelTest) { nav.popBackStack() }
                                }
                            }
                        }
                    }
                }
                if (showNavigation) {
                    AnimatedVisibility(visibleState = capsuleTransition,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = if (animate) fadeIn(tween(MusicMotion.feedback)) +
                            slideInVertically(tween(MusicMotion.feedback)) { it / 2 } else EnterTransition.None,
                        exit = if (animate) fadeOut(tween(96)) +
                            slideOutVertically(tween(96)) { it / 2 } else ExitTransition.None) {
                        CompositionLocalProvider(LocalStudioBackdrop provides backdrop) {
                            FloatingStudioBar(appearance.tabs, primary, appearance.showLabels,
                                Modifier.padding(horizontal = Spacing.page).padding(bottom = Spacing.item).widthIn(max = 448.dp)
                                    .fillMaxWidth(), ::tab)
                        }
                    }
                }
                BackHandler(enabled = !appearance.predictiveBack && route != StudioTab.LIBRARY.route && route != "test" && !quickMenu) {
                    nav.popBackStack()
                }
                if (quickMenu) ProductSheet(stringResource(R.string.studio_routes), { quickMenu = false }) {
                    StudioTab.entries.forEach { item ->
                        top.yukonga.miuix.kmp.preference.ArrowPreference(title = stringResource(item.labelRes()),
                            modifier = Modifier.testTag("studio/menu/${item.route}"), onClick = {
                            quickMenu = false
                            tab(item)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun WithPlayerDock(model: AppViewModel, onOpen: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val gutters = LocalStudioGutters.current
    val workspace = model.midiWorkspace
    val animate = !LocalAppearance.current.reducedEffects
    Column(Modifier.fillMaxSize().padding(gutters), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalStudioGutters provides PaddingValues(), content = content)
        }
        AnimatedVisibility(workspace.songId != null && workspace.report != null,
            enter = if (animate) fadeIn(tween(MusicMotion.dock)) + expandVertically(tween(MusicMotion.dock)) else EnterTransition.None,
            exit = if (animate) fadeOut(tween(MusicMotion.feedback)) + shrinkVertically(tween(MusicMotion.dock)) else ExitTransition.None) {
            MiniPlayer(workspace, onOpen, Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun UnscaledCanvas(content: @Composable () -> Unit) {
    val density = LocalDeviceDensity.current
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) { content() }
}
