package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.Region
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.huanhren.deltaharmonica.ui.MidiWorkspace
import com.huanhren.deltaharmonica.ui.loadLibrarySong
import com.huanhren.deltaharmonica.ui.removeLibrarySong
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.playback.PlaybackProgress
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import com.huanhren.deltaharmonica.playback.PlaybackPlanner
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.ReferenceDisplay

class SongLibraryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun script() = PerformanceScript(1, "profile", "fingerprint", "song.mid", 0,
        MonophonyPolicy.HIGHEST_NOTE, false,
        listOf(ScriptEvent(0, 120, 60, "NOTE_1", Region.NATURAL, false)))

    @Test fun savesAndRestoresOriginalAndScriptWithStableId() = runBlocking {
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val original = ScriptCodec.encode(script()).toByteArray()
        val saved = repository.add("song.json", SongSourceType.JSON, original, script(), listOf("warning"))
        val reopened = SongLibraryRepository(directory)
        val item = reopened.get(saved.id)!!
        assertEquals(listOf("warning"), item.warnings)
        assertArrayEquals(original, reopened.readOriginal(item.id))
        assertEquals(script(), reopened.readScript(item.id))
        assertEquals(saved.id, reopened.rename(item.id, "New name").id)
        assertEquals("New name", SongLibraryRepository(directory).get(item.id)!!.displayName)
    }

    @Test fun corruptedArtifactIsRejectedAndDeleteIsConfinedToSong() = runBlocking {
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val item = repository.add("song.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList())
        directory.resolve(item.id).resolve("original").writeBytes(byteArrayOf(2))
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.readOriginal(item.id) } }
        assertTrue(repository.delete(item.id))
        assertNull(repository.get(item.id))
    }

    @Test fun jsonOriginalIsAlsoPlaybackArtifact() = runBlocking {
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val original = ScriptCodec.encode(script()).toByteArray()
        val saved = repository.add("song.json", SongSourceType.JSON, original, script(), emptyList())
        assertEquals(saved.originalArtifact, saved.playbackScriptArtifact)
        assertFalse(directory.resolve(saved.id).resolve("script.json").exists())
        assertEquals(script(), SongLibraryRepository(directory).readScript(saved.id))
        assertTrue(repository.delete(saved.id))
    }

    @Test fun stagedMidiIsCopiedOnlyAfterSuccessfulConversion() = runBlocking {
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val midi = javaClass.getResourceAsStream("/duration16-test.mid")!!.readBytes()
        val parsed = MidiParser.parse(midi)
        val converted = ScriptConverter.convert(parsed, "sample.mid", 0, MonophonyPolicy.HIGHEST_NOTE, 0,
            com.huanhren.deltaharmonica.model.DefaultProfiles.delta(), null)
        val staged = repository.stageMidi(midi)
        val item = repository.addStagedMidi("sample.mid", staged, converted.script, converted.warnings)
        assertArrayEquals(midi, repository.readOriginal(item.id))
        assertEquals(converted.script, repository.readScript(item.id))
        assertFalse(directory.resolve(staged).exists())
        assertNotEquals(item.originalArtifact, item.playbackScriptArtifact)
    }

    @Test fun failedStagedImportRemovesOnlyItsCandidate() = runBlocking {
        val root = temporary.newFolder()
        val repository = SongLibraryRepository(root)
        val midi = javaClass.getResourceAsStream("/duration16-test.mid")!!.readBytes()
        val token = repository.stageMidi(midi)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addStagedMidi("sample.mid", token, script().copy(events=emptyList()), emptyList()) }
        }
        assertFalse(root.resolve(token).exists())
        repository.refresh()
        assertTrue(repository.items.value.isEmpty())
    }

    @Test fun failedLoadKeepsCurrentScriptAndPlaybackProgress() = runBlocking {
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val saved = repository.add("song.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList())
        val workspace = MidiWorkspace().apply { report = ConversionReport(script(), emptyList(), 0) }
        PlaybackPreparation.progress.value = PlaybackProgress(total = 3, completed = 2)
        directory.resolve(saved.id).resolve("original").writeText("broken")
        assertThrows(Exception::class.java) { runBlocking { loadLibrarySong(repository, saved.id, workspace) } }
        assertEquals(script(), workspace.report!!.script)
        assertEquals(2, PlaybackPreparation.progress.value.completed)
    }

    @Test fun refreshReportsMissingArtifactWithoutHidingRecord() = runBlocking {
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val item = repository.add("song.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList())
        directory.resolve(item.id).resolve("original").delete()
        repository.refresh()
        assertEquals(item.id, repository.items.value.single().id)
        assertTrue(repository.problems.value.containsKey(item.id))
    }

    @Test fun successfulLoadClearsHistoryButActiveSessionCannotBeReplaced() = runBlocking {
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val item = repository.add("song.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList())
        val workspace = MidiWorkspace()
        PlaybackPreparation.progress.value = PlaybackProgress(total=3, completed=2, phase=PlaybackStatus.PLAYING)
        assertThrows(IllegalStateException::class.java) { runBlocking { loadLibrarySong(repository,item.id,workspace) } }
        assertNull(workspace.report)
        assertEquals(2, PlaybackPreparation.progress.value.completed)
        PlaybackPreparation.progress.value = PlaybackProgress(total=3, completed=2, phase=PlaybackStatus.COMPLETED)
        loadLibrarySong(repository,item.id,workspace)
        assertEquals(item.id,workspace.songId)
        assertEquals(0,PlaybackPreparation.progress.value.completed)
    }

    @Test fun cleanupOnlyRemovesOldOwnedCandidates() = runBlocking {
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val token = repository.stageMidi(javaClass.getResourceAsStream("/duration16-test.mid")!!.readBytes())
        val candidate = directory.resolve(token)
        candidate.setLastModified(1)
        val unknown = directory.resolve(".candidate-not-ours").apply { writeText("keep"); setLastModified(1) }
        repository.cleanupExpiredCandidates(System.currentTimeMillis())
        assertFalse(candidate.exists())
        assertTrue(unknown.exists())
    }

    @Test fun cleanupRecoversExpiredPendingDirectoriesButPreservesUnknownContent() = runBlocking {
        val root = temporary.newFolder()
        val old = root.resolve(".pending-${java.util.UUID.randomUUID()}").apply { mkdir(); resolve("original").writeText("partial"); setLastModified(1) }
        val unknown = root.resolve(".pending-${java.util.UUID.randomUUID()}").apply { mkdir(); resolve("other").writeText("keep"); setLastModified(1) }
        SongLibraryRepository(root).cleanupExpiredCandidates(System.currentTimeMillis())
        assertFalse(old.exists())
        assertTrue(unknown.exists())
    }

    @Test fun damagedMetadataIsReportedRatherThanSilentlyIgnored() = runBlocking {
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val item = repository.add("song.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList())
        directory.resolve(item.id).resolve("metadata.json").writeText("broken")
        repository.refresh()
        assertTrue(repository.problems.value.containsKey(item.id))
    }

    @Test fun deletingCurrentSongClearsLoadedScriptOnlyAfterDeletion() = runBlocking {
        val repository = SongLibraryRepository(temporary.newFolder())
        val item = repository.add("song.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList())
        val workspace = MidiWorkspace()
        PlaybackPreparation.progress.value = PlaybackProgress()
        loadLibrarySong(repository,item.id,workspace)
        removeLibrarySong(repository,item.id,workspace)
        assertNull(workspace.songId)
        assertNull(workspace.report)
        assertNull(repository.get(item.id))
    }

    @Test fun duplicateAndInvalidJsonDoNotCreateNewRecords() = runBlocking {
        val repository = SongLibraryRepository(temporary.newFolder())
        val bytes = ScriptCodec.encode(script()).toByteArray()
        repository.add("first.json", SongSourceType.JSON, bytes, script(), emptyList())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.add("again.json", SongSourceType.JSON, bytes, script(), emptyList()) } }
        assertThrows(Exception::class.java) { runBlocking { repository.add("broken.json", SongSourceType.JSON, "{}".toByteArray(), script(), emptyList()) } }
        repository.refresh()
        assertEquals(1, repository.items.value.size)
    }

    @Test fun invalidJsonImportHasReadableError() {
        val failure = assertThrows(IllegalArgumentException::class.java) { ScriptCodec.decodeImport("{\"schemaVersion\":999}") }
        assertEquals("JSON 脚本无效或版本不受支持", failure.message)
    }

    @Test fun recoveredScriptRetainsTimingAndWarningsForPlanner() = runBlocking {
        val layout = DefaultLayouts.create(1).copy(referenceDisplay = ReferenceDisplay(2670,1200))
        val source = PerformanceScript(2, layout.profile!!.id, ScriptConverter.fingerprint(layout.profile!!,layout),
            "test.mid", 0, MonophonyPolicy.HIGHEST_NOTE, false,
            listOf(ScriptEvent(0,100,62,"NOTE_2",Region.NATURAL,false), ScriptEvent(500,200,62,"NOTE_2",Region.NATURAL,false)),
            provenance=ScriptProvenance("RECOVERED",listOf("轨道被截断"),50,0))
        val directory = temporary.newFolder()
        val repository = SongLibraryRepository(directory)
        val bytes = ScriptCodec.encode(source).toByteArray()
        val item = repository.add("test.json", SongSourceType.JSON, bytes, source, source.provenance!!.warnings)
        val loaded = SongLibraryRepository(directory).readScript(item.id)
        assertEquals(source.events, loaded.events)
        val plan = PlaybackPlanner.plan(loaded,layout,Region.NATURAL,false,0,2,1.0)
        assertTrue(plan.fatalErrors.isEmpty())
        assertTrue(plan.warnings.any { it.contains("轨道被截断") })
        assertEquals(200L, plan.events.last().event.durationMs)
    }

    @Test fun failedWriteDoesNotReplaceLoadedSongOrItsProgress() = runBlocking {
        val blockedRoot = temporary.newFile()
        val repository = SongLibraryRepository(blockedRoot)
        val workspace = MidiWorkspace().apply { report = ConversionReport(script(), listOf("old"), 0) }
        PlaybackPreparation.progress.value = PlaybackProgress(total=5,completed=3,phase=PlaybackStatus.COMPLETED)
        assertThrows(Exception::class.java) {
            runBlocking { repository.add("new.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList()) }
        }
        assertEquals(script(), workspace.report!!.script)
        assertEquals(3, PlaybackPreparation.progress.value.completed)
        assertEquals("old", workspace.report!!.warnings.single())
        assertTrue(blockedRoot.isFile)
    }

    @Test fun deletionRejectsPathTraversalAndPreservesUnknownFiles() = runBlocking {
        val root = temporary.newFolder()
        val repository = SongLibraryRepository(root)
        val item = repository.add("test.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList())
        val unknown = root.resolve(item.id).resolve("unknown").apply { writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.delete("../outside") } }
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.delete(item.id) } }
        assertTrue(unknown.exists())
        assertNotNull(repository.get(item.id))
    }

    @Test fun activeSessionPreventsDeletingCurrentSong() = runBlocking {
        val repository = SongLibraryRepository(temporary.newFolder())
        val item = repository.add("song.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList())
        val workspace = MidiWorkspace()
        PlaybackPreparation.progress.value = PlaybackProgress()
        loadLibrarySong(repository,item.id,workspace)
        PlaybackPreparation.progress.value = PlaybackProgress(total=1,phase=PlaybackStatus.PLAYING)
        assertThrows(IllegalStateException::class.java) { runBlocking { removeLibrarySong(repository,item.id,workspace) } }
        assertNotNull(repository.get(item.id))
        assertEquals(item.id, workspace.songId)
        PlaybackPreparation.progress.value = PlaybackProgress()
    }

    @Test fun concurrentRenameAndReadKeepTheSameValidSong() = runBlocking {
        val repository = SongLibraryRepository(temporary.newFolder())
        val item = repository.add("song.json", SongSourceType.JSON, ScriptCodec.encode(script()).toByteArray(), script(), emptyList())
        (0 until 12).map { index -> async(Dispatchers.Default) {
            if (index % 2 == 0) repository.rename(item.id,"name-$index") else assertEquals(script(), repository.readScript(item.id))
        } }.awaitAll()
        assertEquals(item.id, repository.get(item.id)!!.id)
        assertEquals(script(), repository.readScript(item.id))
    }

    @Test fun multitrackMidiConversionSettingsAndOriginalSurviveRestart() = runBlocking {
        val bytes = listOf(77,84,104,100,0,0,0,6,0,1,0,2,0,96,
            77,84,114,107,0,0,0,18,0,255,81,3,7,161,32,96,255,81,3,15,66,64,0,255,47,0,
            77,84,114,107,0,0,0,13,0,144,60,100,0x81,0x40,144,60,0,0,255,47,0).map { it.toByte() }.toByteArray()
        val midi = MidiParser.parse(bytes)
        val converted = ScriptConverter.convert(midi,"multi.mid",1,MonophonyPolicy.LOWEST_NOTE,2,DefaultLayouts.create().profile!!,null)
        val root = temporary.newFolder(); val repository = SongLibraryRepository(root)
        val item = repository.addStagedMidi("multi.mid",repository.stageMidi(bytes),converted.script,converted.warnings)
        val reopened = SongLibraryRepository(root); val saved = reopened.get(item.id)!!
        assertEquals(1,saved.sourceTrack); assertEquals("LOWEST_NOTE",saved.monophonyPolicy); assertEquals(2,saved.transpose)
        assertArrayEquals(bytes,reopened.readOriginal(item.id))
        assertEquals(converted.script,reopened.readScript(item.id))
    }

    @Test fun midiRecoveryWarningsSurviveConversionAndRestart() = runBlocking {
        val bytes = listOf(77,84,104,100,0,0,0,6,0,0,0,1,0,96,77,84,114,107,0,0,0,16,
            0,144,60,100,96,128,60,0,0,255,47,0).map { it.toByte() }.toByteArray()
        val midi = MidiParser.parse(bytes)
        assertTrue(midi.requiresConfirmation)
        val converted = ScriptConverter.convert(midi,"recovered.mid",0,MonophonyPolicy.HIGHEST_NOTE,0,DefaultLayouts.create().profile!!,null)
        val root = temporary.newFolder(); val repository = SongLibraryRepository(root)
        val item = repository.addStagedMidi("recovered.mid",repository.stageMidi(bytes),converted.script,converted.warnings)
        assertEquals(converted.warnings,SongLibraryRepository(root).get(item.id)!!.warnings)
        assertEquals("RECOVERED",SongLibraryRepository(root).readScript(item.id).provenance!!.importStatus)
    }
}
