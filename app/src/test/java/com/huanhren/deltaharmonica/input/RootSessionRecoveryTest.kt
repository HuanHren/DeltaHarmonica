package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.model.ScreenSize
import com.huanhren.deltaharmonica.playback.CombinedGesture
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RootSessionRecoveryTest {
    @Test fun crashWithTouchBlocksThenSessionBPlaysAndReleasesInTheSameBackend() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        val a = requireNotNull(f.backend.openTouch(1000))
        val aId = f.backend.sessionState.value.id
        f.latest.crashOnPlay = true
        val result: TapResult
        val cleanup: RootCleanup
        try {
            result = a.play(gesture)
        } finally { cleanup = f.backend.closeTouch(a) }
        assertTrue(result is TapResult.Unavailable)
        assertTrue(f.touchActive)
        assertEquals(RootCleanup.NO_ACK, cleanup)
        assertEquals(RootSessionPhase.RECOVERY_REQUIRED, f.backend.sessionState.value.phase)
        assertTrue(f.phases.contains(RootSessionPhase.CLEANING))
        assertEquals(aId, f.journal.record?.sessionId)
        assertNull(f.backend.openTouch(1000))
        assertFalse(f.backend.recover().available)
        assertEquals(1, f.helpers.size)
        assertTrue(f.latest.dead)
        assertTrue(f.dispatchLockIsFree())

        f.touchActive = false
        assertTrue(f.backend.checkAccessForUse().available)
        assertTrue(f.backend.recover().available)
        assertNull(f.journal.record)
        val b = requireNotNull(f.backend.openTouch(1000))
        assertNotEquals(aId, f.backend.sessionState.value.id)
        assertEquals(RootCleanup.NO_ACK, f.backend.closeTouch(a))
        assertNull(f.backend.openTouch(1000))
        assertEquals(TapResult.Completed, b.play(gesture))
        assertEquals(RootCleanup.ACKNOWLEDGED, f.backend.closeTouch(b))
        assertEquals(RootSessionPhase.IDLE, f.backend.sessionState.value.phase)
        assertNull(f.journal.record)
        assertFalse(f.touchActive)
        assertTrue(f.helpers.all { it.dead && it.stdinClosed })
        assertTrue(f.dispatchLockIsFree())
        println(f.backend.diagnosticReport())
    }

    @Test fun concurrentCleanupWaitsForSameOwnerAndCannotUnlockNewSession() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        val a = requireNotNull(f.backend.openTouch(1000))
        val helper = f.latest
        helper.holdClose = true
        val first = async { f.backend.closeTouch(a) }
        withTimeout(2000) { helper.closing.await() }
        assertEquals(RootSessionPhase.CLEANING, f.backend.sessionState.value.phase)
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { f.backend.closeTouch(a) }
        assertNull(f.backend.openTouch(1000))
        assertFalse(f.dispatchLockIsFree())
        helper.finish("CLOSED")
        assertEquals(RootCleanup.ACKNOWLEDGED, first.await())
        assertEquals(RootCleanup.ACKNOWLEDGED, duplicate.await())
        val b = requireNotNull(f.backend.openTouch(1000))
        assertEquals(RootCleanup.ACKNOWLEDGED, f.backend.closeTouch(a))
        assertFalse(f.dispatchLockIsFree())
        assertEquals(RootCleanup.ACKNOWLEDGED, f.backend.closeTouch(b))
        assertTrue(f.dispatchLockIsFree())
    }

    @Test fun startupAndJournalFailuresAllowSafeRetryAndNeverDispatch() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        f.failStart = true
        assertNull(f.backend.openTouch(1000))
        assertTrue(f.dispatchLockIsFree())
        f.failStart = false
        f.journal.allowBegin = false
        assertNull(f.backend.openTouch(1000))
        assertTrue(f.latest.dead)
        assertEquals(0, f.latest.plays)
        assertTrue(f.dispatchLockIsFree())
        f.journal.allowBegin = true
        assertEquals(TapResult.Completed, f.backend.tap(point))
    }

    @Test fun verifiedAckWithFailedJournalCommitRemainsBlockedUntilRecoveryCompletes() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        val a = requireNotNull(f.backend.openTouch(1000))
        f.journal.allowConfirm = false
        val dispatched: TapResult
        val cleanup: RootCleanup
        try {
            dispatched = a.play(gesture)
        } finally { cleanup = f.backend.closeTouch(a) }
        assertEquals(TapResult.Completed, dispatched)
        assertEquals(RootCleanup.JOURNAL_FAILURE, cleanup)
        assertTrue(f.backend.resultAfterRelease(cleanup, dispatched) is TapResult.Unavailable)
        assertNull(f.backend.openTouch(1000))
        assertFalse(f.backend.recover().available)
        f.journal.allowConfirm = true
        assertTrue(f.backend.recover().available)
        assertEquals(TapResult.Completed, f.backend.tap(point))
    }

    @Test fun cancellingThePlaybackOwnerStillClosesAndReleasesTheHelper() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        val opened = CompletableDeferred<Unit>()
        val session = launch {
            val connection = requireNotNull(f.backend.openTouch(1000))
            try {
                opened.complete(Unit)
                awaitCancellation()
            } finally {
                assertEquals(RootCleanup.ACKNOWLEDGED, f.backend.closeTouch(connection))
            }
        }
        withTimeout(2_000) { opened.await() }
        session.cancelAndJoin()
        assertTrue(f.latest.dead && f.latest.stdinClosed)
        assertNull(f.journal.record)
        assertTrue(f.dispatchLockIsFree())
        assertEquals(TapResult.Completed, f.backend.tap(point))
    }

    @Test fun revokedRootKeepsTheGateClosedUntilPermissionAndEvidenceReturn() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        val a = requireNotNull(f.backend.openTouch(1000))
        f.latest.crashOnPlay = true
        f.denied = true
        val result: TapResult
        val cleanup: RootCleanup
        try {
            result = a.play(gesture)
            f.touchActive = false
        } finally { cleanup = f.backend.closeTouch(a) }
        assertTrue(result is TapResult.Unavailable)
        assertEquals(RootCleanup.NO_ACK, cleanup)
        assertEquals(RootAuthorizationState.REVOKED, f.backend.authorization.state.value.authorization)
        assertNull(f.backend.openTouch(1000))
        assertFalse(f.backend.recover().available)
        f.denied = false
        assertTrue(f.backend.recover().available)
        assertEquals(TapResult.Completed, f.backend.tap(point))
    }

    @Test fun oneHundredSessionsReleaseOneOwnerAndBothPipesEveryTime() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        repeat(100) {
            assertEquals(TapResult.Completed, f.backend.tap(point))
            assertTrue(f.dispatchLockIsFree())
            assertNull(f.journal.record)
        }
        assertEquals(100, f.helpers.size)
        assertEquals(100, f.helpers.sumOf { it.plays })
        assertTrue(f.helpers.all { it.dead && it.stdinClosed })
        assertEquals(RootSessionPhase.IDLE, f.backend.sessionState.value.phase)
        assertEquals(8, f.backend.diagnosticReport().lineSequence().count { it.startsWith("Root session ") })
    }

    @Test fun independentSupervisorReclaimsWatchdogFailureAndRebuildsInTheSameBackend() = runBlocking {
        val f = Fixture()
        assertTrue(f.backend.checkAccess().available)
        val a = requireNotNull(f.backend.openTouch(1000))
        val aId = f.backend.sessionState.value.id
        val oldExecutor = a.dispatcherId
        f.latest.playFault = RootDispatcherFault.NATIVE_TIMEOUT
        assertTrue(a.play(gesture) is TapResult.Unavailable)
        // Playback owner deliberately does not call closeTouch. The transport fault owns cleanup.
        val blocked = withTimeout(3_000) { f.backend.sessionState.first { it.phase == RootSessionPhase.RECOVERY_REQUIRED } }
        f.awaitResources(a)
        assertEquals("NATIVE_TIMEOUT", blocked.terminationReason)
        assertEquals(RootPlaybackLockState.LOCK_RELEASE_UNCONFIRMED, blocked.lockState)
        assertTrue(f.touchActive)
        assertEquals(aId, f.journal.record?.sessionId)
        assertNull(f.backend.openTouch(1000))
        f.touchActive = false
        assertTrue(f.backend.recover().available)
        val b = requireNotNull(f.backend.openTouch(1000))
        val bId = f.backend.sessionState.value.id
        assertNotEquals(aId, bId)
        assertNotEquals(oldExecutor, b.dispatcherId)
        assertFalse(requireNotNull(blocked.cleanup).confirmed)
        assertEquals(blocked.cleanup, f.backend.closeTouch(a))
        f.backend.recordDiagnostic(requireNotNull(aId), "legacy late callback", "STALE_FAULT")
        assertNull(f.backend.sessionState.value.terminationReason)
        val dispatched: TapResult
        try { dispatched = b.play(gesture) } finally {
            assertEquals(RootCleanup.ACKNOWLEDGED, f.backend.closeTouch(b))
        }
        assertEquals(TapResult.Completed, dispatched)
        f.awaitResources(b)
        f.assertIdle()
        println("A_START=$aId DOWN=${f.helpers[0].plays} FAULT=${blocked.terminationReason} CLEANUP=${blocked.cleanup}")
        println("A_RELEASE_PROOF=CONFIRMED B_START=$bId DISPATCHER_REBUILT=${b.dispatcherId}")
        println("B_DONE=$dispatched B_RELEASE=ACKNOWLEDGED FINAL=${f.backend.sessionState.value.phase}")
        println(f.backend.diagnosticReport())
    }

    @Test fun systemTimeoutHasAnAttemptLimitAndConcurrentRecoveryCannotOpenInput() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        val a = requireNotNull(f.backend.openTouch(1000))
        f.dispatcherTimesOut = true
        f.latest.finish()
        assertEquals(RootCleanup.NO_ACK, f.backend.closeTouch(a))
        val gate = CompletableDeferred<Unit>()
        f.probeGate = gate
        val retry = async { f.backend.checkAccessForUse() }
        withTimeout(2_000) { f.backend.sessionState.first { it.phase == RootSessionPhase.RECOVERING } }
        assertFalse(f.backend.recover().available)
        assertNull(f.backend.openTouch(1000))
        gate.complete(Unit)
        assertFalse(retry.await().available)
        assertEquals(4, f.dispatchReads.get())
        assertEquals(RootRecoveryResult.RETRY_LIMIT, f.backend.sessionState.value.recoveryResult)
        assertTrue(f.backend.sessionState.value.detail.orEmpty().contains("DISPATCHER_UNRESPONSIVE"))
        assertFalse(f.backend.checkAccessForUse().available)
        assertEquals(4, f.dispatchReads.get())
        f.dispatcherTimesOut = false
        assertTrue(f.backend.recover().available)
        f.awaitResources(a)
        f.assertIdle()
    }

    @Test fun aTerminalReleaseClosesTheLeaseEvenWithoutTheCallerFinally() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        val a = requireNotNull(f.backend.openTouch(1000))
        f.latest.holdGesture = true
        val caller = launch { a.play(gesture) }
        withTimeout(2_000) { f.latest.inputReached.await() }
        caller.cancelAndJoin()
        f.awaitResources(a)
        f.assertIdle()
        assertFalse(a.dispatcherFault.isCompleted)
        assertEquals(RootCleanup.ACKNOWLEDGED, f.backend.closeTouch(a))
        assertEquals(TapResult.Completed, f.backend.tap(point))
    }

    @Test fun framedFullSongKeepsTheLeaseUntilThe360thUpAndEnd() = runBlocking {
        val f = Fixture()
        assertTrue(f.backend.checkAccess().available)
        val connection = requireNotNull(f.backend.openTouch(1000))
        val helper = f.latest
        val pendingUp = CompletableDeferred<Unit>()
        val completeNotes = mutableListOf<Int>()
        val steps = List(360) { index -> RootSequenceStep(index * 100L, listOf(RootStroke(1f, 2f, 0L, 45L))) }
        val playback = async {
            try {
                connection.playSequence(steps, { false }, { false }, { index, down, up ->
                    assertEquals(45L, requireNotNull(up) - requireNotNull(down))
                    completeNotes.add(index)
                    helper.sequenceCredits.release()
                    if (index == 358) pendingUp.complete(Unit)
                }, {}, {})
            } finally { assertEquals(RootCleanup.ACKNOWLEDGED, f.backend.closeTouch(connection)) }
        }
        try {
            withTimeout(4_000) { pendingUp.await() }
            assertEquals((339..344).toList(), completeNotes.subList(339, 345))
            assertEquals(359, completeNotes.size)
            assertFalse(playback.isCompleted)
            assertFalse(f.dispatchLockIsFree())
            assertNotNull(f.journal.record)
        } finally { helper.releaseFinalUp.countDown() }
        val result = withTimeout(3_000) { playback.await() }
        assertEquals(TapResult.Completed, result.result)
        assertEquals(360, result.completed)
        assertEquals((0..359).toList(), completeNotes)
        f.awaitResources(connection)
        f.assertIdle()
        println("WIRE fullSongUploaded=${helper.sequenceNotes} boundary=340..345 complete=360 finalUp=true end=true cleanup=ACKNOWLEDGED")
    }

    @Test fun aThousandNormalSessionsReleaseTheirUniqueExecutorAndResources() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        val sessions = mutableSetOf<String>()
        val executors = mutableSetOf<String>()
        val initialThreads = transportThreads()
        repeat(1_000) { index ->
            val connection = requireNotNull(f.backend.openTouch(1000))
            try {
                val active = f.backend.sessionState.value
                assertEquals(RootSessionPhase.ACTIVE, active.phase)
                assertEquals(RootPlaybackLockState.LOCK_HELD, active.lockState)
                assertEquals("READY", connection.dispatcherStatus.name)
                assertTrue(sessions.add(requireNotNull(active.id)))
                assertTrue(executors.add(requireNotNull(connection.dispatcherId)))
                assertEquals(active.id, f.journal.record?.sessionId)
                val nextPoint = point.copy(x = point.x + index % 8)
                assertEquals(TapResult.Completed, connection.play(CombinedGesture.create(nextPoint, 45L + index % 3, emptyList())))
            } finally { assertEquals(RootCleanup.ACKNOWLEDGED, f.backend.closeTouch(connection)) }
            f.awaitResources(connection)
            f.assertIdle()
        }
        awaitThreadBudget(initialThreads)
        assertEquals(1_000, f.helpers.sumOf { it.plays })
        assertEquals(1_000, f.helpers.size)
        assertTrue(f.helpers.all { it.closedAndDrained() })
        println("NORMAL sessions=${sessions.size} executors=${executors.size} helpersClosed=${f.helpers.size} observers=${f.backend.supervisedSessions} pipeThreads=${transportThreads()} initialThreads=$initialThreads")
    }

    @Test fun aThousandMixedFaultsPreserveTheGateAndReleaseOnlyRecognizedResources() = runBlocking {
        val f = Fixture()
        f.backend.checkAccess()
        val initialThreads = transportThreads()
        val seen = IntArray(10)
        repeat(1_000) { index ->
            val scenario = index % seen.size
            seen[scenario]++
            if (scenario == 0) {
                f.failStart = true
                assertNull(f.backend.openTouch(1000))
                f.failStart = false
                f.assertIdle()
                return@repeat
            }
            val connection = requireNotNull(f.backend.openTouch(1000))
            val helper = f.latest
            try {
                when (scenario) {
                    1 -> helper.finish() // Peer exited before RUN/PLAY.
                    2 -> {
                        helper.crashOnPlay = true
                        assertTrue(connection.play(gesture) is TapResult.Unavailable)
                    }
                    3, 4, 5 -> {
                        helper.playFault = when (scenario) {
                            3 -> RootDispatcherFault.NATIVE_TIMEOUT
                            4 -> RootDispatcherFault.WORKER_FAILED
                            else -> RootDispatcherFault.STOP_TIMEOUT
                        }
                        assertTrue(connection.play(gesture) is TapResult.Unavailable)
                    }
                    6 -> {
                        helper.loseReplyAfterUp = true
                        assertTrue(connection.play(gesture) is TapResult.Unavailable)
                    }
                    7 -> f.journal.allowConfirm = false
                    8 -> {
                        f.dispatcherTimesOut = true
                        helper.finish()
                    }
                    9 -> {
                        f.denied = true
                        helper.finish()
                    }
                }
            } finally { f.backend.closeTouch(connection) }
            f.awaitResources(connection)
            if (scenario in 2..5 || scenario >= 7) {
                assertTrue(f.backend.releaseUncertain)
                assertNotNull(f.journal.record)
                assertNull(f.backend.openTouch(1000))
                assertFalse(f.backend.recover().available)
                f.touchActive = false
                f.journal.allowConfirm = true
                f.dispatcherTimesOut = false
                f.denied = false
                assertTrue(f.backend.recover().available)
            }
            f.assertIdle()
        }
        awaitThreadBudget(initialThreads)
        assertEquals(900, f.helpers.size)
        assertTrue(f.helpers.all { it.closedAndDrained() })
        assertEquals(8, f.backend.diagnosticReport().lineSequence().count { it.startsWith("Root session ") })
        println("FAULTS total=${seen.sum()} perClass=${seen.joinToString()} helpersClosed=${f.helpers.size} observers=${f.backend.supervisedSessions} pipeThreads=${transportThreads()} initialThreads=$initialThreads")
    }

    private fun transportThreads(): Int = Thread.getAllStackTraces().keys.count {
        it.name.startsWith("DeltaRootResponses") || it.name.startsWith("DeltaRootCommands")
    }

    private suspend fun awaitThreadBudget(baseline: Int) {
        withTimeout(2_000) { while (transportThreads() > baseline) delay(1) }
    }

    private class Journal : RootReleaseJournal {
        var allowBegin = true
        var allowConfirm = true
        @Volatile var record: RootReleaseRecord? = null
        override fun pending() = record
        override fun begin(record: RootReleaseRecord): Boolean {
            assertNull(this.record)
            if (allowBegin) this.record = record
            return allowBegin
        }
        override fun confirm(record: RootReleaseRecord): Boolean {
            assertEquals(this.record, record)
            if (allowConfirm) this.record = null
            return allowConfirm
        }
    }

    private class Fixture {
        val journal = Journal()
        val helpers = mutableListOf<FakeHelper>()
        val phases = Collections.synchronizedList(mutableListOf<RootSessionPhase>())
        var failStart = false
        @Volatile var denied = false
        @Volatile var touchActive = false
        @Volatile var dispatcherTimesOut = false
        var probeGate: CompletableDeferred<Unit>? = null
        val dispatchReads = AtomicInteger()
        val latest get() = helpers.last()
        private val runner = RootCommandRunner { command, _ ->
            when {
                denied -> RootCommandResult.Failure("permission denied")
                command == "id -u" -> RootCommandResult.Success("0")
                command.contains("/proc/${latest.pid}/stat") && latest.dead ->
                    RootCommandResult.Success("DELTA_GONE ${latest.pid}")
                command.contains("dumpsys input") -> {
                    dispatchReads.incrementAndGet()
                    probeGate?.await()
                    if (dispatcherTimesOut) RootCommandResult.TimedOut else RootCommandResult.Success(RootRecoveryProbeTest.idle.let {
                        if (touchActive) it.replace("<no displays touched>", "active touch") else it
                    })
                }
                else -> RootCommandResult.Failure("unexpected command")
            }
        }
        val backend = RootBackend(
            runner, "mock.apk", 1000, RootPermissionManager(runner), journal,
            openConnection = { _, _, _, sessionId ->
                if (failStart) null else {
                    val helper = FakeHelper(helpers.size + 100, sessionId) { active -> touchActive = active }
                    helpers += helper
                    helper.reply("READY ${RootSequenceProtocol.VERSION} ${helper.pid} 12345")
                    RootGestureConnection(helper, null, sessionId = sessionId).also { assertTrue(it.awaitReady(sessionId)) }
                }
            },
            reportSession = { phases.add(it.phase) },
            waitForRecovery = {},
        ) { ScreenSize(100, 100) }

        fun dispatchLockIsFree(): Boolean {
            if (!inputDispatchMutex.tryLock()) return false
            inputDispatchMutex.unlock()
            return true
        }

        suspend fun awaitResources(connection: RootGestureConnection) {
            withTimeout(3_000) {
                connection.transportClosed.await()
                while (backend.supervisedSessions != 0) delay(1)
            }
        }

        fun assertIdle() {
            assertNull(journal.record)
            assertFalse(touchActive)
            assertTrue(dispatchLockIsFree())
            assertEquals(RootSessionPhase.IDLE, backend.sessionState.value.phase)
            assertEquals(RootPlaybackLockState.LOCK_FREE, backend.sessionState.value.lockState)
        }
    }

    private class FakeHelper(val pid: Int, val sessionId: String, val setTouch: (Boolean) -> Unit) : Process() {
        private val dispatcherId = UUID.randomUUID().toString()
        private val output = ReplyStream()
        @Volatile var crashOnPlay = false
        @Volatile var playFault: RootDispatcherFault? = null
        @Volatile var loseReplyAfterUp = false
        @Volatile var holdGesture = false
        @Volatile var holdClose = false
        @Volatile var dead = false
        @Volatile var stdinClosed = false
        @Volatile var plays = 0
        val releaseFinalUp = CountDownLatch(1)
        val sequenceCredits = Semaphore(16)
        @Volatile var sequenceNotes = 0
            private set
        private var sequenceSize = 0
        val closing = CompletableDeferred<Unit>()
        val inputReached = CompletableDeferred<Unit>()
        private val finished = AtomicBoolean()
        private val commands = object : OutputStream() {
            private val line = StringBuilder()
            override fun write(value: Int) {
                if (value != '\n'.code) {
                    line.append(value.toChar())
                    return
                }
                val frame = checkNotNull(RootSequenceProtocol.decodeFrame(line.toString(), sessionId))
                check(frame.dispatcherId == dispatcherId)
                if (frame.payload == "PING" && frame.operation == 0L) reply("PONG")
                if (frame.payload.startsWith("SEQ ")) sequence(frame)
                if (frame.payload.startsWith("PLAY ")) {
                    plays++
                    setTouch(true)
                    inputReached.complete(Unit)
                    val fault = playFault
                    if (fault != null) {
                        reply("FAULT ${fault.name} POINTERS 0", frame.operation)
                        finish()
                    } else if (crashOnPlay) finish() else if (!holdGesture) {
                        setTouch(false)
                        if (loseReplyAfterUp) finish() else reply("DONE OK 100 145", frame.operation)
                    }
                }
                line.clear()
            }
            override fun close() {
                stdinClosed = true
                closing.complete(Unit)
                if (!dead && !holdClose) {
                    if (holdGesture) setTouch(false)
                    finish("CLOSED")
                }
            }
        }
        fun reply(message: String, op: Long = 0L) =
            output.offer((RootSequenceProtocol.encodeFrame(sessionId, dispatcherId, op, message) + "\n").toByteArray())
        private fun sequence(frame: RootProtocolFrame) {
            val fields = frame.payload.split(' ', limit = 5)
            val id = frame.operation
            check(fields[1].toLong() == id)
            fun respond(payload: String) = reply("SEQ $id $payload", id)
            when (fields[2]) {
                "QUEUE" -> {
                    sequenceSize = fields[3].toInt()
                    respond("ACK 0")
                }
                "BLOCK" -> {
                    check(fields[3].toInt() == sequenceNotes)
                    sequenceNotes += requireNotNull(RootSequenceProtocol.decodeChunk(fields[4])).size
                    respond("ACK $sequenceNotes")
                }
                "RUN" -> {
                    check(sequenceNotes == sequenceSize && sequenceSize == 360)
                    Thread({
                        val epoch = 1_000L
                        respond("START $epoch")
                        repeat(sequenceNotes) { index ->
                            if (!sequenceCredits.tryAcquire(4, TimeUnit.SECONDS)) return@Thread
                            if (index == sequenceNotes - 1 && !releaseFinalUp.await(4, TimeUnit.SECONDS)) return@Thread
                            val down = epoch + index * 100L
                            respond("EVENT $index OK $down $down ${down + 45}")
                        }
                        respond("END OK $sequenceNotes")
                    }, "FakeRootSequence").apply { isDaemon = true; start() }
                }
                "PING" -> respond("PONG")
            }
        }
        fun finish(terminal: String? = null) {
            if (!finished.compareAndSet(false, true)) return
            dead = true
            if (terminal != null) reply(terminal)
            output.offer(ByteArray(0))
        }
        fun closedAndDrained() = dead && stdinClosed && output.closedAndDrained()
        override fun getInputStream() = output
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun getOutputStream() = commands
        override fun waitFor() = exitValue()
        override fun exitValue(): Int = if (dead) 0 else throw IllegalThreadStateException()
        override fun isAlive() = !dead
        override fun destroy() = finish()
    }

    private class ReplyStream : InputStream() {
        private val messages = LinkedBlockingQueue<ByteArray>()
        private var remaining = ByteArray(0)
        private var position = 0
        @Volatile private var eof = false
        @Synchronized fun offer(bytes: ByteArray) {
            if (!closed || bytes.isNotEmpty()) messages.add(bytes)
        }
        @Volatile private var closed = false
        @Synchronized override fun close() {
            closed = true
            // Reader may close immediately after the terminal frame without reading a subsequent EOF.
            // Consume that final queued transport sentinel during close, like closing a real descriptor.
            if (messages.peek()?.isEmpty() == true) messages.poll()
        }
        fun closedAndDrained() = closed && messages.isEmpty()
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (eof) return -1
            if (position == remaining.size) {
                remaining = messages.take()
                position = 0
                if (remaining.isEmpty()) {
                    eof = true
                    return -1
                }
            }
            val count = minOf(length, remaining.size - position)
            remaining.copyInto(bytes, offset, position, position + count)
            position += count
            return count
        }
    }

    private companion object {
        val point = ScreenPoint(12f, 35f)
        val gesture = CombinedGesture.create(point, 45, emptyList())
    }
}
