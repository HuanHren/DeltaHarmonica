package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.Region
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

class ImportRecoveryFailureMatrixTest {
    @get:Rule val temporary = TemporaryFolder()

    private val oldScript = PerformanceScript(1,"profile","fingerprint","old.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
        listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
    private val midi get() = javaClass.getResourceAsStream("/duration16-test.mid")!!.readBytes()
    private fun conversion() = ScriptConverter.convert(MidiParser.parse(midi),"new.mid",0,MonophonyPolicy.HIGHEST_NOTE,0,com.huanhren.deltaharmonica.model.DefaultProfiles.delta(),null)
    private suspend fun old(root: File): SongLibraryItem = SongLibraryRepository(root).add("old.json",SongSourceType.JSON,ScriptCodec.encode(oldScript).toByteArray(),oldScript,emptyList())
    private suspend fun midiSong(root: File): SongLibraryItem {
        val c = conversion()
        return SongLibraryRepository(root).add("new.mid",SongSourceType.MIDI,midi,c.script,c.warnings)
    }
    private fun journal(root: File, song: SongLibraryItem): ImportJournal {
        val store = ImportJournalStore(root)
        return store.ids().map(store::read).single { it.songId == song.id }
    }
    private suspend fun assertOld(root: File, old: SongLibraryItem, repo: SongLibraryRepository) {
        assertEquals(old.contentHash,RealImportFileOperations.sha256(repo.readOriginal(old.id)))
        assertEquals(oldScript,repo.readScript(old.id))
        assertTrue(root.resolve(old.id).isDirectory)
    }

    @Test fun scriptCreationAndCloseFailuresNeverCommit() = runBlocking {
        for (stage in listOf("create", "close", "write-close")) {
            val root = temporary.newFolder(); val previous = old(root); val c = conversion()
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun openScriptOutput(file: File): OutputStream {
                    if (stage == "create") throw IOException("script create failure")
                    val actual = RealImportFileOperations.openScriptOutput(file)
                    return object : OutputStream() {
                        override fun write(value: Int) = actual.write(value)
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            actual.write(bytes,offset,length)
                            if (stage == "write-close") throw IOException("script write failure")
                        }
                        override fun close() { actual.close(); throw IOException("script close failure") }
                    }
                }
            }
            val failure = assertThrows(IOException::class.java) { runBlocking {
                SongLibraryRepository(root,ops).add("new.mid",SongSourceType.MIDI,midi,c.script,c.warnings)
            } }
            if (stage == "write-close") assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it.suppressed.any { s -> s.message?.contains("close") == true } })
            val store = ImportJournalStore(root); val entry = store.ids().map(store::read).single { it.songId != previous.id }
            assertEquals(ImportJournalState.PREPARING,entry.state)
            assertFalse(root.resolve(entry.tempDirectory).exists()); assertFalse(root.resolve(entry.finalDirectory).exists())
            repeat(2) {
                val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
                assertEquals(ImportJournalState.ROLLED_BACK,store.read(entry.transactionId).state)
                assertEquals(listOf(previous.id),reopened.items.value.map { it.id }); assertOld(root,previous,reopened)
                assertNull(reopened.get(entry.songId))
            }
        }
    }

    @Test fun recoveryReadFailuresAreReportedAndRetryFromDisk() = runBlocking {
        for (stage in listOf("metadata.json", "script.json", "original", "journal")) {
            val root = temporary.newFolder(); val previous = old(root); val item = midiSong(root)
            val store = ImportJournalStore(root); val entry = journal(root,item)
            store.write(entry.copy(state=ImportJournalState.FILES_READY))
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun readFile(file: File): ByteArray {
                    if ((stage == "journal" && file.name == "${entry.transactionId}.json") ||
                        (stage != "journal" && file.parentFile?.name == item.id && file.name == stage)) throw IOException("injected $stage read failure")
                    return RealImportFileOperations.readFile(file)
                }
            }
            val failed = SongLibraryRepository(root,ops); failed.recoverPendingTransactions()
            assertTrue("$stage ${failed.problems.value}",failed.problems.value.containsKey("transaction:${entry.transactionId}"))
            assertEquals(ImportJournalState.FILES_READY,store.read(entry.transactionId).state)
            assertTrue(root.resolve(item.id).isDirectory); assertFalse(root.resolve(entry.tempDirectory).exists())
            assertOld(root,previous,failed)
            repeat(2) {
                val repo = SongLibraryRepository(root); repo.recoverPendingTransactions()
                assertEquals(ImportJournalState.COMMITTED,store.read(entry.transactionId).state)
                assertEquals(2,repo.items.value.size); assertOld(root,previous,repo)
                assertEquals(item.contentHash,RealImportFileOperations.sha256(repo.readOriginal(item.id)))
                assertEquals(conversion().script,repo.readScript(item.id))
            }
        }
    }

    @Test fun cleanupFailuresRemainRetryableAcrossStates() = runBlocking {
        for (stage in listOf("preparing", "files-ready", "committed", "partial")) {
            val root = temporary.newFolder(); val previous = old(root); val item = midiSong(root)
            val store = ImportJournalStore(root); val original = journal(root,item)
            val final = root.resolve(item.id); val pending = root.resolve(original.tempDirectory)
            if (stage in listOf("preparing", "files-ready")) {
                assertTrue(final.renameTo(pending))
                store.write(original.copy(state=if(stage == "preparing") ImportJournalState.PREPARING else ImportJournalState.FILES_READY))
            } else {
                assertTrue(pending.mkdir())
                final.resolve("original").copyTo(pending.resolve("original"))
                if(stage == "committed") final.resolve("metadata.json").copyTo(pending.resolve("metadata.json"))
            }
            val expected = store.read(original.transactionId).state
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun deleteTree(file: File): Boolean = if(file == pending) false else RealImportFileOperations.deleteTree(file)
            }
            val failed = SongLibraryRepository(root,ops); failed.recoverPendingTransactions()
            assertTrue("$stage ${failed.problems.value}",failed.problems.value.containsKey("transaction:${original.transactionId}"))
            assertTrue(pending.exists()); assertEquals(expected,store.read(original.transactionId).state)
            assertEquals(stage in listOf("committed", "partial"),final.exists()); assertOld(root,previous,failed)
            repeat(2) {
                val repo = SongLibraryRepository(root); repo.recoverPendingTransactions()
                assertFalse(pending.exists()); assertOld(root,previous,repo)
                if(stage in listOf("committed", "partial")) {
                    assertEquals(ImportJournalState.COMMITTED,store.read(original.transactionId).state)
                    assertEquals(2,repo.items.value.size); assertEquals(item.contentHash,RealImportFileOperations.sha256(repo.readOriginal(item.id)))
                } else {
                    assertEquals(ImportJournalState.ROLLED_BACK,store.read(original.transactionId).state)
                    assertEquals(1,repo.items.value.size); assertNull(repo.get(item.id))
                }
            }
        }
    }

    @Test fun damagedArtifactsAndHashOperationFailureAreDistinguishedAndPreserved() = runBlocking {
        for (stage in listOf("original", "script.json", "hash-error", "pending")) {
            val root = temporary.newFolder(); val previous = old(root); val item = midiSong(root)
            val store = ImportJournalStore(root); val entry = journal(root,item)
            store.write(entry.copy(state=ImportJournalState.FILES_READY))
            val final = root.resolve(item.id); val pending = root.resolve(entry.tempDirectory)
            val artifact = final.resolve(if(stage == "script.json") "script.json" else "original")
            if(stage == "original" || stage == "script.json") artifact.writeText("corrupt $stage")
            if(stage == "pending") {
                assertTrue(pending.mkdir()); pending.resolve("original").writeText("corrupt pending")
            }
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun sha256(bytes: ByteArray): String {
                    if(stage == "hash-error" && bytes.contentEquals(midi)) throw IOException("digest operation failed")
                    return RealImportFileOperations.sha256(bytes)
                }
            }
            repeat(2) {
                val repo = SongLibraryRepository(root,ops); repo.recoverPendingTransactions()
                val reason = repo.problems.value["transaction:${entry.transactionId}"]
                assertNotNull("$stage must be reported",reason)
                if(stage == "hash-error") assertTrue(reason!!.contains("digest operation failed"))
                if(stage == "original" || stage == "script.json") assertTrue(reason!!.contains("摘要"))
                assertEquals(ImportJournalState.FILES_READY,store.read(entry.transactionId).state)
                assertTrue(final.exists()); assertEquals(stage == "pending",pending.exists())
                assertOld(root,previous,repo)
                if(stage == "original" || stage == "script.json") {
                    assertTrue(repo.problems.value.containsKey(item.id))
                    assertThrows(Exception::class.java) { runBlocking { repo.readScript(item.id) } }
                    assertEquals("corrupt $stage",artifact.readText())
                }
            }
            if(stage == "hash-error") {
                repeat(2) {
                    val repo = SongLibraryRepository(root); repo.recoverPendingTransactions()
                    assertEquals(ImportJournalState.COMMITTED,store.read(entry.transactionId).state)
                    assertEquals(2,repo.items.value.size); assertOld(root,previous,repo)
                    assertEquals(item.contentHash,RealImportFileOperations.sha256(repo.readOriginal(item.id)))
                }
            }
        }
    }
}
