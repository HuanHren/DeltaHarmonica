package com.huanhren.deltaharmonica.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.Region

class ImportJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun journalSurvivesNewStoreAndRejectsUnsupportedVersion() {
        val root = temporary.newFolder()
        val id = UUID.randomUUID().toString()
        val song = UUID.randomUUID().toString()
        val journal = ImportJournal(id, song, ImportJournalState.PREPARING, ".pending-$song", song, 1, 1, "a", "b")
        ImportJournalStore(root).write(journal)
        assertEquals(journal, ImportJournalStore(root).read(id))
        val file = root.resolve(".transactions").resolve("$id.json")
        file.writeText(file.readText().replace("\"schemaVersion\":1", "\"schemaVersion\":99"))
        assertThrows(IllegalArgumentException::class.java) { ImportJournalStore(root).read(id) }
        assertTrue(file.exists())
    }

    @Test fun newRepositoryRecoversPublishedDirectoryWithFilesReadyLog() = runBlocking {
        val root = temporary.newFolder()
        val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        val original = ScriptCodec.encode(script).toByteArray()
        val item = SongLibraryRepository(root).add("song.json",SongSourceType.JSON,original,script,emptyList())
        val id = UUID.randomUUID().toString()
        val entry = ImportJournal(id,item.id,ImportJournalState.FILES_READY,".pending-${item.id}",item.id,1,1,item.contentHash,item.scriptHash)
        ImportJournalStore(root).write(entry)
        val reopened = SongLibraryRepository(root)
        reopened.recoverPendingTransactions()
        assertEquals(ImportJournalState.COMMITTED,ImportJournalStore(root).read(id).state)
        assertEquals(script,reopened.readScript(item.id))
        reopened.recoverPendingTransactions()
        assertEquals(ImportJournalState.COMMITTED,ImportJournalStore(root).read(id).state)
    }

    @Test fun newRepositoryRollsBackPreparingAndPreservesCorruptJournal() = runBlocking {
        val root = temporary.newFolder()
        val id = UUID.randomUUID().toString(); val song = UUID.randomUUID().toString()
        val entry = ImportJournal(id,song,ImportJournalState.PREPARING,".pending-$song",song,1,1,"a","b")
        val store = ImportJournalStore(root); store.write(entry)
        val pending = root.resolve(entry.tempDirectory).apply { mkdir(); resolve("original").writeText("partial") }
        val broken = UUID.randomUUID().toString()
        val brokenFile = root.resolve(".transactions").resolve("$broken.json").apply { writeText("broken") }
        val reopened = SongLibraryRepository(root)
        reopened.recoverPendingTransactions()
        assertEquals(ImportJournalState.ROLLED_BACK, store.read(id).state)
        assertFalse(pending.exists())
        assertTrue(brokenFile.exists())
        assertTrue(reopened.problems.value.containsKey("transaction:$broken"))
        reopened.recoverPendingTransactions()
        assertEquals(ImportJournalState.ROLLED_BACK, store.read(id).state)
        assertTrue(brokenFile.exists())
    }

    @Test fun committedRecordWithCorruptArtifactIsReportedAndPreserved() = runBlocking {
        val root = temporary.newFolder()
        val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        val item = SongLibraryRepository(root).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
        val file = root.resolve(item.id).resolve("original")
        file.writeText("damaged")
        val reopened = SongLibraryRepository(root)
        reopened.recoverPendingTransactions()
        assertTrue(file.exists())
        assertTrue(reopened.problems.value.containsKey(item.id))
        assertNotNull(reopened.get(item.id))
    }

    @Test fun validPublishedSongAndOwnedResidualDirectoryRecoverWithoutDuplicates() = runBlocking {
        for (partial in listOf(false,true)) {
            val root = temporary.newFolder()
            val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
                listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
            val item = SongLibraryRepository(root).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
            val id = UUID.randomUUID().toString()
            val entry = ImportJournal(id,item.id,ImportJournalState.FILES_READY,".pending-${item.id}",item.id,1,1,item.contentHash,item.scriptHash)
            ImportJournalStore(root).write(entry)
            val temp = root.resolve(entry.tempDirectory).apply { mkdir() }
            root.resolve(item.id).resolve("original").copyTo(temp.resolve("original"))
            if (!partial) root.resolve(item.id).resolve("metadata.json").copyTo(temp.resolve("metadata.json"))
            repeat(2) {
                val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
                assertEquals(script,reopened.readScript(item.id))
                assertEquals(1,reopened.items.value.size)
                assertEquals(ImportJournalState.COMMITTED,ImportJournalStore(root).read(id).state)
                assertFalse(temp.exists())
            }
        }
    }

    @Test fun unknownResidualOrCorruptFinalIsPreservedForRetry() = runBlocking {
        for (corruptFinal in listOf(false,true)) {
            val root = temporary.newFolder()
            val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
                listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
            val item = SongLibraryRepository(root).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
            val id = UUID.randomUUID().toString()
            val entry = ImportJournal(id,item.id,ImportJournalState.FILES_READY,".pending-${item.id}",item.id,1,1,item.contentHash,item.scriptHash)
            ImportJournalStore(root).write(entry)
            val temp = root.resolve(entry.tempDirectory).apply { mkdir(); resolve("unknown").writeText("preserve") }
            if (corruptFinal) root.resolve(item.id).resolve("original").writeText("damaged")
            repeat(2) {
                val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
                assertTrue(reopened.problems.value.containsKey("transaction:$id"))
                assertTrue(temp.resolve("unknown").exists())
                assertTrue(root.resolve(item.id).exists())
                assertEquals(ImportJournalState.FILES_READY,ImportJournalStore(root).read(id).state)
            }
        }
    }

    @Test fun corruptFinalWithCompleteOrCorruptPendingIsNeverOverwritten() = runBlocking {
        for (corruptPending in listOf(false,true)) {
            val root = temporary.newFolder()
            val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
                listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
            val item = SongLibraryRepository(root).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
            val id = UUID.randomUUID().toString()
            val entry = ImportJournal(id,item.id,ImportJournalState.FILES_READY,".pending-${item.id}",item.id,1,1,item.contentHash,item.scriptHash)
            ImportJournalStore(root).write(entry)
            val temp = root.resolve(entry.tempDirectory).apply { mkdir() }
            root.resolve(item.id).resolve("original").copyTo(temp.resolve("original"))
            root.resolve(item.id).resolve("metadata.json").copyTo(temp.resolve("metadata.json"))
            root.resolve(item.id).resolve("original").writeText("damaged-final")
            if (corruptPending) temp.resolve("original").writeText("damaged-pending")
            repeat(2) {
                val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
                assertTrue(reopened.problems.value.containsKey("transaction:$id"))
                assertEquals("damaged-final",root.resolve(item.id).resolve("original").readText())
                assertTrue(temp.resolve("original").exists())
                assertEquals(ImportJournalState.FILES_READY,ImportJournalStore(root).read(id).state)
                assertThrows(Exception::class.java) { runBlocking { reopened.readScript(item.id) } }
            }
        }
    }
}
