package com.huanhren.deltaharmonica

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
import kotlinx.coroutines.Dispatchers
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
}
