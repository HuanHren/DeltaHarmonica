package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.Region
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ImportCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val script = PerformanceScript(1,"profile","fingerprint","song.json",0,MonophonyPolicy.HIGHEST_NOTE,false,
        listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))

    @Test fun observerCanLeaveAndResubscribeWithoutRestartingWork() = runTest {
        val coordinator = ImportCoordinator(backgroundScope); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var runs = 0
        val observer = backgroundScope.launch { coordinator.state.collect() }
        assertTrue(coordinator.start { runs++; update(ImportPhase.PARSING,"parse"); entered.complete(Unit); release.await(); update(ImportPhase.COMMITTED,"done","song",true) })
        runCurrent(); entered.await(); observer.cancelAndJoin()
        val another = backgroundScope.launch { coordinator.state.collect() }; runCurrent()
        assertEquals(1,runs); assertEquals(ImportPhase.PARSING,coordinator.state.value.phase)
        release.complete(Unit); runCurrent()
        assertEquals(1,runs); assertEquals("song",coordinator.state.value.songId); assertTrue(coordinator.state.value.committed)
        another.cancelAndJoin()
    }

    @Test fun concurrentRequestIsRejectedAndDoesNotRun() = runTest {
        val coordinator = ImportCoordinator(backgroundScope); val release = CompletableDeferred<Unit>(); var runs = 0
        assertTrue(coordinator.start { runs++; release.await(); update(ImportPhase.READY,"ready") })
        runCurrent()
        assertFalse(coordinator.start { runs++ })
        assertNotNull(coordinator.state.value.rejection); assertEquals(1,runs)
        release.complete(Unit); runCurrent(); assertEquals(1,runs)
    }

    @Test fun cancellationDuringParsingCannotPublishSuccess() = runTest {
        val coordinator = ImportCoordinator(backgroundScope); val entered = CompletableDeferred<Unit>()
        assertTrue(coordinator.start { update(ImportPhase.PARSING,"parse"); entered.complete(Unit); CompletableDeferred<Unit>().await(); update(ImportPhase.COMMITTED,"done", "id",true) })
        runCurrent(); entered.await(); coordinator.cancel(); runCurrent()
        assertEquals(ImportPhase.CANCELLED,coordinator.state.value.phase); assertFalse(coordinator.state.value.committed)
    }

    @Test fun ownerCancellationStopsTaskWithoutDependingOnObserver() = runTest {
        val ownerJob = Job(); val owner = CoroutineScope(coroutineContext + ownerJob)
        val coordinator = ImportCoordinator(owner); val entered = CompletableDeferred<Unit>()
        coordinator.start { update(ImportPhase.SAVING,"saving"); entered.complete(Unit); CompletableDeferred<Unit>().await() }
        runCurrent(); entered.await(); owner.cancel(); runCurrent()
        assertEquals(ImportPhase.CANCELLED,coordinator.state.value.phase); assertFalse(coordinator.state.value.active)
    }

    @Test fun inputIsClosedOnSuccessAndReadFailure() = runTest {
        for (fails in listOf(false,true)) {
            var closed = false
            val input = object : java.io.ByteArrayInputStream("data".toByteArray()) {
                override fun read(bytes: ByteArray, offset: Int, len: Int): Int {
                    if (fails) throw java.io.IOException("read failed")
                    return super.read(bytes,offset,len)
                }
                override fun close() { closed = true; super.close() }
            }
            if (fails) assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { DocumentTransfer.readBounded(input) } }
            else assertArrayEquals("data".toByteArray(),DocumentTransfer.readBounded(input))
            assertTrue(closed)
        }
    }

    @Test fun cancellationAroundPublicationIsRecoveredByNewRepository() = kotlinx.coroutines.runBlocking {
        for (after in listOf(false,true)) {
            val root = temporary.newFolder(); val oldScript = script.copy(sourceMidiName="old.json")
            val old = SongLibraryRepository(root).add("old.json",SongSourceType.JSON,ScriptCodec.encode(oldScript).toByteArray(),oldScript,emptyList())
            val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun publishDirectory(source: java.io.File, target: java.io.File) {
                    if (after) RealImportFileOperations.publishDirectory(source,target)
                    entered.countDown(); check(release.await(20,java.util.concurrent.TimeUnit.SECONDS))
                    if (!after) RealImportFileOperations.publishDirectory(source,target)
                }
            }
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val coordinator = ImportCoordinator(owner)
            coordinator.start {
                update(ImportPhase.SAVING,"save")
                val item = SongLibraryRepository(root,ops).add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
                update(ImportPhase.COMMITTED,"done",item.id,true)
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(20,java.util.concurrent.TimeUnit.SECONDS) })
                coordinator.cancel()
            } finally { release.countDown() }
            withTimeout(20_000) { coordinator.state.first { !it.active } }
            assertEquals(ImportPhase.CANCELLED,coordinator.state.value.phase)
            val restarted = SongLibraryRepository(root); restarted.recoverPendingTransactions()
            assertEquals(2,restarted.items.value.size)
            assertEquals(oldScript,restarted.readScript(old.id))
            assertEquals(script,restarted.readScript(restarted.items.value.single { it.id != old.id }.id))
            val again = SongLibraryRepository(root); again.recoverPendingTransactions(); assertEquals(2,again.items.value.size)
            owner.cancel()
        }
    }

    @Test fun oldCallbackCannotReplaceNewTaskState() = runTest {
        val coordinator = ImportCoordinator(backgroundScope); lateinit var old: ImportCoordinator.Task
        coordinator.start { old = this; update(ImportPhase.READY,"old") }; runCurrent()
        val wait = CompletableDeferred<Unit>(); coordinator.start { update(ImportPhase.READING,"new"); wait.await() }; runCurrent()
        old.update(ImportPhase.COMMITTED,"stale","old",true)
        assertEquals("new",coordinator.state.value.message); assertFalse(coordinator.state.value.committed)
        wait.complete(Unit); runCurrent()
    }

    @Test fun committedSongSurvivesActivationFailureAndResubscription() = runTest {
        val root = temporary.newFolder(); val repository = SongLibraryRepository(root); val coordinator = ImportCoordinator(backgroundScope)
        coordinator.start {
            update(ImportPhase.SAVING,"save")
            val item = repository.add("song.json",SongSourceType.JSON,ScriptCodec.encode(script).toByteArray(),script,emptyList())
            update(ImportPhase.COMMITTED,"saved",item.id,true)
            error("activation failed")
        }
        testScheduler.advanceUntilIdle()
        // Repository uses Dispatchers.IO, so wait deterministically for its task rather than advancing virtual time.
        while (coordinator.state.value.active) kotlinx.coroutines.yield()
        val result = coordinator.state.value
        assertTrue(result.committed); assertEquals(ImportPhase.FAILED,result.phase); assertTrue(result.message.contains("activation failed"))
        val reopened = SongLibraryRepository(root); reopened.recoverPendingTransactions()
        assertEquals(script,reopened.readScript(requireNotNull(result.songId))); assertEquals(1,reopened.items.value.size)
        SongLibraryRepository(root).recoverPendingTransactions(); assertEquals(1,reopened.items.value.size)
    }
}
