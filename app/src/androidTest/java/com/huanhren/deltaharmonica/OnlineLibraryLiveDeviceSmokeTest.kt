package com.huanhren.deltaharmonica

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanhren.deltaharmonica.input.TapResult
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.ReferenceDisplay
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.online.OnlineLibraryConfig
import com.huanhren.deltaharmonica.online.OnlineLibraryRepository
import com.huanhren.deltaharmonica.playback.PlaybackPlanner
import com.huanhren.deltaharmonica.playback.PlaybackRunner
import com.huanhren.deltaharmonica.ui.AppViewModel
import com.huanhren.deltaharmonica.overlay.GameMusicCenter
import com.huanhren.deltaharmonica.overlay.MusicCenterPreferences
import com.huanhren.deltaharmonica.overlay.OverlayStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnlineLibraryLiveDeviceSmokeTest {
    @Test(timeout = 90_000)
    fun publicScoreDownloadsPlansAndStartsWithAnUnavailableEndpointAfterCacheClear() = runBlocking {
        requireIsolatedTestHost()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveOnlineLibrary") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedTestApplication
        val layout = DefaultLayouts.create().copy(referenceDisplay = ReferenceDisplay(2400, 1080))
        val catalog = app.onlineLibrary.catalog()
        assertFalse(catalog.stale)
        assertTrue(catalog.entries.isNotEmpty())
        val entry = catalog.search("送别").first { it.id == "4wUpQ4JsP4Cj" }
        val model = withContext(Dispatchers.Main) { app.model }
        val downloaded = withContext(Dispatchers.Main) {
            model.prepareOnlineSong(entry, null, layout, play = false, onStage = {}, onProgress = { _, _ -> })
        }
        assertEquals(entry.id, downloaded.online?.remoteId)
        assertEquals(entry.noteCount, downloaded.online?.noteCount)
        assertTrue(downloaded.eventCount > 0)
        app.onlineLibrary.clearCache()

        val unavailable = OnlineLibraryRepository(app.newTestDirectory("online_unavailable"),
            OnlineLibraryConfig(baseUrl = "https://127.0.0.1:1/harmonica", connectTimeoutMs = 100,
                readTimeoutMs = 100, retryDelayMs = 0), System::currentTimeMillis)
        val script = withContext(Dispatchers.Main) {
            val offline = AppViewModel(app.layouts, app.rootBackend, app.accessibilityBackend, app.backends,
                app.songs, app.contentResolver, rootUiAllowed = false, onlineLibrary = unavailable)
            val reopened = offline.prepareOnlineSong(entry, null, layout, play = true,
                onStage = {}, onProgress = { _, _ -> })
            assertEquals(downloaded.id, reopened.id)
            requireNotNull(offline.midiWorkspace.report).script
        }
        assertEquals(0, script.provenance?.unmappedCount)
        val count = minOf(8, script.events.size)
        val plan = PlaybackPlanner.plan(script, layout, Region.NATURAL, false, 0, count, 2.0)
        assertEquals(plan.fatalErrors.toString(), emptyList<String>(), plan.fatalErrors)
        val runner = PlaybackRunner()
        val outcome = runner.run(plan, hide = {}, awaitWindows = {}, valid = { true },
            dispatch = { _, _ -> TapResult.Completed }, restore = {},
            onEvent = { _, _, completed -> if (completed) runner.requestStop() },
            dryRunCompleted = true, warningsConfirmed = true)
        assertTrue(outcome.stopped)
        assertEquals(1, outcome.completed)
        assertNull(outcome.failure)
    }

    @Test(timeout = 90_000)
    fun nativeMusicCenterSupportsLiveSearchDetailsFavoritesAndPlaybackMode() = withIsolatedActivity {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveOnlineLibrary") == "true")
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedTestApplication
            val favoriteStore = withContext(Dispatchers.Main) { MusicCenterPreferences(app) }
            val previous = favoriteStore.favorites["4wUpQ4JsP4Cj"]
            val center = withContext(Dispatchers.Main) {
                val activity = resumedIsolatedActivity()
                GameMusicCenter(OverlayStyle(activity, app.preferences.appearance.value), app.model, CoroutineScope(this@runBlocking.coroutineContext + Dispatchers.Main.immediate),
                    favoriteStore, { DefaultLayouts.create() }, {}, {}).also {
                    activity.setContentView(it.view)
                    it.show(duringPlayback = false)
                }
            }
            suspend fun waitFor(message: String, condition: () -> Boolean) {
                val end = android.os.SystemClock.elapsedRealtime() + 35_000
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    if (withContext(Dispatchers.Main) { condition() }) return
                    delay(40)
                }
                error(message)
            }
            fun all(view: View): Sequence<View> = sequence {
                yield(view)
                if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(all(view.getChildAt(index)))
            }
            try {
                withContext(Dispatchers.Main) {
                    all(center.view).filterIsInstance<EditText>().single().setText("送别")
                }
                fun target(): Int {
                    val list = all(center.view).filterIsInstance<ListView>().single()
                    return (0 until list.count).firstOrNull {
                        val row = list.adapter.getView(it, null, list) as TextView
                        row.text.contains("送别（李叔同）口琴二重奏")
                    } ?: -1
                }
                waitFor("Music center did not find the real score") { target() >= 0 }
                withContext(Dispatchers.Main) {
                    val list = all(center.view).filterIsInstance<ListView>().single()
                    val index = target()
                    val row = list.adapter.getView(index, null, list)
                    assertTrue(list.performItemClick(row, index, list.adapter.getItemId(index)))
                }
                waitFor("Music center did not display the selected details") {
                    all(center.view).filterIsInstance<TextView>().any { it.text.contains("159") && it.text.contains("BPM") }
                }
                withContext(Dispatchers.Main) {
                    val label = center.view.context.getString(if (previous == null) R.string.game_music_favorite else R.string.game_music_unfavorite)
                    all(center.view).filterIsInstance<Button>().first { it.text == label }.performClick()
                    val restored = MusicCenterPreferences(app)
                    assertEquals(previous == null, restored.favorites.containsKey("4wUpQ4JsP4Cj"))
                    assertTrue(restored.searches.contains("送别"))
                    center.show(duringPlayback = true)
                    val input = all(center.view).filterIsInstance<EditText>().single()
                    assertEquals(View.GONE, input.visibility)
                    val playLabels = listOf(R.string.online_play, R.string.online_download_play).map(center.view.context::getString)
                    assertTrue(all(center.view).filterIsInstance<Button>().none { it.text in playLabels && it.isEnabled })
                    val back = center.view.context.getString(R.string.action_back)
                    all(center.view).filterIsInstance<Button>().first { it.text == back }.performClick()
                    assertEquals(View.GONE, input.visibility)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    val updated = favoriteStore.favorites["4wUpQ4JsP4Cj"]
                    if (previous == null && updated != null) favoriteStore.toggleFavorite(updated)
                    else if (previous != null && updated == null) favoriteStore.toggleFavorite(previous)
                    center.dispose()
                }
            }
        }
    }

}
