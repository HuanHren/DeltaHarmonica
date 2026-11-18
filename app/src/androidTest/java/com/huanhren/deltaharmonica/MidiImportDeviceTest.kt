package com.huanhren.deltaharmonica

import android.net.Uri
import android.os.Bundle
import android.util.Base64

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanhren.deltaharmonica.data.ImportJournalState
import com.huanhren.deltaharmonica.data.ImportJournalStore
import com.huanhren.deltaharmonica.data.ImportPhase
import com.huanhren.deltaharmonica.data.SongLibraryRepository
import com.huanhren.deltaharmonica.data.SongSourceType
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.ReferenceDisplay
import com.huanhren.deltaharmonica.ui.AppViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MidiImportDeviceTest {
    @Test fun midiProviderToCommittedSongAndRepositoryReopen() {
        requireIsolatedTestHost()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as IsolatedTestApplication
        val provider = Uri.parse("content://${app.packageName}.importprovider")
        // Same synthetic 16-note fixture as duration16-test.mid in the JVM suite.
        val midi = Base64.decode(
            "TVRoZAAAAAYAAAABAfRNVHJrAAAApAD/UQMHoSAAkDxaeIA8AIZwkD5agiyAPgCFPJBAWoRYgEAAgxCQQVqCLIBBAIU8kENaeIBDAIZwkEVahFiARQCDEJBHWoIsgEcAhTyQSFp4gEgAhnCQPFp4gDwAhnCQPlqCLIA+AIU8kEBahFiAQACDEJBBWoIsgEEAhTyQQ1p4gEMAhnCQRVqEWIBFAIMQkEdagiyARwCFPJBIWniASAAA/y8A",
            Base64.DEFAULT,
        )
        val name = "duration16-test.mid"
        withIsolatedActivity {
            lateinit var model: AppViewModel
            try {
                app.contentResolver.call(provider, "configure", null, Bundle().apply {
                    putString("name", name)
                    putByteArray("bytes", midi)
                })
                instrumentation.runOnMainSync {
                    model = app.model
                    val uri = provider.buildUpon().appendPath("normal").build()
                    assertTrue(model.importDocument(uri))
                    val original = model.imports.state.value.taskId
                    assertFalse(model.importDocument(uri))
                    assertEquals(original, model.imports.state.value.taskId)
                }
                val cancelledId = model.imports.state.value.taskId
                instrumentation.runOnMainSync { model.imports.cancel() }
                app.contentResolver.call(provider, "release", null, null)
                runBlocking {
                    val state = withTimeout(20_000) { model.imports.state.first { !it.active } }
                    assertEquals(ImportPhase.CANCELLED, state.phase)
                    assertFalse(state.committed)
                }
                instrumentation.runOnMainSync {
                    assertTrue(model.importDocument(provider.buildUpon().appendPath("normal").build()))
                    assertNotEquals(cancelledId, model.imports.state.value.taskId)
                }
                app.contentResolver.call(provider, "release", null, null)
                runBlocking {
                    val state = withTimeout(20_000) { model.imports.state.first { !it.active } }
                    assertEquals(state.message, ImportPhase.READY, state.phase)
                }
                val layout = DefaultLayouts.create(1).copy(referenceDisplay = ReferenceDisplay(2670, 1200))
                instrumentation.runOnMainSync {
                    val ws = model.midiWorkspace
                    ws.candidateTrack = ws.parsed!!.tracks.indexOfFirst { track -> track.notes.isNotEmpty() }
                    assertTrue(ws.candidateTrack >= 0)
                    ws.candidatePolicy = MonophonyPolicy.HIGHEST_NOTE
                    assertTrue(model.convertMidi(layout))
                }
                runBlocking {
                    val state = withTimeout(20_000) { model.imports.state.first { !it.active } }
                    assertEquals(state.message, ImportPhase.COMMITTED, state.phase)
                    val id = requireNotNull(state.songId)
                    assertEquals(id, model.midiWorkspace.songId)
                    val root = app.runDirectory.resolve("song_library")
                    val reopened = SongLibraryRepository(root)
                    reopened.refresh()
                    reopened.refresh()
                    val item = reopened.items.value.single { it.originalFileName == name }
                    assertEquals(id, item.id)
                    assertEquals(SongSourceType.MIDI, item.sourceType)
                    assertArrayEquals(midi, reopened.readOriginal(id))
                    assertEquals(16, reopened.readScript(id).events.size)
                    val journal = ImportJournalStore(root)
                    assertEquals(ImportJournalState.COMMITTED, journal.ids().map(journal::read).single { it.songId == id }.state)
                }
            } finally {
                app.contentResolver.call(provider, "release", null, null)
            }
        }
    }
}
