package com.huanhren.deltaharmonica.input

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RootGestureConnectionTest {
    private val note = RootSequenceStep(0, listOf(RootStroke(1f, 2f, 0, 45)))

    @Test fun stoppingDuringUploadDoesNotCommitPartialTimeline() {
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        fixture(
            peer = {
                expect("SEQ 1 QUEUE 64")
                reply("SEQ 1 ACK 0")
                assertTrue(commands.readLine().startsWith("SEQ 1 BLOCK 0 "))
                stop.set(true)
                reply("SEQ 1 ACK 32")
                expect("SEQ 1 STOP")
                reply("SEQ 1 END STOPPED 0")
                acknowledgeEof()
            },
            client = {
                val result = playSequence(List(64) { note }, stop::get, { false }, { _, _, _ -> }, {}, {})
                assertEquals(0, result.completed)
                assertEquals(TapResult.Cancelled, result.result)
                assertEquals(RootCleanup.ACKNOWLEDGED, close())
            },
        )
    }

    @Test fun outOfOrderEventsInvalidateTheSequenceAndRequestCleanup() = fixture(
        peer = {
            acceptSingleNote()
            expect("SEQ 1 RUN 0")
            reply("SEQ 1 START 1000")
            reply("SEQ 1 EVENT 1 OK 1000 1003 1048")
            acknowledgeEof()
        },
        client = {
            val result = playSequence(listOf(note), { false }, { false }, { _, _, _ ->
                error("An invalid event must never advance progress")
            }, {}, {})
            assertEquals(0, result.completed)
            assertTrue(result.result is TapResult.Unavailable)
            assertEquals(RootCleanup.ACKNOWLEDGED, close())
        },
    )

    @Test fun deniedResumeStopsAtTheReleasedBoundary() {
        val pause = java.util.concurrent.atomic.AtomicBoolean(true)
        fixture(
            peer = {
                acceptSingleNote()
                expect("SEQ 1 RUN 1")
                reply("SEQ 1 START 1000")
                reply("SEQ 1 PAUSED")
                expect("SEQ 1 STOP")
                reply("SEQ 1 END STOPPED 0")
                acknowledgeEof()
            },
            client = {
                val result = playSequence(
                    listOf(note), { false }, pause::get, { _, _, _ -> error("Unexpected note") },
                    { pause.set(false) }, { error("Unverified resume") },
                    beforeResume = { false },
                )
                assertEquals(0, result.completed)
                assertTrue(result.result is TapResult.Unavailable)
                assertEquals(RootCleanup.ACKNOWLEDGED, close())
            },
        )
    }

    @Test fun aLostProcessCannotFabricateAReleaseAcknowledgment() = fixture(
        peer = {
            acceptSingleNote()
            expect("SEQ 1 RUN 0")
            reply("SEQ 1 START 1000")
        },
        client = {
            val result = playSequence(listOf(note), { false }, { false }, { _, _, _ -> }, {}, {})
            assertTrue(result.result is TapResult.Unavailable)
            assertEquals(RootCleanup.NO_ACK, close())
        },
    )

    @Test fun confirmedSequenceUsesThePauseAdjustedDeadlineForLateness() {
        val measurements = mutableListOf<List<Long>>()
        fixture(
            peer = {
                acceptSingleNote()
                expect("SEQ 1 RUN 0")
                reply("SEQ 1 START 1000")
                reply("SEQ 1 EVENT 0 OK 1300 1303 1348")
                reply("SEQ 1 END OK 1")
                acknowledgeEof()
            },
            client = {
                val result = playSequence(
                    listOf(note), { false }, { false }, { _, _, _ -> }, {}, {},
                    onTiming = { index, planned, down, up -> measurements += listOf(index.toLong(), planned, down, up) },
                )
                assertEquals(TapResult.Completed, result.result)
                assertEquals(1, result.completed)
                assertEquals(3L, result.maxLatenessMs)
                assertEquals(RootCleanup.ACKNOWLEDGED, close())
            },
        )
        assertEquals(listOf(listOf(0L, 1300L, 1303L, 1348L)), measurements)
    }

    private fun fixture(peer: FakeHelper.() -> Unit, client: suspend RootGestureConnection.() -> Unit) {
        val helper = FakeHelper()
        val served = CompletableFuture<Unit>()
        val connection = RootGestureConnection(helper, null)
        Thread({
            try {
                helper.peer()
                served.complete(Unit)
            } catch (failure: Throwable) {
                served.completeExceptionally(failure)
            } finally {
                helper.output.close()
            }
        }, "RootProtocolTestPeer").apply { isDaemon = true; start() }
        try {
            runBlocking { withTimeout(5_000) { connection.client() } }
            served.get(2, TimeUnit.SECONDS)
        } finally {
            helper.destroy()
        }
    }

    private class FakeHelper : Process() {
        private val commandReader = PipedInputStream(32_768)
        private val commandWriter = PipedOutputStream(commandReader)
        private val replyReader = PipedInputStream(32_768)
        private val replyWriter = PipedOutputStream(replyReader)
        val commands = commandReader.bufferedReader()
        val output = replyWriter.bufferedWriter()

        fun expect(line: String) { assertEquals(line, commands.readLine()) }
        fun reply(payload: String) {
            output.write("DELTA $payload")
            output.newLine()
            output.flush()
        }

        fun acceptSingleNote() {
            expect("SEQ 1 QUEUE 1")
            reply("SEQ 1 ACK 0")
            assertTrue(commands.readLine().startsWith("SEQ 1 BLOCK 0 "))
            reply("SEQ 1 ACK 1")
        }

        fun acknowledgeEof() {
            assertNull(commands.readLine())
            reply("CLOSED")
        }

        override fun getOutputStream(): OutputStream = commandWriter
        override fun getInputStream(): InputStream = replyReader
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {
            runCatching { commandReader.close() }
            runCatching { replyReader.close() }
            runCatching { commandWriter.close() }
            runCatching { replyWriter.close() }
        }
    }
}
