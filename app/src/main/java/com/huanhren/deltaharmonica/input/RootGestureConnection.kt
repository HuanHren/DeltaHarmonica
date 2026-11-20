package com.huanhren.deltaharmonica.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.huanhren.deltaharmonica.playback.CombinedGesture
import com.huanhren.deltaharmonica.playback.ContinuousPlaybackResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class RootGestureConnection(
    process: Process,
    private val onVolumeDown: (() -> Unit)?,
) {
    private val commands = RootCommandPipe(process.outputStream)
    private val replies = Channel<String>(RootSequenceProtocol.BUFFER_SIZE)
    private val shutdown = CompletableDeferred<RootCleanup>()
    private val gate = Mutex()
    private var nextSequence = 0L
    private val activeSequence = AtomicLong()
    private val lastPongNanos = AtomicLong()
    private var usable = true
    @Volatile private var volumeStopped = false

    init {
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("DELTA ")) continue
                        val reply = line.removePrefix("DELTA ")
                        when {
                            reply == "CLOSED" -> shutdown.complete(RootCleanup.ACKNOWLEDGED)
                            reply == "UNCONFIRMED" -> shutdown.complete(RootCleanup.REJECTED)
                            reply.startsWith("VOLUME ") -> {
                                volumeStopped = true
                                Log.i(TAG, "volume press t=${reply.substringAfter(' ')}")
                                Handler(Looper.getMainLooper()).post { onVolumeDown?.invoke() }
                            }
                            reply == "NO_VOLUME_MONITOR" -> Log.w(TAG, "built-in volume key monitor unavailable")
                            reply == "VOLUME_READY" -> Log.i(TAG, "built-in volume key monitor initialized")
                            reply.startsWith("SEQ ") && reply.endsWith(" PONG") -> {
                                if (reply == "SEQ ${activeSequence.get()} PONG") {
                                    lastPongNanos.set(System.nanoTime())
                                }
                            }
                            else -> if (replies.trySend(reply).isFailure) abortPipe()
                        }
                    }
                }
            } catch (_: IOException) {
                // A lost pipe is reported by the receiver as an uncertain release.
            } finally {
                abortPipe()
                shutdown.complete(RootCleanup.NO_ACK)
            }
        }, "DeltaRootResponses").apply { isDaemon = true; start() }
    }

    private fun abortPipe() {
        replies.close()
        commands.close()
    }

    suspend fun play(
        gesture: CombinedGesture,
        stop: () -> Boolean = { false },
        onOutcome: (RootTouchOutcome) -> Unit = {},
    ): TapResult {
        if (!gate.tryLock()) return TapResult.Busy
        var outstanding = false
        try {
            if (!usable) return TapResult.Unavailable("Root 触摸连接失效，请停止本次操作")
            if (volumeStopped) onVolumeDown?.invoke()
            if (stop() || volumeStopped) return TapResult.Cancelled
            val strokes = gesture.strokes.map { RootStroke(it.point.x, it.point.y, it.startMs, it.durationMs) }
            outstanding = true
            if (!commands.send("PLAY ${RootSequenceProtocol.encodeStrokes(strokes)}")) {
                return TapResult.Unavailable("Root 触摸命令写入失败")
            }
            val timeout = gesture.strokes.maxOf { it.startMs + it.durationMs } + 1_500
            val reply = coroutineScope {
                val watcher = launch {
                    while (!stop() && !volumeStopped) delay(12)
                    if (!commands.send("STOP")) abortPipe()
                }
                try { receive(timeout) } finally { watcher.cancel() }
            } ?: return TapResult.TimedOut
            val fields = reply.split(' ', limit = 5)
            if (fields.size != 4 || fields[0] != "DONE") {
                return TapResult.Unavailable("Root 触摸响应无效，释放待确认")
            }
            val down = fields[2].toLongOrNull()
            val up = fields[3].toLongOrNull()
            if (down == null || up == null || (fields[1] == "OK" && (down < 0 || up < down))) {
                return TapResult.Unavailable("Root 触摸时间响应无效，释放待确认")
            }
            outstanding = false
            val outcome = RootTouchOutcome(
                fields[1],
                down.takeIf { it >= 0 },
                up.takeIf { it >= 0 },
            )
            if (volumeStopped) onVolumeDown?.invoke()
            onOutcome(outcome)
            return when (outcome.status) {
                "OK" -> TapResult.Completed
                "STOPPED" -> TapResult.Cancelled
                "FAILED" -> TapResult.Rejected
                else -> {
                    usable = false
                    TapResult.Unavailable("Root 触摸失败，释放待确认")
                }
            }
        } finally {
            try {
                if (outstanding) withContext(NonCancellable) {
                    usable = false
                    abortPipe()
                }
            } finally { gate.unlock() }
        }
    }

    /** Sends one acknowledged block at a time. Only a completely uploaded plan can start. */
    internal suspend fun playSequence(
        steps: List<RootSequenceStep>,
        stop: () -> Boolean,
        pause: () -> Boolean,
        onNote: (Int, Long?, Long?) -> Unit,
        onPaused: () -> Unit,
        onResumed: () -> Unit,
        onStarted: (Long) -> Unit = {},
        beforeResume: suspend () -> Boolean = { true },
        onTiming: (Int, Long, Long, Long) -> Unit = { _, _, _, _ -> },
    ): ContinuousPlaybackResult {
        if (!gate.tryLock()) return ContinuousPlaybackResult(0, TapResult.Busy)
        var outstanding = false
        try {
            if (!usable) return ContinuousPlaybackResult(0, TapResult.Unavailable("Root 输入连接已失效"))
            if (steps.size !in 1..RootSequenceProtocol.MAX_NOTES) {
                return ContinuousPlaybackResult(0, TapResult.Unavailable("连续演奏音符数量无效"))
            }
            if (stop() || volumeStopped) return ContinuousPlaybackResult(0, TapResult.Cancelled)
            val id = ++nextSequence
            activeSequence.set(id)
            outstanding = true

            suspend fun sendAndAck(command: String, expected: Int): TapResult? {
                if (!commands.send("SEQ $id $command")) return TapResult.Unavailable("连续触控计划发送失败")
                val reply = receive(RootSequenceProtocol.ACK_TIMEOUT_MS)
                return when (reply) {
                    "SEQ $id ACK $expected" -> null
                    "SEQ $id END STOPPED 0" -> TapResult.Cancelled
                    "SEQ $id END FAILED 0" -> TapResult.Unavailable("Root 触控计划未通过校验")
                    else -> TapResult.Unavailable("Root 触控计划确认丢失或顺序错误")
                }
            }

            sendAndAck("QUEUE ${steps.size}", 0)?.let {
                return ContinuousPlaybackResult(0, it)
            }
            var first = 0
            while (first < steps.size && !stop() && !volumeStopped) {
                val last = minOf(first + RootSequenceProtocol.CHUNK_SIZE, steps.size)
                val payload = steps.subList(first, last).joinToString("~", transform = RootSequenceProtocol::encode)
                sendAndAck("BLOCK $first $payload", last)?.let { return ContinuousPlaybackResult(0, it) }
                first = last
            }
            if (stop() || volumeStopped) {
                val sent = commands.send("SEQ $id STOP")
                outstanding = !sent || receive(RootSequenceProtocol.ACK_TIMEOUT_MS) != "SEQ $id END STOPPED 0"
                return ContinuousPlaybackResult(0, TapResult.Cancelled)
            }
            val initialPause = pause()
            if (!commands.send("SEQ $id RUN ${if (initialPause) 1 else 0}")) {
                return ContinuousPlaybackResult(0, TapResult.Unavailable("Root 连续调度启动失败"))
            }
            val result = receiveSequence(id, steps.size, initialPause, stop, pause,
                onNote, onPaused, onResumed, onStarted, beforeResume, onTiming)
            outstanding = !result.terminal
            if (result.playback.result is TapResult.Unavailable) usable = false
            return result.playback
        } finally {
            activeSequence.set(0)
            try {
                if (outstanding) {
                    usable = false
                    // EOF cancels partial uploads and stops a worker even after its acknowledgments were lost.
                    abortPipe()
                }
            } finally { gate.unlock() }
        }
    }

    private data class SequenceReceipt(val playback: ContinuousPlaybackResult, val terminal: Boolean)

    private suspend fun receiveSequence(
        id: Long,
        noteCount: Int,
        initialPause: Boolean,
        stop: () -> Boolean,
        pause: () -> Boolean,
        onNote: (Int, Long?, Long?) -> Unit,
        onPaused: () -> Unit,
        onResumed: () -> Unit,
        onStarted: (Long) -> Unit,
        beforeResume: suspend () -> Boolean,
        onTiming: (Int, Long, Long, Long) -> Unit,
    ): SequenceReceipt = coroutineScope {
        val helperPaused = AtomicBoolean()
        val resumeDenied = AtomicBoolean()
        val lastControlNanos = AtomicLong(System.nanoTime())
        lastPongNanos.set(System.nanoTime())
        val heartbeats = launch(Dispatchers.IO) {
            while (true) {
                delay(500)
                val now = System.nanoTime()
                if (now - lastPongNanos.get() > TimeUnit.SECONDS.toNanos(4) ||
                    now - lastControlNanos.get() > TimeUnit.SECONDS.toNanos(1) ||
                    !commands.send("SEQ $id PING")) {
                    abortPipe()
                    break
                }
            }
        }
        val controls = launch {
            var desiredSent = initialPause
            var validation: Deferred<Boolean>? = null
            suspend fun requestStop() {
                validation?.cancel()
                if (!commands.send("SEQ $id STOP")) {
                    abortPipe()
                    return
                }
                delay(RootSequenceProtocol.ACK_TIMEOUT_MS)
                abortPipe()
            }
            while (true) {
                lastControlNanos.set(System.nanoTime())
                if (stop() || volumeStopped) {
                    requestStop()
                    break
                }
                if (pause()) {
                    validation?.cancel()
                    validation = null
                    if (!desiredSent) {
                        if (!commands.send("SEQ $id PAUSE")) { abortPipe(); break }
                        desiredSent = true
                    }
                } else if (desiredSent && helperPaused.get()) {
                    val check = validation ?: async {
                        try { beforeResume() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { false }
                    }.also { validation = it }
                    if (check.isCompleted) {
                        if (!check.await()) {
                            resumeDenied.set(true)
                            requestStop()
                            break
                        }
                        if (!commands.send("SEQ $id RESUME")) { abortPipe(); break }
                        desiredSent = false
                        validation = null
                    }
                }
                delay(12)
            }
        }
        var observed = 0
        var origin: Long? = null
        var failedEvent: String? = null
        var maxLatenessMs = 0L
        try {
            val prefix = "SEQ $id "
            for (message in replies) {
                if (!message.startsWith(prefix)) break
                val parts = message.removePrefix(prefix).split(' ', limit = 8)
                when (parts[0]) {
                    "PAUSED" -> {
                        if (parts.size != 1 || origin == null || !helperPaused.compareAndSet(false, true)) break
                        onPaused()
                    }
                    "RESUMED" -> {
                        if (parts.size != 1 || origin == null || !helperPaused.compareAndSet(true, false)) break
                        onResumed()
                    }
                    "START" -> {
                        if (parts.size != 2 || origin != null) break
                        val time = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: break
                        origin = time
                        onStarted(time)
                    }
                    "EVENT" -> {
                        if (parts.size != 6 || observed >= noteCount || parts[1].toIntOrNull() != observed ||
                            failedEvent != null || helperPaused.get()) break
                        val began = origin ?: break
                        val planned = parts[3].toLongOrNull()?.takeIf { it >= began } ?: break
                        val down = parts[4].toLongOrNull() ?: break
                        val up = parts[5].toLongOrNull() ?: break
                        if (parts[2] == "OK") {
                            if (down < planned || up < down) break
                            maxLatenessMs = maxOf(maxLatenessMs, down - planned)
                            onTiming(observed, planned, down, up)
                            onNote(observed, down, up)
                            observed++
                        } else {
                            if (parts[2] !in listOf("STOPPED", "FAILED", "UNCONFIRMED", "LATE")) break
                            failedEvent = parts[2]
                        }
                    }
                    "END" -> {
                        if (parts.size != 3 || parts[2].toIntOrNull() != observed) break
                        val status = parts[1]
                        if (failedEvent != null && status != failedEvent) break
                        val result = when (status) {
                            "OK" -> if (origin != null && observed == noteCount) TapResult.Completed else break
                            "STOPPED" -> if (resumeDenied.get()) TapResult.Unavailable("恢复前无法确认游戏环境或控制状态")
                                else TapResult.Cancelled
                            "FAILED" -> TapResult.Rejected
                            "LATE" -> TapResult.Unavailable("Root 触控超过允许的迟到时间，已停止后续音符")
                            "UNCONFIRMED" -> TapResult.Unavailable("Root 释放状态待确认")
                            else -> break
                        }
                        return@coroutineScope SequenceReceipt(ContinuousPlaybackResult(observed, result, maxLatenessMs), true)
                    }
                    else -> break
                }
            }
            SequenceReceipt(ContinuousPlaybackResult(observed,
                TapResult.Unavailable("Root 连续调度通信中断或响应无效"), maxLatenessMs), false)
        } finally {
            heartbeats.cancel()
            controls.cancel()
        }
    }

    private suspend fun receive(timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) { replies.receiveCatching().getOrNull() }

    /** EOF requests worker termination. Only the terminal message confirms cleanup. */
    suspend fun close(): RootCleanup = withContext(NonCancellable) {
        commands.close()
        withTimeoutOrNull(RootSequenceProtocol.ACK_TIMEOUT_MS) { shutdown.await() } ?: RootCleanup.NO_ACK
    }

    companion object {
        private const val TAG = "DeltaRoot"

        suspend fun open(apk: String, targetUid: Int, onVolumeDown: (() -> Unit)?): RootGestureConnection? {
            val volumeArgument = if (onVolumeDown != null) " volume" else ""
            val command = "CLASSPATH=${shellQuote(apk)} exec /system/bin/app_process /system/bin ${RootTouchMain::class.java.name} $targetUid$volumeArgument"
            var connection: RootGestureConnection? = null
            var connected = false
            try {
                withContext(Dispatchers.IO) {
                    connection = RootGestureConnection(
                        ProcessBuilder("su", "-c", command).redirectErrorStream(true).start(),
                        onVolumeDown,
                    )
                }
                connected = connection?.receive(3_000) == "READY ${RootSequenceProtocol.VERSION}"
                return connection?.takeIf { connected }
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            } finally {
                if (!connected) connection?.close()
            }
        }
    }
}

