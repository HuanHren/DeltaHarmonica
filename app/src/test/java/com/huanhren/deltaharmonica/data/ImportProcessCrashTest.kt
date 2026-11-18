package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.Region
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Runs in a separate JVM: halt bypasses finally blocks and does not terminate Gradle's worker. */
object ImportCrashWorker {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]); val boundary = args[1]
        if (boundary == "R") {
            val recoveryOps = object : ImportFileOperations by RealImportFileOperations {
                override fun replaceJournal(source: File, target: File) { Runtime.getRuntime().halt(73) }
            }
            runBlocking { SongLibraryRepository(root,recoveryOps).recoverPendingTransactions() }
            error("recovery boundary was not reached")
        }
        var journalWrites = 0
        val ops = object : ImportFileOperations by RealImportFileOperations {
            override fun createDirectory(directory: File): Boolean {
                if (boundary == "A") Runtime.getRuntime().halt(73)
                return RealImportFileOperations.createDirectory(directory)
            }
            override fun writeFile(file: File, bytes: ByteArray) {
                RealImportFileOperations.writeFile(file,bytes)
                if ((boundary == "B" && file.name == "original") || (boundary == "C" && file.name == "metadata.json")) Runtime.getRuntime().halt(73)
            }
            override fun replaceJournal(source: File, target: File) {
                RealImportFileOperations.replaceJournal(source,target)
                journalWrites++
                if ((boundary == "D" && journalWrites == 2) || (boundary == "F" && journalWrites == 3)) Runtime.getRuntime().halt(73)
            }
            override fun publishDirectory(source: File, target: File) {
                RealImportFileOperations.publishDirectory(source,target)
                if (boundary == "E") Runtime.getRuntime().halt(73)
            }
        }
        val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
            listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))
        runBlocking { SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList()) }
        error("requested boundary was not reached")
    }
}

class ImportProcessCrashTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun workerClasspath(): String {
        val paths = linkedSetOf<String>()
        System.getProperty("java.class.path").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.forEach(paths::add)
        var loader: ClassLoader? = ImportCrashWorker::class.java.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.getURLs().filter { it.protocol == "file" }.forEach { paths.add(File(it.toURI()).path) }
            loader = loader.parent
        }
        return paths.joinToString(File.pathSeparator)
    }

    @Test fun sixHaltedProcessBoundariesRecoverFromDisk() = runBlocking {
        for (boundary in listOf("A","B","C","D","E","F")) {
            val root = temporary.newFolder()
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val process = ProcessBuilder(java,"-cp",workerClasspath(),ImportCrashWorker::class.java.name,root.absolutePath,boundary)
                .redirectErrorStream(true).redirectOutput(root.resolve("worker-output.txt")).start()
            if (!process.waitFor(45,TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("worker $boundary timed out")
            }
            assertEquals("worker $boundary: ${root.resolve("worker-output.txt").readText()}",73,process.exitValue())
            val store = ImportJournalStore(root); val id = store.ids().single(); val entry = store.read(id)
            val expectedBefore = when(boundary) { "A","B","C" -> ImportJournalState.PREPARING; "D","E" -> ImportJournalState.FILES_READY; else -> ImportJournalState.COMMITTED }
            assertEquals(expectedBefore,entry.state)
            assertEquals(boundary in listOf("E","F"),root.resolve(entry.finalDirectory).exists())
            if (boundary in listOf("B","C","D")) assertTrue(root.resolve(entry.tempDirectory).isDirectory)
            val repository = SongLibraryRepository(root); repository.recoverPendingTransactions()
            val expectedAfter = if(boundary in listOf("E","F")) ImportJournalState.COMMITTED else ImportJournalState.ROLLED_BACK
            assertEquals(expectedAfter,store.read(id).state)
            assertEquals(if(expectedAfter == ImportJournalState.COMMITTED) 1 else 0,repository.items.value.size)
            if(expectedAfter == ImportJournalState.COMMITTED) {
                assertEquals(1,repository.readScript(entry.songId).events.size)
                assertEquals(entry.originalHash,RealImportFileOperations.sha256(repository.readOriginal(entry.songId)))
            }
            val again = SongLibraryRepository(root); again.recoverPendingTransactions()
            assertEquals(expectedAfter,store.read(id).state)
            assertEquals(repository.items.value.size,again.items.value.size)
            if(expectedAfter == ImportJournalState.COMMITTED) assertEquals(entry.originalHash,RealImportFileOperations.sha256(again.readOriginal(entry.songId)))
        }
    }

    @Test fun secondProcessHaltDuringRecoveryRemainsRetryable() = runBlocking {
        val root = temporary.newFolder()
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        for (boundary in listOf("E", "R")) {
            val output = root.resolve("worker-$boundary.txt")
            val process = ProcessBuilder(java,"-cp",workerClasspath(),ImportCrashWorker::class.java.name,root.absolutePath,boundary)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(45,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("worker $boundary timed out") }
            assertEquals("worker $boundary: ${output.readText()}",73,process.exitValue())
        }
        val store = ImportJournalStore(root); val id = store.ids().single(); val entry = store.read(id)
        assertEquals(ImportJournalState.FILES_READY,entry.state)
        assertTrue(root.resolve(entry.finalDirectory).isDirectory)
        repeat(2) {
            val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
            assertEquals(ImportJournalState.COMMITTED,store.read(id).state)
            assertEquals(1,reopened.items.value.size)
            assertEquals(entry.originalHash,RealImportFileOperations.sha256(reopened.readOriginal(entry.songId)))
        }
    }
}
