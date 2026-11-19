package com.huanhren.deltaharmonica

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
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
import com.huanhren.deltaharmonica.ui.StudioAppearance
import com.huanhren.deltaharmonica.ui.theme.DeltaTheme
import com.huanhren.deltaharmonica.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DeltaHarmonicaApp
    private lateinit var originalAppearance: StudioAppearance
    private val fixtureHistory = mutableListOf<String>()

    @Before fun checkHost() {
        requireIsolatedTestHost()
        instrumentation.runOnMainSync {
            originalAppearance = app.preferences.appearance.value
            app.preferences.updateAppearance { StudioAppearance(theme = ThemeMode.LIGHT) }
        }
    }
    @After fun close() {
        if (!::originalAppearance.isInitialized) return
        closeIsolatedActivities()
        instrumentation.runOnMainSync {
            fixtureHistory.forEach(app.preferences::removeHistory)
            app.preferences.updateAppearance { originalAppearance }
        }
    }

    @Test fun navigationAndThemeSurviveRecreation() {
        startIsolatedMainActivity()
        compose.onNodeWithTag("nav/settings").performClick()
        compose.waitForTag("studio/settings/category/system").performScrollTo().performClick()
        compose.waitForTag("studio/settings/appearance").performClick()
        compose.chooseStudioOption("studio/theme", app.getString(R.string.standard_dark))
        assertFalse(app.rootUiAllowed)
        compose.recreateStudioActivity()
        compose.waitForTag("studio/settings/page/appearance", displayed = true)
        compose.onNodeWithTag("studio/theme").assert(hasText(app.getString(R.string.standard_dark)))
        compose.chooseStudioOption("studio/theme", app.getString(R.string.standard_light))
        compose.onNodeWithTag("studio/theme").assert(hasText(app.getString(R.string.standard_light)))
        compose.chooseStudioOption("studio/theme", app.getString(R.string.standard_system))
        compose.onNodeWithTag("studio/theme").assert(hasText(app.getString(R.string.standard_system)))
        compose.onNodeWithTag("studio/settings/back").performClick()
        compose.waitForTag("studio/settings/page/system", displayed = true)
        compose.onNodeWithTag("studio/settings/back").performClick()
        compose.waitForTag("nav/player").performClick()
        compose.onNodeWithTag("nav/player").assertIsSelected()
    }

    @Test fun libraryScrollsAndFiltersManySongs() {
        startIsolatedMainActivity()
        val isolated = app as IsolatedTestApplication
        val songs = SongLibraryRepository(isolated.newTestDirectory("product-library"))
        val store = ViewModelStore()
        lateinit var model: AppViewModel
        try {
            instrumentation.runOnMainSync {
                val activity = resumedIsolatedActivity()
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AppViewModel(app.layouts, app.rootBackend, app.accessibilityBackend, app.backends,
                            songs, app.contentResolver, preferences = app.preferences, rootUiAllowed = false,
                            onlineLibrary = app.onlineLibrary) as T
                }
                model = ViewModelProvider(store, factory)[AppViewModel::class.java]
                activity.setContent {
                    val appearance by app.preferences.appearance.collectAsStateWithLifecycle()
                    DeltaTheme(appearance) { AppNavigation(model, activity) }
                }
            }
            compose.onNodeWithTag("nav/library").performClick()
            val emptyLibrary = app.getString(R.string.library_empty)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(emptyLibrary).fetchSemanticsNodes().isNotEmpty() }
            captureStudioFrame(compose, "library-empty-light")
            val activeSongId = populateAndCheckLibrary(songs)
            verifyLandscapePlayer(model)
            verifyTrackManagement(songs, activeSongId)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun populateAndCheckLibrary(songs: SongLibraryRepository): String {
        fun script(index: Int) = PerformanceScript(1, "profile", "fingerprint", "fixture-$index", 0,
            MonophonyPolicy.HIGHEST_NOTE, false, List(32) { event ->
                ScriptEvent(event * 630L, 260, 60, "NOTE_1", Region.NATURAL, false)
            })
        val titles = listOf("雨后的站台", "晚风沿着海岸", "窗边晨光", "九月来信", "未眠的海", "玻璃花房")
        val items = runBlocking {
            List(55) { index ->
                val score = script(index)
                val title = titles.getOrNull(54 - index) ?: "UI-fixture-${index.toString().padStart(2, '0')}"
                songs.add(title, SongSourceType.JSON, ScriptCodec.encode(score).toByteArray(), score, emptyList())
            }
        }
        instrumentation.runOnMainSync {
            items.takeLast(4).forEachIndexed { index, item ->
                app.preferences.recordSession(item.id, now = 1794997800000L + index)
                fixtureHistory += item.id
            }
        }
        compose.onNodeWithTag("library/list").performScrollToIndex(0)
        compose.onNodeWithText(app.getString(R.string.library_sort_recent)).assertIsDisplayed()
        captureStudioFrame(compose, "library-recent-light")
        compose.onNodeWithTag("library/list").performScrollToNode(hasText("UI-fixture-07"))
        compose.onNodeWithText("UI-fixture-07").assertIsDisplayed()
        compose.onNodeWithTag("library/list").performScrollToIndex(3)
        captureStudioFrame(compose, "library-list-light")
        compose.onNodeWithTag("library/open-search").performClick()
        compose.onNodeWithTag("library/search").performTextInput("雨后")
        compose.onNodeWithText("雨后的站台").assertExists()
        compose.onNodeWithText("UI-fixture-18").assertDoesNotExist()
        captureStudioFrame(compose, "library-search-light", includeSystemUi = true)
        compose.onNodeWithTag("library/actions/${items.last().id}").performClick()
        compose.onNodeWithText(app.getString(R.string.library_rename)).assertExists()
        captureStudioFrame(compose, "library-track-actions")
        compose.onNodeWithTag("library/action-open", useUnmergedTree = true).performClick()
        compose.waitForTag("player/current-note")
        compose.onNodeWithTag("nav/player").assertIsSelected()
        captureStudioFrame(compose, "player-light")

        compose.onNodeWithTag("player/collapse").performClick()
        compose.waitForTag("mini/open", displayed = true).assertExists()
        captureStudioFrame(compose, "library-mini-player")
        compose.onNodeWithTag("mini/open").performClick()
        compose.waitForTag("player/current-note")
        instrumentation.runOnMainSync { app.preferences.setTheme(ThemeMode.DARK) }
        captureStudioFrame(compose, "player-dark")
        compose.onNodeWithTag("nav/settings").performClick()
        compose.waitForTag("mini/open", displayed = true).assertExists()
        captureStudioFrame(compose, "settings-mini-player")
        return items.last().id
    }

    private fun verifyLandscapePlayer(model: AppViewModel) {
        instrumentation.runOnMainSync { resumedIsolatedActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            var landscape = false
            instrumentation.runOnMainSync {
                landscape = runCatching {
                    resumedIsolatedActivity().resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                }.getOrDefault(false)
            }
            landscape
        }
        instrumentation.runOnMainSync {
            val activity = resumedIsolatedActivity()
            activity.setContent {
                val appearance by app.preferences.appearance.collectAsStateWithLifecycle()
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * 1.30f)) {
                    DeltaTheme(appearance) { AppNavigation(model, activity) }
                }
            }
        }
        compose.waitForTag("nav/player", displayed = true).performClick()
        compose.waitForTag("player/start", displayed = true).assertExists()
        compose.onNodeWithText("雨后的站台").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.player_range_16)).assertIsDisplayed()
        captureStudioFrame(compose, "player-large-font-landscape")
        compose.waitForTag("player/collapse", displayed = true).performClick()
    }

    private fun verifyTrackManagement(songs: SongLibraryRepository, id: String) {
        val actions = "library/actions/$id"
        compose.onNodeWithTag("library/list").performScrollToNode(hasTestTag(actions))
        compose.waitForTag(actions, displayed = true).performClick()
        compose.onNodeWithText(app.getString(R.string.library_rename)).performClick()
        compose.onNodeWithTag("library/rename-input").performTextReplacement("雨后的站台（改名）")
        compose.onNodeWithText(app.getString(R.string.action_save)).performClick()
        compose.waitUntil(5000) { songs.items.value.any { it.id == id && it.displayName == "雨后的站台（改名）" } }
        compose.onNodeWithTag("library/list").performScrollToNode(hasTestTag(actions))
        compose.waitForTag(actions, displayed = true).performClick()
        compose.onNodeWithText(app.getString(R.string.library_delete_track)).performClick()
        assertEquals(55, songs.items.value.size)
        compose.onNodeWithText(app.getString(R.string.library_delete)).performClick()
        compose.waitUntil(5000) { songs.items.value.size == 54 }
        compose.onNodeWithTag("mini/player").assertDoesNotExist()
    }

    @Test fun playbackCurrentNoteCompletionAndFailureAreReadable() {
        startIsolatedMainActivity()
        instrumentation.runOnMainSync {
            resumedIsolatedActivity().setContent {
                DeltaTheme(ThemeMode.DARK) {
                    Column {
                        PlayerProgress(PlaybackProgress(8, 2, "NOTE_3", "正在演奏", PlaybackStatus.PLAYING), 8)
                        PlayerProgress(PlaybackProgress(8, 2, null, "已暂停", PlaybackStatus.PAUSED, elapsedMs = 3000, durationMs = 8000), 8)
                        PlayerProgress(PlaybackProgress(8, 8, null, "演奏已完成", PlaybackStatus.COMPLETED), 8)
                        PlayerProgress(PlaybackProgress(8, 2, null, "演奏异常", PlaybackStatus.FAILED), 8)
                    }
                }
            }
        }
        compose.onNodeWithText("NOTE_3").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.player_phase_completed)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.player_phase_failed)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.session_paused)).assertIsDisplayed()
        compose.onNodeWithText("0:03").assertIsDisplayed()
        compose.onAllNodesWithTag("player/progress").assertCountEquals(4)
    }
}