/** Bounded writer: a stuck OS pipe cannot suspend the caller or its stop watcher indefinitely. */
private class RootCommandPipe(private val stream: OutputStream) {
    private data class Command(val text: String, val sent: CompletableDeferred<Boolean>)

    private val pending = ArrayBlockingQueue<Command>(4)
    private val closed = AtomicBoolean()

    init {
        Thread({
            val writer = stream.bufferedWriter()
            try {
                while (!closed.get()) {
                    val command = pending.poll(25L, TimeUnit.MILLISECONDS) ?: continue
                    if (closed.get()) {
                        command.sent.complete(false)
                        break
                    }
                    try {
                        writer.write(command.text)
                        writer.newLine()
                        writer.flush()
                        command.sent.complete(true)
                    } catch (_: IOException) {
                        command.sent.complete(false)
                        break
                    }
                }
            } finally {
                closed.set(true)
                runCatching { stream.close() }
                while (true) {
                    val abandoned = pending.poll() ?: break
                    abandoned.sent.complete(false)
                }
            }
        }, "DeltaRootCommands").apply { isDaemon = true; start() }
    }

    suspend fun send(text: String): Boolean {
        if (closed.get()) return false
        val sent = CompletableDeferred<Boolean>()
        if (!pending.offer(Command(text, sent))) {
            close()
            return false
        }
        val confirmed = withTimeoutOrNull(RootSequenceProtocol.ACK_TIMEOUT_MS) { sent.await() } == true
        if (!confirmed) close()
        return confirmed
    }

    fun close() { closed.set(true) }
}
