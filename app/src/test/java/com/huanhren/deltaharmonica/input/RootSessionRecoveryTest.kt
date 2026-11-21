package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.model.ScreenSize
import com.huanhren.deltaharmonica.playback.CombinedGesture
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
        assertEquals(RootCleanup.NO_ACK, f.backend.closeTouch(a))
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
        val result: TapResult
        val cleanup: RootCleanup
        try {
            result = a.play(gesture)
            f.touchActive = false
            f.denied = true
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
        val phases = mutableListOf<RootSessionPhase>()
        var failStart = false
        var denied = false
        @Volatile var touchActive = false
        val latest get() = helpers.last()
        private val runner = RootCommandRunner { command, _ ->
            when {
                denied -> RootCommandResult.Failure("permission denied")
                command == "id -u" -> RootCommandResult.Success("0")
                command.contains("/proc/${latest.pid}/stat") && latest.dead ->
                    RootCommandResult.Success("DELTA_GONE ${latest.pid}")
                command.contains("dumpsys input") ->
                    RootCommandResult.Success(RootRecoveryProbeTest.idle.let {
                        if (touchActive) it.replace("<no displays touched>", "active touch") else it
                    })
                else -> RootCommandResult.Failure("unexpected command")
            }
        }
        val backend = RootBackend(
            runner, "mock.apk", 1000, RootPermissionManager(runner), journal,
            openConnection = { _, _, _, sessionId ->
                if (failStart) null else {
                    val helper = FakeHelper(helpers.size + 100) { active -> touchActive = active }
                    helpers += helper
                    helper.reply("READY ${RootSequenceProtocol.VERSION} $sessionId ${helper.pid} 12345")
                    RootGestureConnection(helper, null).also { assertTrue(it.awaitReady(sessionId)) }
                }
            },
            reportSession = { phases.add(it.phase) },
        ) { ScreenSize(100, 100) }

        fun dispatchLockIsFree(): Boolean {
            if (!inputDispatchMutex.tryLock()) return false
            inputDispatchMutex.unlock()
            return true
        }
    }

    private class FakeHelper(val pid: Int, val setTouch: (Boolean) -> Unit) : Process() {
        private val output = ReplyStream()
        @Volatile var crashOnPlay = false
        @Volatile var holdClose = false
        @Volatile var dead = false
        @Volatile var stdinClosed = false
        @Volatile var plays = 0
        val closing = CompletableDeferred<Unit>()
        private val commands = object : OutputStream() {
            private val line = StringBuilder()
            override fun write(value: Int) {
                if (value != '\n'.code) {
                    line.append(value.toChar())
                    return
                }
                if (line.startsWith("PLAY ")) {
                    plays++
                    setTouch(true)
                    if (crashOnPlay) finish() else {
                        setTouch(false)
                        reply("DONE OK 100 145")
                    }
                }
                line.clear()
            }
            override fun close() {
                stdinClosed = true
                closing.complete(Unit)
                if (!dead && !holdClose) finish("CLOSED")
            }
        }
        fun reply(message: String) = output.offer("DELTA $message\n".toByteArray())
        fun finish(terminal: String? = null) {
            dead = true
            if (terminal != null) reply(terminal)
            output.offer(ByteArray(0))
        }
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
        private var eof = false
        fun offer(bytes: ByteArray) { messages.add(bytes) }
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
