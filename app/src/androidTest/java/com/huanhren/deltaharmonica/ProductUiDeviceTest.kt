package com.huanhren.deltaharmonica

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanhren.deltaharmonica.data.SongLibraryRepository
import com.huanhren.deltaharmonica.data.SongSourceType
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.midi.PerformanceScript
import com.huanhren.deltaharmonica.midi.ScriptCodec
import com.huanhren.deltaharmonica.midi.ScriptEvent
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.playback.PlaybackProgress
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import com.huanhren.deltaharmonica.ui.AppNavigation
import com.huanhren.deltaharmonica.ui.AppViewModel
import com.huanhren.deltaharmonica.ui.PlayerProgress
import com.huanhren.deltaharmonica.ui.SongSort
import com.huanhren.deltaharmonica.ui.theme.DeltaTheme
import com.huanhren.deltaharmonica.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DeltaHarmonicaApp

    @Before fun checkHost() = requireIsolatedTestHost()
    @After fun close() {
        instrumentation.runOnMainSync { app.preferences.setTheme(ThemeMode.SYSTEM) }
        closeIsolatedActivities()
    }

    @Test fun navigationAndThemeSurviveRecreation() {
        startIsolatedMainActivity()
        compose.onNodeWithTag("nav/settings").performClick()
        compose.onNodeWithText("深色").performClick()
        assertFalse(app.rootUiAllowed)
        compose.onNodeWithText("检查 Root 授权").assertDoesNotExist()
        instrumentation.runOnMainSync { resumedIsolatedActivity().recreate() }
        compose.onNodeWithTag("nav/settings").assertIsSelected()
        compose.onNodeWithText("深色").assertIsSelected()
        compose.onNodeWithText("浅色").performClick().assertIsSelected()
        compose.onNodeWithText("跟随系统").performClick().assertIsSelected()
        compose.onNodeWithTag("nav/player").performClick()
        compose.onNodeWithTag("nav/player").assertIsSelected()
    }

    @Test fun libraryScrollsAndFiltersManySongs() {
        startIsolatedMainActivity()
        val isolated = app as IsolatedTestApplication
        val songs = SongLibraryRepository(isolated.newTestDirectory("product-library"))
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val activity = resumedIsolatedActivity()
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AppViewModel(app.layouts, app.rootBackend, app.accessibilityBackend, app.backends,
                            songs, app.contentResolver, rootUiAllowed = false) as T
                }
                val model = ViewModelProvider(store, factory)[AppViewModel::class.java]
                activity.setContent { DeltaTheme { AppNavigation(model, activity) } }
            }
            compose.onNodeWithTag("nav/library").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("曲库还没有曲目").fetchSemanticsNodes().isNotEmpty() }
            populateAndCheckLibrary(songs)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun populateAndCheckLibrary(songs: SongLibraryRepository) {
        fun script(index: Int) = PerformanceScript(1, "profile", "fingerprint", "fixture-$index", 0,
            MonophonyPolicy.HIGHEST_NOTE, false, listOf(ScriptEvent(0, 120, 60, "NOTE_1", Region.NATURAL, false)))
        runBlocking {
            repeat(55) { index ->
                val score = script(index)
                songs.add("UI-fixture-${index.toString().padStart(2, '0')}", SongSourceType.JSON,
                    ScriptCodec.encode(score).toByteArray(), score, emptyList())
            }
        }
        compose.onNodeWithText(SongSort.NAME.label).performClick()
        compose.onNodeWithTag("library/list").performScrollToNode(hasText("UI-fixture-54"))
        compose.onNodeWithText("UI-fixture-54").assertIsDisplayed()
        compose.onNodeWithTag("library/list").performScrollToIndex(0)
        compose.onNodeWithTag("library/search").performTextInput("fixture-17")
        compose.onNodeWithText("UI-fixture-17").assertExists()
        compose.onNodeWithText("UI-fixture-18").assertDoesNotExist()
        compose.onNodeWithText("UI-fixture-17").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("player/current-note").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav/player").assertIsSelected()
    }

    @Test fun playbackCurrentNoteCompletionAndFailureAreReadable() {
        startIsolatedMainActivity()
        instrumentation.runOnMainSync {
            resumedIsolatedActivity().setContent {
                DeltaTheme(ThemeMode.DARK) {
                    Column {
                        PlayerProgress(PlaybackProgress(8, 2, "NOTE_3", "正在演奏", PlaybackStatus.PLAYING), 8)
                        PlayerProgress(PlaybackProgress(8, 8, null, "演奏已完成", PlaybackStatus.COMPLETED), 8)
                        PlayerProgress(PlaybackProgress(8, 2, null, "演奏异常", PlaybackStatus.FAILED), 8)
                    }
                }
            }
        }
        compose.onNodeWithText("NOTE_3").assertIsDisplayed()
        compose.onNodeWithText("演奏已完成").assertIsDisplayed()
        compose.onNodeWithText("演奏异常").assertIsDisplayed()
        compose.onAllNodesWithTag("player/progress").assertCountEquals(3)
    }
}
