package com.huanhren.deltaharmonica.input

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RootGestureConnectionTest {
    private val note = RootSequenceStep(0, listOf(RootStroke(1f, 2f, 0, 45)))

    @Test fun aDelayedPreviousOperationCannotCompleteOrAbortTheCurrentNote() = fixture(
        peer = {
            acceptSingleNote()
            expect("SEQ 1 RUN 0")
            reply("SEQ 1 START 1000")
            reply("SEQ 1 EVENT 0 OK 1000 1000 1045")
            reply("SEQ 1 END OK 1")
            acceptSingleNote(2)
            expect("SEQ 2 RUN 0")
            reply("SEQ 2 START 2000")
            reply("SEQ 1 END STOPPED 0")
            reply("SEQ 2 END STOPPED 0", session = "cdd2c2e6-1fb4-4d31-a813-8f840c583792")
            reply("SEQ 2 END STOPPED 0", dispatcher = "62d94a72-2968-44b5-b4da-0a1be9ab93d2")
            reply("SEQ 2 EVENT 0 OK 2000 2000 2045")
            reply("SEQ 2 END OK 1")
            acknowledgeEof()
        },
        client = {
            repeat(2) {
                val result = playSequence(listOf(note), { false }, { false }, { _, _, _ -> }, {}, {})
                assertEquals(TapResult.Completed, result.result)
                assertEquals(1, result.completed)
            }
            assertEquals(RootCleanup.ACKNOWLEDGED, close())
        },
    )

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

    @Test fun stopDuringAnUnacknowledgedBlockDoesNotWaitForItsAck() {
        val stop = AtomicBoolean()
        fixture(
            peer = {
                expect("SEQ 1 QUEUE 64")
                reply("SEQ 1 ACK 0")
                assertTrue(commands.readLine().startsWith("SEQ 1 BLOCK 0 "))
                stop.set(true)
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

    @Test fun naturalCompletionCanWinAfterStopWasSent() {
        val stop = AtomicBoolean()
        fixture(
            peer = {
                acceptSingleNote()
                expect("SEQ 1 RUN 0")
                reply("SEQ 1 START 1000")
                stop.set(true)
                expect("SEQ 1 STOP")
                reply("SEQ 1 EVENT 0 OK 1000 1000 1045")
                reply("SEQ 1 END OK 1")
                acknowledgeEof()
            },
            client = {
                val result = playSequence(listOf(note), stop::get, { false }, { _, _, _ -> }, {}, {})
                assertEquals(1, result.completed)
                assertEquals(TapResult.Completed, result.result)
                assertEquals(RootCleanup.ACKNOWLEDGED, close())
                assertEquals(RootCleanup.ACKNOWLEDGED, close())
            },
        )
    }

    @Test fun atomicStopIsSentWhileTheCallerCannotProcessCallbacks() {
        val stop = AtomicBoolean()
        val receivedStop = CompletableFuture<Unit>()
        fixture(
            peer = {
                acceptSingleNote()
                expect("SEQ 1 RUN 0")
                reply("SEQ 1 START 1000")
                expect("SEQ 1 STOP")
                receivedStop.complete(Unit)
                reply("SEQ 1 END STOPPED 0")
                acknowledgeEof()
            },
            client = {
                val result = playSequence(
                    listOf(note), { false }, { false }, { _, _, _ -> }, {}, {},
                    onStarted = {
                        stop.set(true)
                        receivedStop.get(1, TimeUnit.SECONDS)
                    },
                    stopImmediately = stop::get,
                )
                assertEquals(TapResult.Cancelled, result.result)
                assertEquals(RootCleanup.ACKNOWLEDGED, close())
            },
        )
    }

    @Test fun hardwareStopNotifiesCallerBeforeReturningDespiteQueuedMainCallback() {
        val posted = CompletableFuture<() -> Unit>()
        val notifications = mutableListOf<Thread>()
        fixture(
            onVolumeDown = { notifications += Thread.currentThread() },
            postVolumeStop = { _, callback -> posted.complete(callback) },
            peer = {
                acceptSingleNote()
                expect("SEQ 1 RUN 0")
                reply("SEQ 1 START 1000")
                reply("VOLUME 1020")
                reply("SEQ 1 END STOPPED 0")
                while (true) assertEquals("SEQ 1 STOP", commands.readLine() ?: break)
                reply("CLOSED")
            },
            client = {
                val result = playSequence(listOf(note), { false }, { false }, { _, _, _ -> }, {}, {})
                assertEquals(TapResult.Cancelled, result.result)
                assertEquals(listOf(Thread.currentThread()), notifications)
                posted.get(1, TimeUnit.SECONDS).invoke()
                assertEquals(1, notifications.size)
                assertEquals(RootCleanup.ACKNOWLEDGED, close())
            },
        )
    }

    @Test fun aBlockedCommandWriterTriggersOneProcessTeardownWithoutFakingTheReceipt() = runBlocking {
        val ready = "DELTA $SESSION_ID $DISPATCHER_ID 0 READY 4 73 190892\n"
        val writing = CountDownLatch(1)
        val teardown = CountDownLatch(1)
        val destroyed = AtomicInteger()
        val answerStream = PipedInputStream(1024)
        val answers = PipedOutputStream(answerStream)
        val commands = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                if (String(bytes, off, len).trim() == "DELTA $SESSION_ID $DISPATCHER_ID 0 PING") {
                    answers.write("DELTA $SESSION_ID $DISPATCHER_ID 0 PONG\n".toByteArray())
                    return
                }
                writing.countDown()
                if (!teardown.await(5, TimeUnit.SECONDS)) error("Writer stranded")
                throw java.io.IOException("Process closed")
            }
        }
        val process = object : Process() {
            override fun getInputStream(): InputStream = answerStream
            override fun getOutputStream(): OutputStream = commands
            override fun getErrorStream(): InputStream = InputStream.nullInputStream()
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = if (destroyed.get() > 0) 0 else throw IllegalThreadStateException()
            override fun destroy() {
                destroyed.incrementAndGet()
                teardown.countDown()
                answers.close()
            }
            override fun destroyForcibly(): Process {
                destroy()
                return this
            }
        }
        val connection = RootGestureConnection(process, null, SESSION_ID)
        try {
            answers.write(ready.toByteArray())
            assertTrue(connection.awaitReady(SESSION_ID))
            val job = launch(Dispatchers.Default) {
                connection.playSequence(listOf(note), { false }, { false }, { _, _, _ -> }, {}, {})
            }
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            assertEquals(0, destroyed.get())
            assertEquals(RootCleanup.NO_ACK, connection.close())
            assertTrue(connection.transportClosed.isCompleted)
            assertTrue(teardown.await(2, TimeUnit.SECONDS))
            assertEquals(RootCleanup.NO_ACK, connection.close())
            assertEquals(1, destroyed.get())
            job.join()
        } finally {
            answers.close()
            teardown.countDown()
        }
    }

    @Test fun nativeTimeoutDuringStopQuarantinesTheDispatcherWithoutReleaseAck() {
        val stop = AtomicBoolean()
        fixture(
            peer = {
                acceptSingleNote()
                expect("SEQ 1 RUN 0")
                reply("SEQ 1 START 1000")
                stop.set(true)
                expect("SEQ 1 STOP")
                reply("FAULT NATIVE_TIMEOUT POINTERS 0,2", operation = 1)
            },
            client = {
                val result = playSequence(listOf(note), { false }, { false }, { _, _, _ -> }, {}, {}, stopImmediately = stop::get)
                assertTrue(result.result is TapResult.Unavailable)
                assertEquals(0, result.completed)
                assertEquals(RootDispatcherFault.NATIVE_TIMEOUT, dispatcherFault.await())
                assertEquals(RootDispatcherStatus.UNRESPONSIVE, dispatcherStatus)
                assertEquals(setOf(0, 2), uncertainPointerIds)
                assertEquals(RootCleanup.REJECTED, close())
                assertTrue(transportClosed.isCompleted)
            },
        )
    }

    @Test fun processExitIsDetectedEvenIfAReplyPipeWasInherited() {
        fixture(
            monitorProcess = true,
            peer = {
                exitWithoutClosingReplies()
                assertNull(commands.readLine())
                // Keep stdout open until client teardown. Holding it open defeats EOF detection alone.
                assertTrue(destroyed.await(3, TimeUnit.SECONDS))
            },
            client = {
                assertEquals(RootDispatcherFault.PEER_EXITED, dispatcherFault.await())
                assertEquals(RootCleanup.NO_ACK, close())
                assertTrue(transportClosed.isCompleted)
            },
        )
    }

    @Test fun helperWatchdogReportsTransportFailureAfterReleasingTouch() = fixture(
        peer = {
            acceptSingleNote()
            expect("SEQ 1 RUN 0")
            reply("SEQ 1 START 1000")
            reply("SEQ 1 EVENT 0 TRANSPORT 1000 1000 1045")
            reply("SEQ 1 END TRANSPORT 0")
            acknowledgeEof()
        },
        client = {
            val result = playSequence(listOf(note), { false }, { false }, { _, _, _ ->
                error("Watchdog must not advance progress")
            }, {}, {})
            assertEquals(0, result.completed)
            assertEquals(TapResult.Unavailable("Root helper 通信中断或心跳超时，已停止演奏"), result.result)
            assertEquals(RootCleanup.ACKNOWLEDGED, close())
        },
    )

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

    @Test fun eofBeforeRunWhilePausedWhileResumingDuringStopAndAfterUpRemainsUnconfirmed() {
        for (exitAt in listOf("UPLOAD", "RUN", "PAUSED", "RESUME", "STOP", "AFTER_UP")) {
            val stop = AtomicBoolean()
            val pause = AtomicBoolean(exitAt in listOf("PAUSED", "RESUME"))
            fixture(
                peer = {
                    if (exitAt == "UPLOAD") {
                        expect("SEQ 1 QUEUE 1")
                        return@fixture
                    }
                    acceptSingleNote()
                    expect("SEQ 1 RUN ${if (pause.get()) 1 else 0}")
                    if (exitAt == "RUN") return@fixture
                    reply("SEQ 1 START 1000")
                    when (exitAt) {
                        "PAUSED" -> reply("SEQ 1 PAUSED")
                        "RESUME" -> {
                            reply("SEQ 1 PAUSED")
                            expect("SEQ 1 RESUME")
                        }
                        "STOP" -> {
                            stop.set(true)
                            expect("SEQ 1 STOP")
                        }
                        "AFTER_UP" -> reply("SEQ 1 EVENT 0 OK 1000 1000 1045")
                    }
                },
                client = {
                    val result = playSequence(listOf(note), stop::get, pause::get, { _, _, _ -> },
                        { if (exitAt == "RESUME") pause.set(false) }, {})
                    assertTrue(exitAt, result.result is TapResult.Unavailable)
                    assertEquals(exitAt, if (exitAt == "AFTER_UP") 1 else 0, result.completed)
                    assertEquals(exitAt, RootCleanup.NO_ACK, close())
                },
            )
        }
    }

    @Test fun missingEndGetsABoundedDeadlineEvenWhenHelperKeepsResponding() = fixture(
        peer = {
            acceptSingleNote()
            expect("SEQ 1 RUN 0")
            reply("SEQ 1 START 1000")
            reply("SEQ 1 EVENT 0 OK 1000 1000 1045")
            while (true) {
                val command = commands.readLine() ?: break
                assertEquals("SEQ 1 PING", command)
                reply("SEQ 1 PONG")
            }
            reply("CLOSED")
        },
        client = {
            val result = playSequence(listOf(note), { false }, { false }, { _, _, _ -> }, {}, {})
            assertEquals(1, result.completed)
            assertEquals(TapResult.Unavailable("Root helper 未在期限内确认演奏进度或结束"), result.result)
            assertEquals(RootCleanup.ACKNOWLEDGED, close())
        },
    )

    @Test fun replySaturationRequestsEofWhileTheCallerIsBlocked() {
        val eof = CompletableFuture<Unit>()
        fixture(
            peer = {
                acceptSingleNote()
                expect("SEQ 1 RUN 0")
                reply("SEQ 1 START 1000")
                repeat(RootSequenceProtocol.BUFFER_SIZE + 8) { reply("SEQ 1 START 1045") }
                acknowledgeEof()
                eof.complete(Unit)
            },
            client = {
                val result = playSequence(listOf(note), { false }, { false }, { _, _, _ ->
                    error("An unrelated session advanced progress")
                }, {}, {}, onStarted = { eof.get(2, TimeUnit.SECONDS) })
                assertEquals(0, result.completed)
                assertTrue(result.result is TapResult.Unavailable)
                assertEquals(RootCleanup.ACKNOWLEDGED, close())
            },
        )
    }

    @Test fun repeatedStopAndPauseResumePreserveTheReusableConnection() {
        val stop = AtomicBoolean()
        val pause = AtomicBoolean()
        fixture(timeoutMs = 15_000,
            peer = {
                for (id in 1..200) {
                    acceptSingleNote(id)
                    expect("SEQ $id RUN ${if (id <= 100) 0 else 1}")
                    reply("SEQ $id START 1000")
                    if (id <= 100) {
                        stop.set(true)
                        expect("SEQ $id STOP")
                        reply("SEQ $id END STOPPED 0")
                    } else {
                        reply("SEQ $id PAUSED")
                        expect("SEQ $id RESUME")
                        reply("SEQ $id RESUMED")
                        reply("SEQ $id EVENT 0 OK 1000 1000 1045")
                        reply("SEQ $id END OK 1")
                    }
                }
                acknowledgeEof()
            },
            client = {
                repeat(200) { index ->
                    stop.set(false)
                    pause.set(index >= 100)
                    val result = playSequence(listOf(note), stop::get, pause::get, { _, _, _ -> }, { pause.set(false) }, {})
                    assertEquals("sequence ${index + 1}", if (index < 100) TapResult.Cancelled else TapResult.Completed, result.result)
                }
                assertEquals(RootCleanup.ACKNOWLEDGED, close())
            },
        )
    }

    @Test fun helperReportsKnownUnreleasedPointersOnlyInTheTerminalReceipt() = fixture(
        peer = {
            assertNull(commands.readLine())
            reply("UNCONFIRMED POINTERS 0,2")
        },
        client = {
            assertNull(uncertainPointerIds)
            assertEquals(RootCleanup.REJECTED, close())
            assertEquals(setOf(0, 2), uncertainPointerIds)
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

    private fun fixture(
        timeoutMs: Long = 5_000,
        onVolumeDown: (() -> Unit)? = null,
        postVolumeStop: (String, () -> Unit) -> Unit = { _, _ -> error("Unexpected volume reply") },
        monitorProcess: Boolean = false,
        peer: FakeHelper.() -> Unit,
        client: suspend RootGestureConnection.() -> Unit,
    ) {
        val helper = FakeHelper()
        val served = CompletableFuture<Unit>()
        val connection = RootGestureConnection(helper, onVolumeDown, SESSION_ID, postVolumeStop, monitorProcess)
        Thread({
            try {
                helper.reply("READY 4 73 190892")
                helper.expect("PING")
                helper.reply("PONG")
                helper.peer()
                served.complete(Unit)
            } catch (failure: Throwable) {
                served.completeExceptionally(failure)
            } finally {
                helper.output.close()
            }
        }, "RootProtocolTestPeer").apply { isDaemon = true; start() }
        try {
            runBlocking {
                withTimeout(timeoutMs) {
                    assertTrue(connection.awaitReady(SESSION_ID))
                    connection.client()
                }
            }
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
        val commands = object : BufferedReader(InputStreamReader(commandReader)) {
            override fun readLine(): String? {
                val line = super.readLine() ?: return null
                val fields = line.split(' ', limit = 5)
                assertEquals(listOf("DELTA", SESSION_ID, DISPATCHER_ID), fields.take(3))
                val payload = fields[4]
                val expectedOperation = if (payload.startsWith("SEQ ")) payload.split(' ')[1] else "0"
                assertEquals(expectedOperation, fields[3])
                return payload
            }
        }
        val output = replyWriter.bufferedWriter()
        val destroyed = CountDownLatch(1)
        @Volatile private var exited = false

        fun exitWithoutClosingReplies() { exited = true }

        fun expect(line: String) { assertEquals(line, commands.readLine()) }
        fun reply(
            payload: String,
            operation: Long = if (payload.startsWith("SEQ ")) payload.split(' ')[1].toLong() else 0,
            session: String = SESSION_ID,
            dispatcher: String = DISPATCHER_ID,
        ) {
            output.write("DELTA $session $dispatcher $operation $payload")
            output.newLine()
            output.flush()
        }

        fun acceptSingleNote(id: Int = 1) {
            expect("SEQ $id QUEUE 1")
            reply("SEQ $id ACK 0")
            assertTrue(commands.readLine().startsWith("SEQ $id BLOCK 0 "))
            reply("SEQ $id ACK 1")
        }

        fun acknowledgeEof() {
            assertNull(commands.readLine())
            reply("CLOSED")
        }

        override fun getOutputStream(): OutputStream = commandWriter
        override fun getInputStream(): InputStream = replyReader
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (exited) 0 else throw IllegalThreadStateException()
        override fun destroy() {
            exited = true
            // End of writer sends EOF to the corresponding reader.
            runCatching { replyWriter.close() }
            runCatching { commandWriter.close() }
            destroyed.countDown()
        }
    }

    private companion object {
        const val SESSION_ID = "2a1e96c6-c578-4f21-92ed-b4dcbbe13caa"
        const val DISPATCHER_ID = "72024017-6f2f-4cc5-b52e-44d78d12a3f7"
    }
}
