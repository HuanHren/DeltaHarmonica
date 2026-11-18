package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.Region
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImportFileOperationsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualJournalOutputSyncAndCloseFailuresLeaveNoCommittedRecord() = runBlocking {
        for (stage in listOf("sync", "close")) {
            val root = temporary.newFolder()
            val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
                listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun openJournalOutput(file: File): ImportSyncedOutput {
                    val actual = RealImportFileOperations.openJournalOutput(file)
                    return object : ImportSyncedOutput {
                        override fun write(bytes: ByteArray) = actual.write(bytes)
                        override fun sync() { if(stage == "sync") throw IOException("sync failed"); actual.sync() }
                        override fun close() { actual.close(); if(stage == "close") throw IOException("close failed") }
                    }
                }
            }
            assertThrows(IOException::class.java) { runBlocking {
                SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
            } }
            val store = ImportJournalStore(root)
            assertTrue(store.ids().isEmpty())
            val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
            assertTrue(reopened.items.value.isEmpty())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".pending-") })
        }
    }

    @Test fun cancellationAtSynchronousImportBoundariesRecoversAccordingToPublication() = runBlocking {
        for (boundary in listOf("write", "before-ready", "before-publish", "after-publish", "after-committed")) {
            val root = temporary.newFolder(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var journalWrites = 0
            fun pause() { entered.countDown(); check(release.await(20,TimeUnit.SECONDS)) }
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun writeFile(file: File, bytes: ByteArray) {
                    if(boundary == "write" && file.name == "original") pause()
                    RealImportFileOperations.writeFile(file,bytes)
                }
                override fun replaceJournal(source: File, target: File) {
                    journalWrites++
                    if(boundary == "before-ready" && journalWrites == 2) pause()
                    RealImportFileOperations.replaceJournal(source,target)
                    if(boundary == "after-committed" && journalWrites == 3) pause()
                }
                override fun publishDirectory(source: File, target: File) {
                    if(boundary == "before-publish") pause()
                    RealImportFileOperations.publishDirectory(source,target)
                    if(boundary == "after-publish") pause()
                }
            }
            val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
                listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
            val task = async(Dispatchers.Default) {
                SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
            }
            try {
                assertTrue("boundary $boundary not reached",withContext(Dispatchers.IO) { entered.await(20,TimeUnit.SECONDS) })
                task.cancel()
            } finally { release.countDown() }
            assertThrows(CancellationException::class.java) { runBlocking { task.await() } }
            val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
            val store = ImportJournalStore(root); val entry = store.read(store.ids().single())
            val expected = if(boundary in listOf("write", "before-ready")) ImportJournalState.ROLLED_BACK else ImportJournalState.COMMITTED
            assertEquals(expected,entry.state)
            if(expected == ImportJournalState.COMMITTED) assertEquals(script,reopened.readScript(entry.songId)) else assertTrue(reopened.items.value.isEmpty())
            SongLibraryRepository(root).recoverPendingTransactions()
            assertEquals(expected,store.read(entry.transactionId).state)
        }
    }

    @Test fun filesReadyLogFailureAndFinalVerificationFailureRemainRecoverable() = runBlocking {
        for (stage in listOf("files-ready", "published-read")) {
            val root = temporary.newFolder(); var journalWrites = 0
            val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
                listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun replaceJournal(source: File, target: File) {
                    journalWrites++
                    if (stage == "files-ready" && journalWrites == 2) throw IOException("journal failure")
                    RealImportFileOperations.replaceJournal(source,target)
                }
                override fun readFile(file: File): ByteArray {
                    if (stage == "published-read" && file.name == "original" && file.parentFile?.name?.startsWith(".") == false) throw IOException("published read failure")
                    return RealImportFileOperations.readFile(file)
                }
            }
            assertThrows(IOException::class.java) { runBlocking {
                SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
            } }
            val store = ImportJournalStore(root); val entry = store.read(store.ids().single())
            assertEquals(if(stage == "files-ready") ImportJournalState.PREPARING else ImportJournalState.FILES_READY,entry.state)
            assertEquals(stage == "published-read",root.resolve(entry.songId).exists())
            assertFalse(root.resolve(entry.tempDirectory).exists())
            val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
            assertEquals(if(stage == "files-ready") ImportJournalState.ROLLED_BACK else ImportJournalState.COMMITTED,store.read(entry.transactionId).state)
            if(stage == "published-read") assertEquals(script,reopened.readScript(entry.songId)) else assertTrue(reopened.items.value.isEmpty())
            SongLibraryRepository(root).recoverPendingTransactions()
            assertEquals(if(stage == "files-ready") ImportJournalState.ROLLED_BACK else ImportJournalState.COMMITTED,store.read(entry.transactionId).state)
        }
    }

    @Test fun failedCleanupLeavesPreparingRecordForNewInstanceToRecover() = runBlocking {
        val root = temporary.newFolder()
        val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        val ops = object : ImportFileOperations by RealImportFileOperations {
            override fun writeFile(file: File, bytes: ByteArray) { throw IOException("write failure") }
            override fun deleteTree(file: File): Boolean = false
        }
        val failure = assertThrows(IOException::class.java) { runBlocking {
            SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
        } }
        assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { error -> error.suppressed.any { it.message?.contains("清理") == true } })
        val store = ImportJournalStore(root); val entry = store.read(store.ids().single())
        assertEquals(ImportJournalState.PREPARING,entry.state)
        assertTrue(root.resolve(entry.tempDirectory).exists())
        assertFalse(root.resolve(entry.finalDirectory).exists())
        val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
        assertFalse(root.resolve(entry.tempDirectory).exists())
        assertEquals(ImportJournalState.ROLLED_BACK,store.read(entry.transactionId).state)
        assertTrue(reopened.items.value.isEmpty())
    }

    @Test fun midiScriptWriteFailurePreservesExistingSongAndRollsBackOnRestart() = runBlocking {
        val root = temporary.newFolder()
        val oldScript = PerformanceScript(1,"profile","fingerprint","old.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        val old = SongLibraryRepository(root).add("old.json",SongSourceType.JSON,ScriptCodec.encode(oldScript).toByteArray(),oldScript,emptyList())
        val bytes = javaClass.getResourceAsStream("/duration16-test.mid")!!.readBytes()
        val parsed = MidiParser.parse(bytes)
        val conversion = ScriptConverter.convert(parsed,"new.mid",0,MonophonyPolicy.HIGHEST_NOTE,0,com.huanhren.deltaharmonica.model.DefaultProfiles.delta(),null)
        val ops = object : ImportFileOperations by RealImportFileOperations {
            override fun openScriptOutput(file: File): java.io.OutputStream {
                val actual = RealImportFileOperations.openScriptOutput(file)
                return object : java.io.OutputStream() {
                    override fun write(value: Int) { throw IOException("script write failed") }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { throw IOException("script write failed") }
                    override fun close() = actual.close()
                }
            }
        }
        assertThrows(IOException::class.java) { runBlocking {
            SongLibraryRepository(root,ops).add("new.mid",SongSourceType.MIDI,bytes,conversion.script,conversion.warnings)
        } }
        val store = ImportJournalStore(root)
        val failed = store.ids().map { store.read(it) }.single { it.songId != old.id }
        assertEquals(ImportJournalState.PREPARING,failed.state)
        assertFalse(root.resolve(failed.finalDirectory).exists())
        assertFalse(root.resolve(failed.tempDirectory).exists())
        val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
        assertEquals(ImportJournalState.ROLLED_BACK,store.read(failed.transactionId).state)
        assertEquals(oldScript,reopened.readScript(old.id))
        assertEquals(listOf(old.id),reopened.items.value.map { it.id })
        SongLibraryRepository(root).recoverPendingTransactions()
        assertEquals(ImportJournalState.ROLLED_BACK,store.read(failed.transactionId).state)
    }

    @Test fun cancellationDuringWritingPropagatesAndRecoveryRollsBack() = runBlocking {
        val root = temporary.newFolder()
        val ops = object : ImportFileOperations by RealImportFileOperations {
            override fun writeFile(file: File, bytes: ByteArray) { throw CancellationException("cancelled") }
        }
        val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        assertThrows(CancellationException::class.java) { runBlocking {
            SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
        } }
        val store = ImportJournalStore(root); val id = store.ids().single()
        assertEquals(ImportJournalState.PREPARING,store.read(id).state)
        val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
        assertEquals(ImportJournalState.ROLLED_BACK,store.read(id).state)
        assertTrue(reopened.items.value.isEmpty())
    }

    @Test fun cancellationIsNotMaskedWhenCleanupAlsoFails() = runBlocking {
        val root = temporary.newFolder()
        val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        val ops = object : ImportFileOperations by RealImportFileOperations {
            override fun writeFile(file: File, bytes: ByteArray) { throw CancellationException("cancelled") }
            override fun deleteTree(file: File): Boolean = false
        }
        val failure = assertThrows(CancellationException::class.java) { runBlocking {
            SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
        } }
        assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { error -> error.suppressed.any { it.message?.contains("清理") == true } })
        val store = ImportJournalStore(root); val entry = store.read(store.ids().single())
        assertTrue(root.resolve(entry.tempDirectory).exists())
        SongLibraryRepository(root).recoverPendingTransactions()
        assertEquals(ImportJournalState.ROLLED_BACK,store.read(entry.transactionId).state)
    }

    @Test fun cancellationDuringRecoveryPropagatesAndJournalRemainsRetryable() = runBlocking {
        val root = temporary.newFolder()
        val id = java.util.UUID.randomUUID().toString(); val song = java.util.UUID.randomUUID().toString()
        val store = ImportJournalStore(root)
        store.write(ImportJournal(id,song,ImportJournalState.PREPARING,".pending-$song",song,1,1,"a","b"))
        val ops = object : ImportFileOperations by RealImportFileOperations {
            override fun readFile(file: File): ByteArray { throw CancellationException("cancelled") }
        }
        assertThrows(CancellationException::class.java) { runBlocking { SongLibraryRepository(root,ops).recoverPendingTransactions() } }
        assertEquals(ImportJournalState.PREPARING,store.read(id).state)
        SongLibraryRepository(root).recoverPendingTransactions()
        assertEquals(ImportJournalState.ROLLED_BACK,store.read(id).state)
    }

    @Test fun cancellationDuringJournalReadDoesNotDeletePendingDirectory() = runBlocking {
        val root = temporary.newFolder(); val id = java.util.UUID.randomUUID().toString(); val song = java.util.UUID.randomUUID().toString()
        val entry = ImportJournal(id,song,ImportJournalState.PREPARING,".pending-$song",song,1,1,"a","b")
        val store = ImportJournalStore(root); store.write(entry)
        val temp = root.resolve(entry.tempDirectory).apply { mkdir(); resolve("original").writeText("partial") }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val ops = object : ImportFileOperations by RealImportFileOperations {
            override fun readFile(file: File): ByteArray {
                val result = RealImportFileOperations.readFile(file)
                if(file.name == "$id.json") { entered.countDown(); check(release.await(20,TimeUnit.SECONDS)) }
                return result
            }
        }
        val task = async(Dispatchers.Default) { SongLibraryRepository(root,ops).recoverPendingTransactions() }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(20,TimeUnit.SECONDS) })
            task.cancel()
        } finally { release.countDown() }
        assertThrows(CancellationException::class.java) { runBlocking { task.await() } }
        assertTrue(temp.exists())
        assertEquals(ImportJournalState.PREPARING,store.read(id).state)
        SongLibraryRepository(root).recoverPendingTransactions()
        assertFalse(temp.exists())
        assertEquals(ImportJournalState.ROLLED_BACK,store.read(id).state)
    }

    @Test fun writeFailuresAndHashFailureRecoverWithoutDamagingExistingSong() = runBlocking {
        for (stage in listOf("directory", "original", "metadata", "hash")) {
            val root = temporary.newFolder()
            val oldScript = PerformanceScript(1,"profile","fingerprint","old.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
                listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
            val old = SongLibraryRepository(root).add("old.json",SongSourceType.JSON,ScriptCodec.encode(oldScript).toByteArray(),oldScript,emptyList())
            val newScript = oldScript.copy(sourceMidiName="new.json")
            var hashes = 0
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun createDirectory(directory: File): Boolean {
                    if(stage == "directory") throw IOException("directory failure")
                    return RealImportFileOperations.createDirectory(directory)
                }
                override fun writeFile(file: File, bytes: ByteArray) {
                    if(file.name == stage || (stage == "metadata" && file.name == "metadata.json")) throw IOException("write failure")
                    RealImportFileOperations.writeFile(file,bytes)
                }
                override fun sha256(bytes: ByteArray): String {
                    if(stage == "hash" && ++hashes == 3) throw IOException("hash failure")
                    return RealImportFileOperations.sha256(bytes)
                }
            }
            assertThrows(IOException::class.java) { runBlocking {
                SongLibraryRepository(root,ops).add("new.json",SongSourceType.JSON,ScriptCodec.encode(newScript).toByteArray(),newScript,emptyList())
            } }
            val restarted = SongLibraryRepository(root); restarted.recoverPendingTransactions()
            assertEquals(oldScript,restarted.readScript(old.id))
            assertEquals(listOf(old.id),restarted.items.value.map { it.id })
            val journals = ImportJournalStore(root)
            assertTrue(journals.ids().map { journals.read(it).state }.contains(ImportJournalState.ROLLED_BACK))
            SongLibraryRepository(root).recoverPendingTransactions()
            assertEquals(oldScript,SongLibraryRepository(root).readScript(old.id))
        }
    }

    @Test fun failedDirectoryPublicationLeavesFilesReadyJournalForRecovery() = runBlocking {
        val root = temporary.newFolder()
        val ops = object : ImportFileOperations by RealImportFileOperations {
            override fun publishDirectory(source: File, target: File) { throw IOException("injected publication failure") }
        }
        val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        assertThrows(IOException::class.java) { runBlocking {
            SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
        } }
        val store = ImportJournalStore(root)
        val id = store.ids().single()
        assertEquals(ImportJournalState.FILES_READY, store.read(id).state)
        val restarted = SongLibraryRepository(root)
        restarted.recoverPendingTransactions()
        assertEquals(ImportJournalState.ROLLED_BACK, store.read(id).state)
        assertTrue(restarted.items.value.isEmpty())
    }

    @Test fun committedJournalWriteFailurePreservesPublishedSongAndCanRecover() = runBlocking {
        val root = temporary.newFolder()
        var writes = 0
        val ops = object : ImportFileOperations by RealImportFileOperations {
            override fun replaceJournal(source: File, target: File) {
                writes++
                if (writes == 3) throw IOException("injected committed write failure")
                RealImportFileOperations.replaceJournal(source,target)
            }
        }
        val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        val bytes = ScriptCodec.encode(script).toByteArray()
        assertThrows(IOException::class.java) { runBlocking {
            SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,bytes,script,emptyList())
        } }
        val store = ImportJournalStore(root)
        val journal = store.read(store.ids().single())
        assertEquals(ImportJournalState.FILES_READY,journal.state)
        assertTrue(root.resolve(journal.songId).isDirectory)
        val reopened = SongLibraryRepository(root)
        reopened.recoverPendingTransactions()
        assertEquals(ImportJournalState.COMMITTED,store.read(journal.transactionId).state)
        assertArrayEquals(bytes,reopened.readOriginal(journal.songId))
        assertEquals(script,reopened.readScript(journal.songId))
        SongLibraryRepository(root).recoverPendingTransactions()
        assertEquals(ImportJournalState.COMMITTED,store.read(journal.transactionId).state)
    }

    @Test fun failedRecoveryJournalUpdateReportsProblemAndLaterInstanceCanRetry() = runBlocking {
        val root = temporary.newFolder()
        var writes = 0
        val failCommit = object : ImportFileOperations by RealImportFileOperations {
            override fun replaceJournal(source: File, target: File) {
                if (++writes == 3) throw IOException("commit failed")
                RealImportFileOperations.replaceJournal(source,target)
            }
        }
        val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        assertThrows(IOException::class.java) { runBlocking {
            SongLibraryRepository(root,failCommit).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
        } }
        val store = ImportJournalStore(root); val entry = store.read(store.ids().single())
        val failRecovery = object : ImportFileOperations by RealImportFileOperations {
            override fun replaceJournal(source: File, target: File) { throw IOException("recovery failed") }
        }
        val failed = SongLibraryRepository(root,failRecovery)
        failed.recoverPendingTransactions()
        assertTrue(failed.problems.value.containsKey("transaction:${entry.transactionId}"))
        assertEquals(ImportJournalState.FILES_READY,store.read(entry.transactionId).state)
        assertTrue(root.resolve(entry.songId).isDirectory)
        val retry = SongLibraryRepository(root); retry.recoverPendingTransactions()
        assertEquals(ImportJournalState.COMMITTED,store.read(entry.transactionId).state)
        assertEquals(script,retry.readScript(entry.songId))
    }
}
