package com.huanhren.deltaharmonica.ui

import com.huanhren.deltaharmonica.data.SongLibraryRepository
import com.huanhren.deltaharmonica.data.SongSourceType
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.playback.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceImportProtectionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun failedActivationOfCommittedSongKeepsOldWorkspacePlanAndStatistics() = runBlocking {
        val root = temporary.newFolder(); val repository = SongLibraryRepository(root)
        val old = PerformanceScript(1,"profile","fingerprint","old.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        val next = old.copy(sourceMidiName="new.json")
        val first = repository.add("old.json",SongSourceType.JSON,ScriptCodec.encode(old).toByteArray(),old,emptyList())
        val workspace = MidiWorkspace(); loadLibrarySong(repository,first.id,workspace)
        val plan = PlaybackPlan(emptyList(),0,0,120,emptyList(),emptyList(),150,100)
        workspace.dryRun = plan; workspace.speed = 2.0; workspace.transpose = 3
        val progress = PlaybackProgress(4,2,state="old completed",phase=PlaybackStatus.COMPLETED)
        val timing = ActualTiming(11,22,3,120,"old")
        PlaybackPreparation.progress.value = progress; PlaybackPreparation.actualTimings[0] = timing
        try {
            val second = repository.add("new.json",SongSourceType.JSON,ScriptCodec.encode(next).toByteArray(),next,emptyList())
            root.resolve(second.id).resolve("original").writeText("corrupt")
            assertThrows(Exception::class.java) { runBlocking { loadLibrarySong(repository,second.id,workspace) } }
            assertEquals(first.id,workspace.songId); assertEquals(old,workspace.report?.script)
            assertEquals(plan,workspace.dryRun); assertEquals(2.0,workspace.speed,0.0); assertEquals(3,workspace.transpose)
            assertEquals(progress,PlaybackPreparation.progress.value); assertEquals(timing,PlaybackPreparation.actualTimings[0])
            assertNotNull(repository.get(second.id)); assertTrue(root.resolve(second.id).isDirectory)
            assertEquals(old,repository.readScript(first.id))
        } finally {
            PlaybackPreparation.actualTimings.clear(); PlaybackPreparation.progress.value = PlaybackProgress()
        }
    }
}
