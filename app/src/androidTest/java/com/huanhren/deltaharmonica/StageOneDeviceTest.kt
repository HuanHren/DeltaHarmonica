package com.huanhren.deltaharmonica

import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.huanhren.deltaharmonica.input.DisplaySnapshot
import com.huanhren.deltaharmonica.input.displaySnapshot
import com.huanhren.deltaharmonica.input.BackendAvailability
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.ui.CanvasGeometry
import com.huanhren.deltaharmonica.ui.TargetMarker
import com.huanhren.deltaharmonica.ui.theme.DeltaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only on a deliberately connected test device. Uses the same UI and DataStore as the app. */
@RunWith(AndroidJUnit4::class)
class StageOneDeviceTest {
    @Before fun launchIsolatedHost() = startIsolatedMainActivity()
    @org.junit.After fun finishIsolatedHost() = closeIsolatedActivities()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DeltaHarmonicaApp

    private fun requireEnabledService() {
        assumeTrue("User must enable the accessibility service in Android settings",
            app.accessibilityBackend.status().availability != BackendAvailability.NOT_ENABLED)
        compose.waitUntil(10_000) { app.accessibilityBackend.status().available }
        compose.onNodeWithTag("nav/settings").performClick()
        compose.onNodeWithText("Accessibility", useUnmergedTree = true).performClick()
        compose.onNodeWithText("返回").performClick()
    }

    private fun activateRootBackend() {
        compose.onNodeWithTag("nav/settings").performClick()
        compose.onNodeWithText("Root", useUnmergedTree = true).performClick()
        compose.onNodeWithText("检查 Root 授权").performClick()
        compose.waitUntil(35_000) { app.rootBackend.status().available }
        compose.onNodeWithText("返回").performClick()
    }

    @Test fun rtlCanvasKeepsPhysicalScreenCoordinates() {
        instrumentation.runOnMainSync {
            val activity = resumedIsolatedActivity()
            activity.setContent {
                DeltaTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Box(Modifier.fillMaxSize()) {
                            TargetMarker(TouchTarget("rtl", "RTL target", NormalizedPoint(0.25f, 0.75f)),
                                CanvasGeometry(DisplaySnapshot(ScreenSize(1000, 500), 0, true),
                                    ScreenPoint(20f, 30f), IntSize(1000, 500)), false)
                        }
                    }
                }
            }
        }
        val center = compose.onNodeWithContentDescription("RTL target").fetchSemanticsNode().boundsInRoot.center
        assertEquals(0.25f * 999 - 20, center.x, 1f)
        assertEquals(0.75f * 499 - 30, center.y, 1f)
    }

    private fun prepareLandscapeLayout() {
        instrumentation.runOnMainSync { resumedIsolatedActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            var landscape = false
            instrumentation.runOnMainSync {
                landscape = runCatching {
                    val activity = resumedIsolatedActivity()
                    val display = displaySnapshot(activity)
                    activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                        display.supported && display.size.orientation == LayoutOrientation.LANDSCAPE
                }.getOrDefault(false)
            }
            landscape
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("校准布局").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("校准布局").performScrollTo().performClick()
        compose.waitForIdle()
        val adapt = compose.onAllNodesWithText("使用当前方向并重排")
        if (adapt.fetchSemanticsNodes().isNotEmpty()) {
            adapt.onFirst().performClick()
            compose.onNodeWithText("恢复草稿").performClick()
        }

    }

    @Test fun dragSaveRecreateAndReload() {
        prepareLandscapeLayout()
        compose.onNodeWithText("收起面板").performClick()
        // Last key is away from the control panel and has enough room for an inward drag.
        val before = compose.onNodeWithContentDescription("Key 8").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithContentDescription("Key 8").performTouchInput {
            swipe(center, center + Offset(-80f, -70f), durationMillis = 450)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Key 8").fetchSemanticsNodes().isNotEmpty() }
        val after = compose.onNodeWithContentDescription("Key 8").fetchSemanticsNode().boundsInRoot.center
        assertTrue("Drag must visibly move the target", after.x < before.x - 30f && after.y < before.y - 30f)
        compose.onNodeWithText("显示面板").performClick()
        compose.onNodeWithTag("calibration/save").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
        val saved = runBlocking { app.layouts.load() }!!
        assertEquals(LayoutOrientation.LANDSCAPE, saved.orientation)
        assertEquals((1..8).map { "NOTE_$it" }, saved.targets.filter { it.type == TargetType.NOTE }.map { it.id })
        assertEquals(DefaultLayouts.controls().map { it.id }, saved.targets.filter { it.type != TargetType.NOTE }.map { it.id })
        instrumentation.runOnMainSync { resumedIsolatedActivity().recreate() }
        compose.waitForIdle()
        // Navigation restores the calibration route across recreation.
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("校准布局").performScrollTo().performClick()
        compose.onNodeWithText("收起面板").performClick()
        val restored = compose.onNodeWithContentDescription("Key 8").fetchSemanticsNode().boundsInRoot.center
        assertEquals(after.x, restored.x, 2f)
        assertEquals(after.y, restored.y, 2f)
        assertEquals(saved, runBlocking { app.layouts.load() })
    }

    @Ignore("The isolated host does not register an accessibility gesture service")
    @Test fun enabledAccessibilityGestureActuallyHitsSavedTarget() {
        requireEnabledService()
        prepareLandscapeLayout()
        compose.onNodeWithTag("calibration/save").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("测试已保存布局").performClick()
        compose.onNodeWithText("3 秒后测试一次触摸").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("测试期间收到触摸：Key 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("系统手势完成 · 请核对下方实际命中").assertExists()
    }

    @Ignore("The isolated host prohibits Root commands")
    @Test fun rootGestureActuallyHitsSavedTarget() {
        activateRootBackend()
        prepareLandscapeLayout()
        compose.onNodeWithTag("calibration/save").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("测试已保存布局").performClick()
        compose.onNodeWithText("3 秒后测试一次触摸").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("测试期间收到触摸：Key 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Root 点击命令完成 · 请核对下方实际命中").assertExists()
    }

    @Ignore("The isolated host prohibits Root commands")
    @Test fun leavingRootTestPageCancelsPendingTap() {
        activateRootBackend()
        prepareLandscapeLayout()
        compose.onNodeWithTag("calibration/save").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("测试已保存布局").performClick()
        compose.onNodeWithText("3 秒后测试一次触摸").performClick()
        instrumentation.runOnMainSync { resumedIsolatedActivity().onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("测试已保存布局").performClick()
        compose.onNodeWithText("测试已取消").assertExists()
    }

    @Ignore("The isolated host does not register an accessibility gesture service")
    @Test fun leavingTestPageCancelsPendingTap() {
        requireEnabledService()
        prepareLandscapeLayout()
        compose.onNodeWithTag("calibration/save").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("测试已保存布局").performClick()
        compose.onNodeWithText("3 秒后测试一次触摸").performClick()
        instrumentation.runOnMainSync { resumedIsolatedActivity().onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("测试已保存布局").performClick()
        compose.onNodeWithText("测试已取消").assertExists()
    }
}
