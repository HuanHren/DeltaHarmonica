package com.huanhren.deltaharmonica

import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanhren.deltaharmonica.data.*
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.playback.*
import com.huanhren.deltaharmonica.ui.*
import com.huanhren.deltaharmonica.ui.theme.DeltaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class WorkspaceProtectionDeviceTest {
    @Before fun checkIsolatedHost() = requireIsolatedTestHost()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as IsolatedTestApplication
    private val provider get() = Uri.parse("content://${instrumentation.targetContext.packageName}.importprovider")

    @Test fun failedImportPreservesFullWorkspaceThenSuccessfulImportReplacesIt() {
        val root = app.newTestDirectory("workspace_library")
        val failMetadata = AtomicBoolean(false)
        val storage = object : ImportFileOperations by RealImportFileOperations {
            override fun writeFile(file: File, bytes: ByteArray) {
                if (file.name == "metadata.json" && failMetadata.getAndSet(false)) throw IOException("injected metadata write")
                RealImportFileOperations.writeFile(file, bytes)
            }
        }
        val songs=SongLibraryRepository(root, storage)
        val layout=DefaultLayouts.create(1).copy(referenceDisplay=ReferenceDisplay(2670,1200))
        val profile=requireNotNull(layout.profile)
        val a=PerformanceScript(2,profile.id,ScriptConverter.fingerprint(profile,layout),"a.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,100,62,"NOTE_2",Region.NATURAL,false)),provenance=ScriptProvenance("CLEAN",emptyList(),0,0))
        val b=a.copy(sourceMidiName="b.json",events=listOf(ScriptEvent(0,160,62,"NOTE_2",Region.NATURAL,false)))
        val itemA=runBlocking { songs.add("a.json",SongSourceType.JSON,ScriptCodec.encode(a).toByteArray(),a,emptyList()) }
        val originalHash=RealImportFileOperations.sha256(runBlocking { songs.readOriginal(itemA.id) })
        val oldProgress=PlaybackPreparation.progress.value
        val oldTimings=PlaybackPreparation.actualTimings.toMap()
        val oldPending=PlaybackPreparation.pending
        check(PlaybackPreparation.activeRunner == null && PlaybackPreparation.cancelPendingStart == null)
        val store=ViewModelStore()
        lateinit var model: AppViewModel
        lateinit var activity: MainActivity
        startIsolatedMainActivity()
        try {
            instrumentation.runOnMainSync {
                activity=resumedIsolatedActivity()
                val factory=object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T =
                        AppViewModel(app.layouts,app.rootBackend,app.accessibilityBackend,app.backends,songs,app.contentResolver,
                            rootUiAllowed = false, onlineLibrary = app.onlineLibrary) as T
                }
                model=ViewModelProvider(store,factory)[AppViewModel::class.java]
                activity.setContent { DeltaTheme { AppNavigation(model,activity) } }
            }
            runBlocking { loadLibrarySong(songs,itemA.id,model.midiWorkspace) }
            val plan=PlaybackPlanner.plan(a,layout,Region.NATURAL,false,0,1,1.0)
            assertTrue(plan.fatalErrors.toString(),plan.fatalErrors.isEmpty())
            val progress=PlaybackProgress(4,2,state="acceptance",phase=PlaybackStatus.COMPLETED)
            val timing=ActualTiming(11,22,3,100,"acceptance")
            instrumentation.runOnMainSync {
                model.midiWorkspace.dryRun=plan; model.midiWorkspace.speed=2.0; model.midiWorkspace.transpose=3
                model.midiWorkspace.candidateTrack=0; model.midiWorkspace.candidatePolicy=MonophonyPolicy.LOWEST_NOTE; model.midiWorkspace.candidateTranspose=2
                PlaybackPreparation.progress.value=progress; PlaybackPreparation.actualTimings.clear(); PlaybackPreparation.actualTimings[0]=timing
            }
            compose.onNodeWithTag("nav/player").performClick()
            compose.onNodeWithText("a.json").assertExists()
            compose.waitForTag("nav/library").performClick()
            compose.waitForTag("library/import").performClick()
            fun deliver(name: String, bytes: ByteArray, path: String = "normal") {
                app.contentResolver.call(provider,"configure",null,Bundle().apply { putString("name",name); putByteArray("bytes",bytes) })
                app.contentResolver.call(provider,"release",null,null)
                instrumentation.runOnMainSync { assertTrue(model.importDocument(provider.buildUpon().appendPath(path).build())) }
                compose.waitUntil(20_000) { !model.imports.state.value.active }
            }
            val ws=model.midiWorkspace
            fun assertOldWorkspace(expectedProgress: PlaybackProgress = progress, count: Int = 1) {
                assertEquals(count, songs.items.value.size)
                assertEquals(itemA.id,ws.songId); assertEquals("a.json",ws.name); assertEquals(a,ws.report!!.script)
                assertEquals(plan,ws.dryRun); assertEquals(expectedProgress,PlaybackPreparation.progress.value)
                assertEquals(mapOf(0 to timing),PlaybackPreparation.actualTimings)
                assertEquals(2.0,ws.speed,0.0); assertEquals(3,ws.transpose); assertEquals(0,ws.candidateTrack)
                assertEquals(MonophonyPolicy.LOWEST_NOTE,ws.candidatePolicy); assertEquals(2,ws.candidateTranspose)
                assertEquals(originalHash,RealImportFileOperations.sha256(runBlocking { songs.readOriginal(itemA.id) }))
            }
            val valid = ScriptCodec.encode(b)
            for ((name, invalid) in listOf(
                "broken.json" to "{\"schemaVersion\":",
                "schema.json" to valid.replace("\"schemaVersion\": 2", "\"schemaVersion\": 99"),
                "event.json" to valid.replace("\"durationMs\": 160", "\"durationMs\": 0"),
            )) {
                deliver(name,invalid.toByteArray())
                assertEquals(ImportPhase.FAILED,model.imports.state.value.phase)
                compose.onNodeWithText(app.getString(R.string.import_detail_failed, app.getString(R.string.import_error_json))).assertExists()
                assertOldWorkspace()
            }
            deliver("read.json", valid.toByteArray(), "io")
            assertEquals(ImportPhase.FAILED,model.imports.state.value.phase)
            assertFalse(model.imports.state.value.committed)
            assertOldWorkspace()
            failMetadata.set(true)
            deliver("metadata.json",valid.toByteArray())
            assertEquals(ImportPhase.FAILED,model.imports.state.value.phase)
            assertFalse(model.imports.state.value.committed)
            assertTrue(model.imports.state.value.message, model.imports.state.value.message.contains("injected metadata write"))
            assertOldWorkspace()
            val journal = ImportJournalStore(root)
            val pending = journal.ids().map(journal::read).single { it.state == ImportJournalState.PREPARING }
            assertFalse(root.resolve(pending.tempDirectory).exists())
            assertFalse(root.resolve(pending.finalDirectory).exists())
            val reopened = SongLibraryRepository(root)
            repeat(2) {
                runBlocking { reopened.refresh() }
                assertEquals(ImportJournalState.ROLLED_BACK, journal.read(pending.transactionId).state)
                assertEquals(itemA.id, reopened.items.value.single().id)
                assertOldWorkspace()
            }

            val blocked = b.copy(sourceMidiName = "saved-while-busy.json")
            val busy = progress.copy(phase = PlaybackStatus.PLAYING)
            instrumentation.runOnMainSync { PlaybackPreparation.progress.value = busy }
            deliver("saved-while-busy.json", ScriptCodec.encode(blocked).toByteArray())
            assertEquals(ImportPhase.FAILED,model.imports.state.value.phase)
            assertTrue(model.imports.state.value.committed)
            compose.onNodeWithText(app.getString(R.string.import_detail_failed_saved, app.getString(R.string.music_error_playback_busy))).assertExists()
            val blockedId = requireNotNull(model.imports.state.value.songId)
            assertOldWorkspace(busy, count = 2)
            assertEquals(ImportJournalState.COMMITTED, journal.ids().map(journal::read).single { it.songId == blockedId }.state)
            assertEquals(blocked,runBlocking { songs.readScript(blockedId) })
            instrumentation.runOnMainSync { PlaybackPreparation.progress.value = progress }

            deliver("b.json",ScriptCodec.encode(b).toByteArray())
            assertEquals(ImportPhase.COMMITTED,model.imports.state.value.phase); assertTrue(model.imports.state.value.committed)
            val idB=requireNotNull(model.imports.state.value.songId)
            assertEquals(idB,ws.songId); assertEquals(b,ws.report!!.script); assertEquals(3,songs.items.value.size)
            assertNotNull(runBlocking { songs.get(itemA.id) }); assertEquals(b,runBlocking { songs.readScript(idB) })
            assertTrue(PlaybackPreparation.actualTimings.isEmpty()); assertEquals(PlaybackProgress(),PlaybackPreparation.progress.value)
            compose.onNodeWithText(app.getString(R.string.import_open_player)).performScrollTo().performClick()
            compose.onNodeWithText("b.json").assertExists()
        } finally {
            instrumentation.runOnMainSync {
                store.clear()
                PlaybackPreparation.progress.value=oldProgress; PlaybackPreparation.actualTimings.clear(); PlaybackPreparation.actualTimings.putAll(oldTimings); PlaybackPreparation.pending=oldPending
            }
            closeIsolatedActivities()
            // Keep this run's data for audit. No filesystem deletion is performed here.
        }
    }
}
