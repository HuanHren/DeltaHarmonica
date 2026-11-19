package com.huanhren.deltaharmonica

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanhren.deltaharmonica.data.SongLibraryRepository
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.InstrumentRuntimeState
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.ScreenSize
import com.huanhren.deltaharmonica.overlay.GameCalibrationPanel
import com.huanhren.deltaharmonica.overlay.GamePlayerPanel
import com.huanhren.deltaharmonica.overlay.GameSessionBar
import com.huanhren.deltaharmonica.playback.PlaybackProgress
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import com.huanhren.deltaharmonica.overlay.OverlayStyle
import com.huanhren.deltaharmonica.ui.AppViewModel
import com.huanhren.deltaharmonica.ui.HomeLayout
import com.huanhren.deltaharmonica.ui.LauncherStyle
import com.huanhren.deltaharmonica.ui.PlayerPreferences
import com.huanhren.deltaharmonica.ui.StudioAppearance
import com.huanhren.deltaharmonica.ui.StudioLanguage
import com.huanhren.deltaharmonica.ui.StudioSeed
import com.huanhren.deltaharmonica.ui.StudioTab
import com.huanhren.deltaharmonica.ui.labelRes
import com.huanhren.deltaharmonica.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class StudioUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DeltaHarmonicaApp
    private lateinit var originalAppearance: StudioAppearance
    private var originalLocales: LocaleList? = null
    private var originalAliases = emptyMap<ComponentName, Int>()

    @Before fun prepareIsolatedAppearance() {
        requireIsolatedTestHost()
        instrumentation.runOnMainSync {
            originalAppearance = app.preferences.appearance.value
            app.preferences.updateAppearance { StudioAppearance(theme = ThemeMode.LIGHT, navigation = StudioTab.entries.toSet()) }
        }
        startIsolatedMainActivity()
    }

    @After fun restoreIsolatedAppearance() {
        if (!::originalAppearance.isInitialized) return
        closeIsolatedActivities()
        instrumentation.runOnMainSync {
            app.preferences.updateAppearance { originalAppearance }
            if (Build.VERSION.SDK_INT >= 33) {
                originalLocales?.let { app.getSystemService(LocaleManager::class.java).applicationLocales = it }
            }
            originalAliases.forEach { (component, state) ->
                app.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }

    @Test fun settingsCategoriesOpenDedicatedPagesAndReturnToSettings() {
        openSettings()
        captureStudioFrame(compose, "settings-index")
        val categories = listOf("playback", "overlay", "calibration", "library", "system", "about")
        for (category in categories) {
            openSettingsCategory(category)
            compose.onNodeWithTag("studio/settings-scroll").assertDoesNotExist()
            compose.onNodeWithTag("studio/capsule").assertDoesNotExist()
            when (category) {
                "playback" -> captureStudioFrame(compose, "settings-playback")
                "library" -> {
                    compose.onNodeWithTag("studio/settings/import").performClick()
                    compose.waitForTag("import/pick", displayed = true)
                    onActivity { onBackPressedDispatcher.onBackPressed() }
                    compose.waitForTag("studio/settings/page/library", displayed = true)
                }
                "system" -> {
                    compose.onNodeWithTag("studio/settings/appearance").performClick()
                    compose.waitForTag("studio/settings/page/appearance", displayed = true)
                    onActivity { onBackPressedDispatcher.onBackPressed() }
                    compose.waitForTag("studio/settings/page/system", displayed = true)
                }
                "about" -> {
                    val diagnostics = compose.onNodeWithTag("studio/settings/diagnostics")
                    val refresh = compose.onNodeWithText(activityString(R.string.refresh_state))
                    refresh.assertDoesNotExist()
                    diagnostics.performScrollTo().performClick()
                    refresh.assertIsDisplayed()
                    compose.onNodeWithText(activityString(R.string.root_check)).assertDoesNotExist()
                    diagnostics.performClick()
                    refresh.assertDoesNotExist()
                }
            }
            if (category == "playback") {
                compose.onNodeWithTag("studio/settings/back").performClick()
            } else {
                onActivity { onBackPressedDispatcher.onBackPressed() }
            }
            compose.waitForTag("studio/settings-scroll", displayed = true)
            tab("settings").assertIsSelected()
        }
    }

    @Test fun allThemeModesAndSeedsAreSelectableAndPersisted() {
        openAppearance()
        val expectedModes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.MONET_SYSTEM,
            ThemeMode.MONET_LIGHT, ThemeMode.MONET_DARK, ThemeMode.SYSTEM)
        expectedModes.forEach { mode ->
            choose("studio/theme", mode.labelRes())
            compose.onNodeWithTag("studio/theme").assert(hasText(activityString(mode.labelRes())))
            assertEquals(mode, app.preferences.appearance.value.theme)
            when (mode) {
                ThemeMode.LIGHT -> captureStudioFrame(compose, "settings-light")
                ThemeMode.DARK -> {
                    captureStudioFrame(compose, "settings-dark")
                    backToSettingsFromAppearance()
                    tab("home").performClick()
                    captureStudioFrame(compose, "home-workbench-dark")
                    openAppearance()
                }
                else -> Unit
            }
        }
        compose.onNodeWithTag("studio/seed/AUTO").performScrollTo().assertIsSelected()
        val expectedSeeds = listOf(
            StudioSeed.TEAL, StudioSeed.CYAN, StudioSeed.BLUE, StudioSeed.INDIGO, StudioSeed.PURPLE, StudioSeed.VIOLET,
            StudioSeed.MAGENTA, StudioSeed.PINK, StudioSeed.ROSE, StudioSeed.RED, StudioSeed.ORANGE, StudioSeed.AMBER,
            StudioSeed.YELLOW, StudioSeed.LIME, StudioSeed.GREEN, StudioSeed.BLUE_GREY,
        )
        expectedSeeds.forEach { seed ->
            compose.onNodeWithTag("studio/seed/${seed.name}").performScrollTo().activate().assertIsSelected()
            assertEquals(seed, app.preferences.appearance.value.seed)
        }
        choose("studio/theme", ThemeMode.MONET_DARK.labelRes())
        recreateActivity()
        compose.waitForTag("studio/settings/page/appearance", displayed = true)
        compose.onNodeWithTag("studio/theme").performScrollTo().assert(hasText(activityString(ThemeMode.MONET_DARK.labelRes())))
        compose.onNodeWithTag("studio/seed/BLUE_GREY").performScrollTo().assertIsSelected()
        val reloaded = PlayerPreferences(app).appearance.value
        assertEquals(ThemeMode.MONET_DARK, reloaded.theme)
        assertEquals(StudioSeed.BLUE_GREY, reloaded.seed)
    }

    @Test fun allThreeHomeLayoutsRenderAndTheChoicePersists() {
        tab("home").performClick()
        compose.onNodeWithTag("home/layout/WORKBENCH").assertIsDisplayed()
        captureStudioFrame(compose, "home-workbench-light")
        for (layout in listOf(HomeLayout.LIST, HomeLayout.GRID)) {
            openAppearance()
            choose("studio/home-layout", layout.labelRes())
            backToSettingsFromAppearance()
            tab("home").performClick().assertIsSelected()
            compose.onNodeWithTag("home/layout/${layout.name}").assertIsDisplayed()
            captureStudioFrame(compose, "home-${layout.name.lowercase(Locale.ROOT)}-light")
        }
        recreateActivity()
        compose.onNodeWithTag("home/layout/GRID").assertIsDisplayed()
        assertEquals(HomeLayout.GRID, PlayerPreferences(app).appearance.value.layout)
    }

    @Test fun swipingCapsuleChangesRouteAndHiddenRoutesRemainReachable() {
        val capsule = compose.onNodeWithTag("studio/capsule").assertIsDisplayed()
        val home = compose.onNodeWithTag("nav/library").assertIsSelected()
        val player = compose.onNodeWithTag("nav/player")
        val capsuleLeft = capsule.fetchSemanticsNode().boundsInRoot.left
        val from = home.fetchSemanticsNode().boundsInRoot.center.x - capsuleLeft
        val to = player.fetchSemanticsNode().boundsInRoot.center.x - capsuleLeft
        capsule.performTouchInput { swipe(Offset(from, centerY), Offset(to, centerY), durationMillis = 350) }
        player.assertIsSelected()

        openAppearance()
        for (tab in listOf(StudioTab.HOME, StudioTab.LIBRARY, StudioTab.PLAYER)) {
            studioSwitch("studio/nav/${tab.name}").performScrollTo().activate().assertIsOff()
        }
        studioSwitch("studio/nav/SETTINGS").performScrollTo().assertIsOn().assertIsNotEnabled()
        backToSettingsFromAppearance()
        tab("settings").assertIsSelected()
        compose.onNodeWithTag("nav/home").assertDoesNotExist()
        compose.onNodeWithTag("nav/library").assertDoesNotExist()
        compose.onNodeWithTag("nav/player").assertDoesNotExist()
        assertEquals(setOf(StudioTab.SETTINGS), PlayerPreferences(app).appearance.value.navigation)
        compose.onNodeWithTag("studio/menu").performScrollTo().performClick()
        compose.waitForTag("studio/menu/library", displayed = true).performClick()
        compose.onNodeWithTag("library/list").assertIsDisplayed()
        compose.onNodeWithTag("nav/library").assertDoesNotExist()
    }

    @Test fun capsuleHidesDuringVerticalScrollAndReturnsWhenIdle() {
        openLandscapeSettings()
        val capsule = compose.onNodeWithTag("studio/capsule").assertIsDisplayed()
        val settings = compose.onNodeWithTag("studio/settings-scroll").assertIsDisplayed()
        val initialViewport = settings.fetchSemanticsNode()
        val scrollBefore = initialViewport.config[SemanticsProperties.VerticalScrollAxisRange].value()
        val visibleBottom = initialViewport.boundsInRoot.bottom
        val minimumReclaimedSpace = 48 * onActivity { resources.displayMetrics.density }
        compose.mainClock.autoAdvance = false
        try {
            settings.performTouchInput {
                down(Offset(centerX, height * .66f))
                for (fraction in listOf(.16f, .34f, .54f, .72f, .86f, .96f, .992f, 1f)) {
                    moveTo(Offset(centerX, height * (.66f - .31f * fraction)), delayMillis = 48)
                }
                up()
            }
            compose.mainClock.advanceTimeBy(112)
            val hiddenViewport = settings.fetchSemanticsNode()
            val scrollAfter = hiddenViewport.config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Touch input did not scroll Settings: $scrollBefore to $scrollAfter", scrollAfter > scrollBefore)
            val reclaimed = hiddenViewport.boundsInRoot.bottom - visibleBottom
            assertTrue("Settings should reclaim the navigation space while hidden (reclaimed $reclaimed px)",
                reclaimed >= minimumReclaimedSpace)
            capsule.assertIsNotDisplayed()
            captureIsolatedFrame("settings-navigation-hidden")
            compose.mainClock.advanceTimeBy(160)
            capsule.assertIsDisplayed()
            compose.mainClock.advanceTimeBy(80)
            assertEquals(visibleBottom, settings.fetchSemanticsNode().boundsInRoot.bottom, 2f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun sliderAppliesBothScaleLimitsAndPreservesPlatformDensity() {
        openAppearance()
        val beforeDpi = onActivity { resources.configuration.densityDpi }
        val beforeFontScale = onActivity { resources.configuration.fontScale }
        val slider = compose.onNodeWithTag("studio/scale")
        for (scale in listOf(.8f, .94f, 1.1f)) {
            slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(scale))
            }
            assertEquals(scale, app.preferences.appearance.value.scale, .001f)
            compose.onNodeWithText(activityString(R.string.scale_label, (scale * 100).roundToInt())).assertExists()
            val minHeightPx = 48 * onActivity { resources.displayMetrics.density }
            backToSettingsFromAppearance()
            val settingsHeightPx = tab("settings").fetchSemanticsNode().boundsInRoot.height
            assertTrue("Navigation touch target is ${settingsHeightPx}px at scale $scale", settingsHeightPx >= minHeightPx - 1f)
            openAppearance()
        }
        recreateActivity()
        assertEquals(1.1f, PlayerPreferences(app).appearance.value.scale, .001f)
        compose.onNodeWithText(activityString(R.string.scale_label, 110)).assertExists()
        assertEquals(beforeDpi, onActivity { resources.configuration.densityDpi })
        assertEquals(beforeFontScale, onActivity { resources.configuration.fontScale }, 0f)
    }

    @Test fun recordSettingsScrollFrameStats() {
        requireIsolatedTestHost()
        openLandscapeSettings()
        val settings = compose.onNodeWithTag("studio/settings-scroll").assertIsDisplayed()
        val settingsNode = settings.fetchSemanticsNode()
        val viewport = settingsNode.boundsInWindow
        val initialScroll = settingsNode.config[SemanticsProperties.VerticalScrollAxisRange].value()
        val decor = onActivity { window.decorView }
        val automation = instrumentation.uiAutomation
        fun gfxInfo(action: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("dumpsys gfxinfo ${app.packageName} $action"),
        ).bufferedReader().use { it.readText() }
        fun swipeInRealTime(start: Float, end: Float) {
            val downAt = SystemClock.uptimeMillis()
            fun send(action: Int, fraction: Float) {
                val y = viewport.top + viewport.height * (start + (end - start) * fraction)
                val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                    viewport.center.x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    instrumentation.runOnMainSync { decor.dispatchTouchEvent(event) }
                } finally {
                    event.recycle()
                }
            }
            send(MotionEvent.ACTION_DOWN, 0f)
            try {
                for (step in 1..24) {
                    SystemClock.sleep(16)
                    send(MotionEvent.ACTION_MOVE, step / 24f)
                }
            } finally {
                send(MotionEvent.ACTION_UP, 1f)
            }
            SystemClock.sleep(280)
        }

        gfxInfo("reset")
        repeat(4) { index ->
            swipeInRealTime(.75f, .30f)
            if (index == 0) {
                val scroll = settings.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("Frame sample gesture did not scroll Settings", scroll > initialScroll)
            }
            swipeInRealTime(.30f, .75f)
        }
        compose.waitForIdle()
        val folder = (app as IsolatedTestApplication).runDirectory.resolve("studio-captures").apply { mkdirs() }
        folder.resolve("settings-scroll-framestats.txt").writeText(gfxInfo("framestats"))
    }

    @Test fun manualLanguageChangesVisibleResourcesAndSurvivesRecreation() {
        if (Build.VERSION.SDK_INT >= 33) originalLocales = app.getSystemService(LocaleManager::class.java).applicationLocales
        openAppearance()
        choose("studio/language", R.string.english)
        waitForLanguage(Locale.ENGLISH)
        compose.onNodeWithText(localizedString(Locale.ENGLISH, R.string.appearance)).assertExists()
        assertEquals(StudioLanguage.ENGLISH, PlayerPreferences(app).appearance.value.language)
        recreateActivity()
        waitForLanguage(Locale.ENGLISH)
        compose.onNodeWithText(localizedString(Locale.ENGLISH, R.string.appearance)).assertExists()
        choose("studio/language", R.string.chinese)
        waitForLanguage(Locale.SIMPLIFIED_CHINESE)
        compose.onNodeWithText(localizedString(Locale.SIMPLIFIED_CHINESE, R.string.appearance)).assertExists()
        assertEquals(StudioLanguage.CHINESE, PlayerPreferences(app).appearance.value.language)
    }

    @Test fun iconPreferencePreservesTheIsolatedHostsHiddenLauncher() {
        val appClassPackage = MainActivity::class.java.name.substringBeforeLast('.')
        val wave = ComponentName(app.packageName, "$appClassPackage.WaveLauncher")
        val compass = ComponentName(app.packageName, "$appClassPackage.CompassLauncher")
        originalAliases = listOf(wave, compass).associateWith(app.packageManager::getComponentEnabledSetting)
        openAppearance()
        choose("studio/launcher", R.string.compass_icon)
        assertLauncherPreference(compass, wave, R.drawable.ic_launcher_compass)
        recreateActivity()
        assertLauncherPreference(compass, wave, R.drawable.ic_launcher_compass)
        assertEquals(LauncherStyle.COMPASS, PlayerPreferences(app).appearance.value.launcher)
        choose("studio/launcher", R.string.wave_icon)
        assertLauncherPreference(wave, compass, R.drawable.ic_launcher)
    }

    @Test fun blurAndGlassCanBeDisabledIndependently() {
        openAppearance()
        studioSwitch("studio/blur").performScrollTo().assertIsOn().activate().assertIsOff()
        studioSwitch("studio/glass").assertIsOn()
        backToSettingsFromAppearance()
        tab("home").performClick()
        captureStudioFrame(compose, "home-glass-only")
        openAppearance()
        studioSwitch("studio/glass").performScrollTo().activate().assertIsOff()
        studioSwitch("studio/blur").performScrollTo().activate().assertIsOn()
        recreateActivity()
        studioSwitch("studio/blur").performScrollTo().assertIsOn()
        studioSwitch("studio/glass").assertIsOff()
        val saved = PlayerPreferences(app).appearance.value
        assertTrue(saved.blur)
        assertFalse(saved.glass)
        backToSettingsFromAppearance()
        tab("home").performClick()
        captureStudioFrame(compose, "home-blur-only")
    }

    @Test fun predictiveCallbacksAndStandardBackKeepNavigationConsistent() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        tab("home").performClick()
        compose.waitForTag("home/import").performScrollTo().performClick()
        val picker = compose.waitForTag("import/pick").assertIsDisplayed()
        dispatchPredictiveProgress()
        onActivity { onBackPressedDispatcher.dispatchOnBackCancelled() }
        picker.assertIsDisplayed()

        dispatchPredictiveProgress()
        onActivity { onBackPressedDispatcher.onBackPressed() }
        compose.waitForTag("home/layout/WORKBENCH")
        tab("home").assertIsSelected()

        openAppearance()
        studioSwitch("studio/predictive-back").performScrollTo().assertIsOn().activate().assertIsOff()
        backToSettingsFromAppearance()
        tab("home").performClick()
        compose.waitForTag("home/import").performScrollTo().performClick()
        compose.waitForTag("import/pick").assertIsDisplayed()
        onActivity { onBackPressedDispatcher.onBackPressed() }
        tab("home").assertIsSelected()
        assertFalse(PlayerPreferences(app).appearance.value.predictiveBack)
    }

    private fun dispatchPredictiveProgress() {
        onActivity {
            onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        onActivity {
            onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 500f, .55f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
    }

    @Test fun nativeGamePanelExpandsAndCollapsesInPortrait() = verifyNativeGamePanel(landscape = false)

    @Test fun nativeGamePanelExpandsAndCollapsesInLandscape() = verifyNativeGamePanel(landscape = true)

    @Test fun calibrationPanelSupportsGroupedActionsAndCollapsingInLandscape() {
        onActivity { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            runCatching { onActivity { resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } }.getOrDefault(false)
        }

        lateinit var panel: GameCalibrationPanel
        lateinit var state: GameCalibrationPanel.State
        val actions = mutableListOf<String>()
        onActivity {
            val style = OverlayStyle(this, StudioAppearance(theme = ThemeMode.DARK))
            val metrics = resources.displayMetrics
            val bounds = ScreenSize(metrics.widthPixels, metrics.heightPixels)
            val container = FrameLayout(this).apply { setBackgroundColor(0xFF131E27.toInt()) }
            fun position(controller: GameCalibrationPanel) = FrameLayout.LayoutParams(
                if (controller.minimized) controller.collapsedWidth else controller.preferredWidth.coerceAtMost(bounds.width),
                if (controller.minimized) controller.collapsedHeight else minOf(controller.maxHeight, bounds.height - style.dp(64)),
                Gravity.CENTER,
            )
            var controller: GameCalibrationPanel? = null
            panel = GameCalibrationPanel(style, {
                controller?.let { it.view.layoutParams = position(it) }
            }, { handle, click ->
                click?.let { handle.setOnClickListener { it() } }
            }, GameCalibrationPanel.Actions(
                select = {},
                nudge = { x, y -> actions += "nudge $x $y" },
                save = { actions += "save" },
                recalibrate = { actions += "recalibrate" },
                toggleMarkers = { actions += "hide" },
                confirmCoordinates = { actions += "confirm" },
                testTap = { actions += "tap" },
                testNote = { actions += if (it) "hold" else "short" },
                adjustDuration = { actions += "duration $it" },
                sendRegion = { actions += "send $it" },
                syncRegion = { actions += "sync $it" },
                sendSemitone = { actions += "semitone $it" },
                syncSemitone = {},
                resetState = { actions += "reset" },
                close = { actions += "close" },
            ))
            controller = panel
            val targets = DefaultLayouts.create().targets
            state = GameCalibrationPanel.State(
                targets = targets, selected = targets.first().id, display = bounds,
                compatible = true, draft = false, hasReference = true, shown = true,
                coordinatesConfirmed = true, busy = false, duration = 750L,
                runtime = InstrumentRuntimeState(), root = false, backendAvailable = true,
                notice = "", result = "",
            )
            panel.update(state)
            container.addView(panel.view, position(panel))
            setContentView(container)
            assertTrue(panel.minimized)
        }
        captureIsolatedFrame("calibration-landscape-capsule")

        fun clickByDescription(label: String) {
            val control = panel.view.descendants().single { it.contentDescription == label }
            assertTrue(control.isEnabled && control.performClick())
        }
        fun clickByLabel(label: String, index: Int = 0) {
            val control = panel.view.descendants().filterIsInstance<android.widget.Button>()
                .filter { it.text.toString() == label }.elementAt(index)
            assertTrue(control.isEnabled && control.performClick())
        }
        onActivity {
            clickByDescription(getString(R.string.game_cal_expand))
            assertFalse(panel.minimized)
            clickByLabel(getString(R.string.game_cal_save))
            clickByLabel(getString(R.string.game_cal_tap))
            clickByDescription(getString(R.string.game_cal_nudge_x, -250))
            clickByLabel(getString(R.string.game_cal_confirmed))
            clickByLabel(getString(R.string.game_cal_hide))
            clickByLabel(getString(R.string.game_cal_recalibrate))
        }
        captureIsolatedFrame("calibration-landscape-position")
        onActivity {
            clickByLabel(getString(R.string.game_cal_test_tab))
            clickByLabel(getString(R.string.game_cal_short))
            clickByLabel(getString(R.string.game_cal_hold_action))
            clickByLabel("+250 ms")
            panel.update(state.copy(busy = true))
            val hold = panel.view.descendants().filterIsInstance<android.widget.Button>()
                .single { it.text.toString() == getString(R.string.game_cal_hold_action) }
            assertFalse(hold.isEnabled)
            panel.update(state)
            clickByLabel(getString(R.string.game_cal_register_tab))
            clickByLabel(getString(R.string.overlay_high))
            clickByLabel(getString(R.string.overlay_low), index = 1)
            clickByLabel(getString(R.string.overlay_semitone_on))
        }
        captureIsolatedFrame("calibration-landscape-register")
        onActivity {
            clickByDescription(getString(R.string.game_cal_more))
            clickByLabel(getString(R.string.game_cal_reset))
            clickByLabel(getString(R.string.game_cal_exit))
            clickByDescription(getString(R.string.game_cal_collapse))
            assertTrue(panel.minimized)
            assertEquals(listOf(
                "save", "tap", "nudge -250 0", "confirm", "hide", "recalibrate", "short", "hold",
                "duration 250", "send ${Region.HIGH}", "sync ${Region.LOW}", "semitone true", "reset", "close",
            ), actions)
        }
    }

    private fun verifyNativeGamePanel(landscape: Boolean) {
        if (landscape) {
            onActivity { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                runCatching { onActivity { resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } }.getOrDefault(false)
            }
        }
        lateinit var panel: GamePlayerPanel
        lateinit var frame: FrameLayout
        onActivity {
            val isolated = app as IsolatedTestApplication
            val songs = SongLibraryRepository(isolated.newTestDirectory(if (landscape) "overlay-native-landscape" else "overlay-native-portrait"))
            val fixture = AppViewModel(app.layouts, app.rootBackend, app.accessibilityBackend, app.backends, songs,
                app.contentResolver, rootUiAllowed = false)
            fixture.midiWorkspace.name = "潮汐练习曲"
            val style = OverlayStyle(this, StudioAppearance(theme = ThemeMode.DARK, reducedEffects = !landscape))
            val container = FrameLayout(this).apply { setBackgroundColor(0xFF10161E.toInt()) }
            frame = container
            val fullscreen = resources.displayMetrics
            panel = GamePlayerPanel(style, fixture, { null }, {}, { _, _ -> }, {}, {}, fullscreen.heightPixels)
            container.addView(panel.view, FrameLayout.LayoutParams(panel.collapsedWidth, panel.collapsedHeight,
                Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = style.dp(20) })
            setContentView(container)
            assertTrue(panel.minimized)
        }
        captureIsolatedFrame(if (landscape) "overlay-landscape-collapsed" else "overlay-portrait-collapsed")
        onActivity {
            assertTrue(panel.view.descendants().single { it.contentDescription == getString(R.string.overlay_expand) }.performClick())
            assertFalse(panel.minimized)
            panel.view.layoutParams = panel.view.layoutParams.apply {
                width = panel.preferredWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            val labels = panel.view.descendants().filterIsInstance<TextView>().map { it.text.toString() }.toSet()
            assertTrue(labels.containsAll(listOf(
                "潮汐练习曲", getString(R.string.overlay_choose), getString(R.string.overlay_8_notes),
                getString(R.string.overlay_16_notes), getString(R.string.overlay_full), getString(R.string.overlay_speed, 1),
                getString(R.string.overlay_speed, 2), getString(R.string.overlay_start), getString(R.string.overlay_stop),
            )))
            assertFalse(panel.view.descendants().filterIsInstance<TextView>().single { it.text == getString(R.string.overlay_start) }.isEnabled)
            assertTrue(panel.view.descendants().filterIsInstance<ProgressBar>().single().isShown)
        }
        captureIsolatedFrame(if (landscape) "overlay-landscape-expanded" else "overlay-portrait-expanded")
        onActivity {
            assertTrue(panel.view.height in (panel.collapsedHeight + 1)..resources.displayMetrics.heightPixels)
            assertTrue(panel.view.descendants().single { it.contentDescription == getString(R.string.overlay_minimize) }.performClick())
            assertTrue(panel.minimized)
            assertTrue(panel.view.descendants().any { it.contentDescription == getString(R.string.overlay_expand) })
            panel.view.layoutParams = FrameLayout.LayoutParams(panel.collapsedWidth, panel.collapsedHeight).apply {
                val screen = com.huanhren.deltaharmonica.model.ScreenSize(frame.width, frame.height)
                val target = com.huanhren.deltaharmonica.overlay.OverlayGeometry.snappedPlayerOrigin(
                    com.huanhren.deltaharmonica.model.ScreenPoint(48f, frame.height * .42f), screen, width, height, panel.screenMargin)
                leftMargin = target.x.toInt()
                topMargin = target.y.toInt()
            }
        }
        if (landscape) captureIsolatedFrame("overlay-snap-layout")
        onActivity { panel.dispose() }
    }

    @Test fun accessibilityVolumePauseIsOptInAndRemainsSelectedAfterRecreation() {
        val prior = app.preferences.volumePause.value
        try {
            instrumentation.runOnMainSync { app.preferences.setVolumePause(false) }
            openSettings()
            openSettingsCategory("playback")
            studioSwitch("studio/volume-pause").performScrollTo().assertIsOff().activate().assertIsOn()
            recreateActivity()
            studioSwitch("studio/volume-pause").performScrollTo().assertIsOn()
            assertTrue(PlayerPreferences(app).volumePause.value)
            captureStudioFrame(compose, "volume-pause-setting")
        } finally {
            instrumentation.runOnMainSync { app.preferences.setVolumePause(prior) }
        }
    }

    @Test fun sessionBarShowsPausedTrackAndDisablesPauseDuringAccessibilityHold() {
        onActivity {
            var pauseCalls = 0
            var stopCalls = 0
            val style = OverlayStyle(this, StudioAppearance(theme = ThemeMode.DARK, reducedEffects = true))
            lateinit var bar: GameSessionBar
            bar = GameSessionBar(style, "潮汐练习曲", { pauseCalls++ }, { stopCalls++ }, { bar.setExpanded(!bar.expanded) })
            val container = FrameLayout(this).apply { setBackgroundColor(0xFF10161E.toInt()) }
            container.addView(bar.view, FrameLayout.LayoutParams(bar.expandedWidth, bar.expandedHeight, Gravity.CENTER))
            setContentView(container)
            val playing = PlaybackProgress(16, 7, phase = PlaybackStatus.PLAYING, elapsedMs = 6400, durationMs = 18000)
            bar.update(playing, canExpand = true, canPause = false)
            assertFalse(bar.view.descendants().single { it.contentDescription == getString(R.string.session_pause) }.isEnabled)
            val paused = playing.copy(phase = PlaybackStatus.PAUSED)
            bar.update(paused, canExpand = true, canPause = true)
            val details = bar.view.descendants().single { it.contentDescription == getString(R.string.overlay_expand) }
            assertTrue(details.isEnabled && details.performClick())
            bar.update(paused, canExpand = true, canPause = true)
            assertTrue(bar.expanded)
            assertEquals(355, bar.view.descendants().filterIsInstance<ProgressBar>().single().progress)
            val resume = bar.view.descendants().single { it.contentDescription == getString(R.string.session_resume) }
            val stop = bar.view.descendants().single { it.contentDescription == getString(R.string.overlay_stop) }
            assertTrue(resume.isEnabled && resume.performClick())
            assertTrue(stop.isEnabled && stop.performClick())
            assertEquals(1, pauseCalls)
            assertEquals(1, stopCalls)
        }
        captureIsolatedFrame("overlay-paused-controls")
    }

    private fun openSettings() {
        tab("settings").performClick().assertIsSelected()
    }

    private fun openLandscapeSettings() {
        onActivity { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            runCatching { onActivity { resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } }.getOrDefault(false)
        }
        openSettings()
    }

    private fun openSettingsCategory(category: String) {
        compose.onNodeWithTag("studio/settings/category/$category").performScrollTo().performClick()
        compose.waitForTag("studio/settings/page/$category", displayed = true)
    }

    private fun openAppearance() {
        openSettings()
        openSettingsCategory("system")
        compose.onNodeWithTag("studio/settings/appearance").performClick()
        compose.waitForTag("studio/settings/page/appearance", displayed = true)
    }

    private fun backToSettingsFromAppearance() {
        compose.onNodeWithTag("studio/settings/back").performClick()
        compose.waitForTag("studio/settings/page/system", displayed = true)
        compose.onNodeWithTag("studio/settings/back").performClick()
        compose.waitForTag("studio/settings-scroll", displayed = true)
    }

    private fun tab(route: String): SemanticsNodeInteraction = compose.waitForTag("nav/$route", displayed = true)

    private fun studioSwitch(tag: String): SemanticsNodeInteraction {
        val row = hasTestTag(tag)
        return compose.onNode(isToggleable() and (row or hasAnyAncestor(row)))
    }

    private fun choose(tag: String, @StringRes value: Int) {
        compose.chooseStudioOption(tag, activityString(value))
    }

    private fun recreateActivity() = compose.recreateStudioActivity()

    private fun waitForLanguage(locale: Locale) {
        compose.waitUntil(10_000) {
            runCatching { onActivity { resources.configuration.locales[0].language == locale.language } }.getOrDefault(false)
        }
    }

    private fun localizedString(locale: Locale, @StringRes value: Int): String =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) }).getString(value)

    private fun activityString(@StringRes value: Int, vararg arguments: Any): String =
        onActivity { getString(value, *arguments) }

    private fun <T> onActivity(read: MainActivity.() -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching { resumedIsolatedActivity().read() } }
        return checkNotNull(result).getOrThrow()
    }

    private fun assertLauncherPreference(enabled: ComponentName, disabled: ComponentName, expectedIcon: Int) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val manager = app.packageManager
        assertTrue(manager.queryIntentActivities(launcher, 0).isEmpty())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, manager.getComponentEnabledSetting(enabled))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, manager.getComponentEnabledSetting(disabled))
        val info = manager.getActivityInfo(enabled, 0)
        assertEquals(expectedIcon, info.iconResource)
        assertFalse(info.exported)
    }
}

internal fun ComposeTestRule.chooseStudioOption(tag: String, value: String) {
    onNodeWithTag(tag).performScrollTo().activate()
    val option = SemanticsMatcher("Option of $tag") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("$tag/option/") == true
    }
    onNode(option and hasText(value)).performScrollTo().performClick()
}

internal fun ComposeTestRule.waitForTag(tag: String, displayed: Boolean = false): SemanticsNodeInteraction {
    val node = onNodeWithTag(tag)
    waitUntil(6_000) {
        if (displayed) node.isDisplayed() else onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    return node
}

private fun SemanticsNodeInteraction.activate(): SemanticsNodeInteraction =
    performSemanticsAction(SemanticsActions.OnClick) { action -> assertTrue(action()) }

private fun View.descendants(): Sequence<View> = sequence {
    yield(this@descendants)
    if (this@descendants is ViewGroup) {
        for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
    }
}

internal fun ComposeTestRule.recreateStudioActivity() {
    requireIsolatedTestHost()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var previous: MainActivity
    instrumentation.runOnMainSync {
        previous = resumedIsolatedActivity()
        previous.recreate()
    }
    waitUntil(10_000) {
        var replaced = false
        instrumentation.runOnMainSync {
            replaced = runCatching { resumedIsolatedActivity() !== previous }.getOrDefault(false)
        }
        replaced
    }
}

internal fun captureStudioFrame(compose: ComposeTestRule, name: String, includeSystemUi: Boolean = false) {
    compose.waitForIdle()
    captureIsolatedFrame(name, includeSystemUi)
}

private fun captureIsolatedFrame(name: String, includeSystemUi: Boolean = false) {
    requireIsolatedTestHost()
    require(name.matches(Regex("[a-z-]+")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val app = instrumentation.targetContext.applicationContext as IsolatedTestApplication
    val folder = app.runDirectory.resolve("studio-captures")
    check(folder.isDirectory || folder.mkdir())
    if (includeSystemUi) {
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Display capture failed" }
        try { saveFrame(image, folder.resolve("$name.png"), app) } finally { image.recycle() }
        return
    }
    val finished = CountDownLatch(1)
    lateinit var image: Bitmap
    var status = PixelCopy.ERROR_UNKNOWN
    instrumentation.runOnMainSync {
        val window = resumedIsolatedActivity().window
        image = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, image, { result -> status = result; finished.countDown() }, Handler(Looper.getMainLooper()))
    }
    try {
        check(finished.await(5, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) { "Window capture failed ($status)" }
        saveFrame(image, folder.resolve("$name.png"), app)
    } finally {
        image.recycle()
    }
}

private fun saveFrame(image: Bitmap, output: java.io.File, app: IsolatedTestApplication) {
    output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    Log.i("StudioUiDeviceTest", "CAPTURE ${output.relativeTo(app.runDirectory).invariantSeparatorsPath}")
}
