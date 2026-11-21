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
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class RootGestureConnection(
    private val process: Process,
    private val onVolumeDown: (() -> Unit)?,
    private val postVolumeStop: (String, () -> Unit) -> Unit = { timestamp, callback ->
        Log.i(TAG, "volume press t=$timestamp")
        Handler(Looper.getMainLooper()).post { callback() }
    },
) {
    private val commands = RootCommandPipe(process.outputStream)
    private val replies = Channel<String>(RootSequenceProtocol.BUFFER_SIZE)
    private val shutdown = CompletableDeferred<RootCleanup>()
    private val terminationRequested = AtomicBoolean()
    private val gate = Mutex()
    private var nextSequence = 0L
    private val activeSequence = AtomicLong()
    private val lastPongNanos = AtomicLong()
    @Volatile private var usable = true
    @Volatile private var volumeStopped = false
    private val volumeStopNotified = AtomicBoolean()
    @Volatile var helperIdentity: RootHelperIdentity? = null
        private set
    @Volatile var uncertainPointerIds: Set<Int>? = null
        private set

    init {
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("DELTA ")) continue
                        val reply = line.removePrefix("DELTA ")
                        when {
                            reply == "CLOSED" -> {
                                shutdown.complete(RootCleanup.ACKNOWLEDGED)
                                break
                            }
                            reply == "UNCONFIRMED" -> {
                                shutdown.complete(RootCleanup.REJECTED)
                                break
                            }
                            reply.startsWith("UNCONFIRMED POINTERS ") -> {
                                val fields = reply.removePrefix("UNCONFIRMED POINTERS ").split(',', limit = 4)
                                val pointers = fields.mapNotNull { it.toIntOrNull()?.takeIf { id -> id in 0..2 } }.toSet()
                                if (pointers.size == fields.size) {
                                    uncertainPointerIds = pointers
                                    shutdown.complete(RootCleanup.REJECTED)
                                }
                                break
                            }
                            reply.startsWith("VOLUME ") -> {
                                volumeStopped = true
                                postVolumeStop(reply.substringAfter(' '), ::notifyVolumeStop)
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
        usable = false
        replies.close()
        commands.close()
    }

    private fun notifyVolumeStop() {
        if (volumeStopped && volumeStopNotified.compareAndSet(false, true)) onVolumeDown?.invoke()
    }

    private enum class StopReason { USER, RESUME_DENIED }

    private suspend fun monitorStop(
        command: String,
        request: CompletableDeferred<StopReason>,
        stopImmediately: () -> Boolean,
    ) {
        while (!request.isCompleted) {
            if (volumeStopped || stopImmediately()) request.complete(StopReason.USER)
            else delay(12)
        }
        if (commands.send(command, priority = true) == CommandWrite.SENT) {
            delay(RootSequenceProtocol.ACK_TIMEOUT_MS)
        }
        abortPipe()
    }

    suspend fun play(
        gesture: CombinedGesture,
        stop: () -> Boolean = { false },
        stopImmediately: () -> Boolean = { false },
        onOutcome: (RootTouchOutcome) -> Unit = {},
    ): TapResult {
        if (!gate.tryLock()) return TapResult.Busy
        var outstanding = false
        try {
            if (!usable || helperIdentity == null) return TapResult.Unavailable("Root 触摸连接失效，请停止本次操作")
            notifyVolumeStop()
            if (stop() || volumeStopped) return TapResult.Cancelled
            val strokes = gesture.strokes.map { RootStroke(it.point.x, it.point.y, it.startMs, it.durationMs) }
            val reply = coroutineScope {
                val request = CompletableDeferred<StopReason>()
                val local = launch {
                    while (!stop() && !volumeStopped) delay(12)
                    request.complete(StopReason.USER)
                }
                val watcher = launch(Dispatchers.IO) {
                    monitorStop("STOP", request, stopImmediately)
                }
                try {
                    outstanding = true
                    when (commands.send("PLAY ${RootSequenceProtocol.encodeStrokes(strokes)}", unlessStopped = request)) {
                        CommandWrite.SENT -> {
                            val timeout = gesture.strokes.maxOf { it.startMs + it.durationMs } + 1_500
                            val receipt = withTimeoutOrNull(timeout) { replies.receiveCatching() }
                            if (receipt == null) null else receipt.getOrNull() ?: "PEER_CLOSED"
                        }
                        CommandWrite.SUPERSEDED -> "DONE STOPPED -1 -1"
                        CommandWrite.FAILED -> "WRITE_FAILED"
                    }
                } finally {
                    local.cancel()
                    watcher.cancel()
                }
            } ?: return TapResult.TimedOut
            if (reply == "WRITE_FAILED") return TapResult.Unavailable("Root 触摸命令写入失败")
            if (reply == "PEER_CLOSED") return TapResult.Unavailable("Root helper 通信已断开，释放待确认")
            val fields = reply.split(' ', limit = 5)
            if (fields.size != 4 || fields[0] != "DONE") {
                return TapResult.Unavailable("Root 触摸响应无效，释放待确认")
            }
            val down = fields[2].toLongOrNull()
            val up = fields[3].toLongOrNull()
            if (down == null || up == null || (fields[1] == "OK" && (down < 0 || up <= down))) {
                return TapResult.Unavailable("Root 触摸时间响应无效，释放待确认")
            }
            outstanding = false
            val outcome = RootTouchOutcome(
                fields[1],
                down.takeIf { it >= 0 },
                up.takeIf { it >= 0 },
            )
            notifyVolumeStop()
            onOutcome(outcome)
            return when (outcome.status) {
                "OK" -> TapResult.Completed
                "STOPPED" -> TapResult.Cancelled
                "FAILED" -> TapResult.Rejected
                "TRANSPORT" -> TapResult.Unavailable(TRANSPORT_ERROR).also { usable = false }
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
        stopImmediately: () -> Boolean = { false },
        onReceipt: (Int, RootTouchOutcome) -> Unit = { _, _ -> },
    ): ContinuousPlaybackResult {
        if (!gate.tryLock()) return ContinuousPlaybackResult(0, TapResult.Busy)
        var outstanding = false
        try {
            if (!usable || helperIdentity == null) {
                return ContinuousPlaybackResult(0, TapResult.Unavailable("Root 输入连接已失效"))
            }
            if (steps.size !in 1..RootSequenceProtocol.MAX_NOTES) {
                return ContinuousPlaybackResult(0, TapResult.Unavailable("连续演奏音符数量无效"))
            }
            if (stop() || stopImmediately() || volumeStopped) {
                notifyVolumeStop()
                return ContinuousPlaybackResult(0, TapResult.Cancelled)
            }
            val id = ++nextSequence
            activeSequence.set(id)
            outstanding = true

            val result: SequenceReceipt = coroutineScope {
                val request = CompletableDeferred<StopReason>()
                fun checkStop() {
                    if (stop() || volumeStopped) request.complete(StopReason.USER)
                }
                val local = launch {
                    while (!request.isCompleted) {
                        checkStop()
                        delay(12)
                    }
                }
                val watcher = launch(Dispatchers.IO) {
                    monitorStop("SEQ $id STOP", request, stopImmediately)
                }
                try {
                    val upload = uploadSequence(id, steps, request, ::checkStop)
                    if (upload != null) return@coroutineScope upload
                    checkStop()
                    val initialPause = pause()
                    if (commands.send("SEQ $id RUN ${if (initialPause) 1 else 0}", unlessStopped = request) == CommandWrite.FAILED) {
                        return@coroutineScope SequenceReceipt(
                            ContinuousPlaybackResult(0, TapResult.Unavailable("Root 连续调度启动失败")), false,
                        )
                    }
                    receiveSequence(id, steps, initialPause, request, pause,
                        onNote, onPaused, onResumed, onStarted, beforeResume, onTiming, onReceipt)
                } finally {
                    local.cancel()
                    watcher.cancel()
                }
            }
            outstanding = result.terminal == false
            if (result.playback.result is TapResult.Unavailable) usable = false
            if (result.playback.result == TapResult.Cancelled) notifyVolumeStop()
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

    private suspend fun uploadSequence(
        id: Long,
        steps: List<RootSequenceStep>,
        stop: Deferred<StopReason>,
        checkStop: () -> Unit,
    ): SequenceReceipt? {
        suspend fun sendAndAck(command: String, expected: Int): SequenceReceipt? {
            checkStop()
            if (commands.send("SEQ $id $command", unlessStopped = stop) == CommandWrite.FAILED) {
                return SequenceReceipt(ContinuousPlaybackResult(0, TapResult.Unavailable("连续触控计划发送失败")), false)
            }
            while (true) {
                when (receive(RootSequenceProtocol.ACK_TIMEOUT_MS)) {
                    "SEQ $id ACK $expected" -> if (!stop.isCompleted) return null
                    "SEQ $id END STOPPED 0" -> return SequenceReceipt(ContinuousPlaybackResult(0, TapResult.Cancelled), true)
                    "SEQ $id END FAILED 0" -> return SequenceReceipt(
                        ContinuousPlaybackResult(0, TapResult.Unavailable("Root 触控计划未通过校验")), true,
                    )
                    else -> return SequenceReceipt(
                        ContinuousPlaybackResult(0, TapResult.Unavailable("Root 触控计划确认丢失或顺序错误")), false,
                    )
                }
            }
        }

        sendAndAck("QUEUE ${steps.size}", 0)?.let { return it }
        var first = 0
        while (first < steps.size) {
            val last = minOf(first + RootSequenceProtocol.CHUNK_SIZE, steps.size)
            val payload = steps.subList(first, last).joinToString("~", transform = RootSequenceProtocol::encode)
            sendAndAck("BLOCK $first $payload", last)?.let { return it }
            first = last
        }
        return null
    }

    private suspend fun receiveSequence(
        id: Long,
        steps: List<RootSequenceStep>,
        initialPause: Boolean,
        stop: CompletableDeferred<StopReason>,
        pause: () -> Boolean,
        onNote: (Int, Long?, Long?) -> Unit,
        onPaused: () -> Unit,
        onResumed: () -> Unit,
        onStarted: (Long) -> Unit,
        beforeResume: suspend () -> Boolean,
        onTiming: (Int, Long, Long, Long) -> Unit,
        onReceipt: (Int, RootTouchOutcome) -> Unit,
    ): SequenceReceipt = coroutineScope {
        val helperPaused = AtomicBoolean()
        val heartbeatLost = AtomicBoolean()
        val resumeDeadlineMissed = AtomicBoolean()
        lastPongNanos.set(System.nanoTime())
        val heartbeats = launch(Dispatchers.IO) {
            while (!stop.isCompleted) {
                delay(500)
                if (stop.isCompleted) break
                if (System.nanoTime() - lastPongNanos.get() > TimeUnit.SECONDS.toNanos(5)) {
                    heartbeatLost.set(true)
                    abortPipe()
                    break
                }
                if (commands.send("SEQ $id PING", unlessStopped = stop) == CommandWrite.FAILED) {
                    abortPipe()
                    break
                }
            }
        }
        val controls = launch {
            var desiredSent = initialPause
            var validation: Deferred<Boolean>? = null
            var resumeSentAtNanos: Long? = null
            while (!stop.isCompleted) {
                val resumeSentAt = resumeSentAtNanos
                if (!helperPaused.get()) {
                    resumeSentAtNanos = null
                } else if (resumeSentAt != null && System.nanoTime() - resumeSentAt >=
                    TimeUnit.MILLISECONDS.toNanos(RootSequenceProtocol.ACK_TIMEOUT_MS)
                ) {
                    resumeDeadlineMissed.set(true)
                    abortPipe()
                    break
                }
                if (pause()) {
                    validation?.cancel()
                    validation = null
                    if (!desiredSent && resumeSentAtNanos == null) {
                        if (commands.send("SEQ $id PAUSE", unlessStopped = stop) == CommandWrite.FAILED) {
                            abortPipe()
                            break
                        }
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
                            stop.complete(StopReason.RESUME_DENIED)
                            break
                        }
                        if (commands.send("SEQ $id RESUME", unlessStopped = stop) == CommandWrite.FAILED) {
                            abortPipe()
                            break
                        }
                        resumeSentAtNanos = System.nanoTime()
                        desiredSent = false
                        validation = null
                    }
                }
                delay(12)
            }
            validation?.cancel()
        }
        var observed = 0
        var origin: Long? = null
        var failedEvent: String? = null
        var maxLatenessMs = 0L
        var progressDeadlineMissed = false
        try {
            val prefix = "SEQ $id "
            while (true) {
                val message = if (helperPaused.get()) {
                    replies.receiveCatching().getOrNull()
                } else {
                    val waitMs = if (origin == null || failedEvent != null || observed >= steps.size) {
                        RootSequenceProtocol.ACK_TIMEOUT_MS
                    } else {
                        val next = steps[observed]
                        val previous = steps.getOrNull(observed - 1)?.noteAtMs ?: 0L
                        (next.noteAtMs - previous).coerceAtLeast(0) + next.strokes.last().durationMs + EVENT_SLACK_MS
                    }
                    val receipt = withTimeoutOrNull(waitMs) { replies.receiveCatching() }
                    if (receipt == null) progressDeadlineMissed = true
                    receipt?.getOrNull()
                } ?: break
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
                        if (parts.size != 6 || observed >= steps.size || parts[1].toIntOrNull() != observed ||
                            failedEvent != null || helperPaused.get()) break
                        val began = origin ?: break
                        val planned = parts[3].toLongOrNull()?.takeIf { it >= began } ?: break
                        val down = parts[4].toLongOrNull() ?: break
                        val up = parts[5].toLongOrNull() ?: break
                        val status = parts[2]
                        if (status == "OK") {
                            if (down < planned || up <= down) break
                            onReceipt(observed, RootTouchOutcome(status, down, up))
                            maxLatenessMs = maxOf(maxLatenessMs, down - planned)
                            onTiming(observed, planned, down, up)
                            onNote(observed, down, up)
                            observed++
                        } else {
                            if (status !in listOf("STOPPED", "FAILED", "UNCONFIRMED", "LATE", "TRANSPORT") ||
                                down < -1L || up < -1L || (up >= 0L && (down < 0L || up < down))
                            ) break
                            failedEvent = status
                            onReceipt(observed, RootTouchOutcome(status, down.takeIf { it >= 0 }, up.takeIf { it >= 0 }))
                        }
                    }
                    "END" -> {
                        if (parts.size != 3 || parts[2].toIntOrNull() != observed) break
                        val status = parts[1]
                        if (failedEvent != null && status != failedEvent) break
                        val result = when (status) {
                            "OK" -> if (origin != null && observed == steps.size) TapResult.Completed else break
                            "STOPPED" -> if (!volumeStopped && stop.isCompleted && stop.await() == StopReason.RESUME_DENIED) {
                                TapResult.Unavailable("恢复前无法确认游戏环境或控制状态")
                            } else TapResult.Cancelled
                            "FAILED" -> TapResult.Rejected
                            "LATE" -> TapResult.Unavailable("Root 触控超过允许的迟到时间，已停止后续音符")
                            "TRANSPORT" -> TapResult.Unavailable(TRANSPORT_ERROR)
                            "UNCONFIRMED" -> TapResult.Unavailable("Root 释放状态待确认")
                            else -> break
                        }
                        return@coroutineScope SequenceReceipt(ContinuousPlaybackResult(observed, result, maxLatenessMs), true)
                    }
                    else -> break
                }
            }
            val reason = when {
                heartbeatLost.get() -> HEARTBEAT_ERROR
                resumeDeadlineMissed.get() -> "Root helper 未确认恢复演奏"
                progressDeadlineMissed -> "Root helper 未在期限内确认演奏进度或结束"
                else -> "Root 连续调度通信中断或响应无效"
            }
            SequenceReceipt(ContinuousPlaybackResult(observed,
                TapResult.Unavailable(reason),
                maxLatenessMs), false)
        } finally {
            heartbeats.cancel()
            controls.cancel()
        }
    }

    private suspend fun receive(timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) { replies.receiveCatching().getOrNull() }

    internal suspend fun awaitReady(sessionId: String): Boolean {
        if (!RootSequenceProtocol.validSessionId(sessionId)) return false
        val reply = receive(3_000) ?: return false
        helperIdentity = RootSequenceProtocol.decodeReady(reply, sessionId)
        return helperIdentity != null
    }

    /** EOF requests worker termination. Only the terminal message confirms cleanup. */
    suspend fun close(): RootCleanup = withContext(NonCancellable) {
        usable = false
        val closingAt = System.nanoTime()
        commands.close()
        val result = withTimeoutOrNull(RootSequenceProtocol.ACK_TIMEOUT_MS) { shutdown.await() } ?: RootCleanup.NO_ACK
        if (result == RootCleanup.NO_ACK && process.isAlive) {
            // EOF can close the reply pipe early while the helper is still releasing pointers.
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closingAt)
            delay((RootSequenceProtocol.ACK_TIMEOUT_MS - elapsed).coerceAtLeast(0L))
            terminateProcess()
        }
        result
    }

    private fun terminateProcess() {
        if (process.isAlive && terminationRequested.compareAndSet(false, true)) process.requestTermination()
    }

    companion object {
        private const val TAG = "DeltaRoot"
        private const val EVENT_SLACK_MS = 5_000L
        private const val TRANSPORT_ERROR = "Root helper 通信中断或心跳超时，已停止演奏"
        private const val HEARTBEAT_ERROR = "Root helper 心跳超时，已停止演奏"

        suspend fun open(
            apk: String,
            targetUid: Int,
            onVolumeDown: (() -> Unit)?,
            sessionId: String = UUID.randomUUID().toString(),
        ): RootGestureConnection? {
            if (!RootSequenceProtocol.validSessionId(sessionId) || targetUid < 0) return null
            val volumeArgument = if (onVolumeDown != null) " volume" else ""
            val command = "CLASSPATH=${shellQuote(apk)} exec /system/bin/app_process /system/bin " +
                "${RootTouchMain::class.java.name} $targetUid $sessionId$volumeArgument"
            var process: Process? = null
            var connection: RootGestureConnection? = null
            var connected = false
            try {
                withContext(Dispatchers.IO) {
                    // Keep ownership here if cancellation discards the dispatcher block's result.
                    val launched = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                    process = launched
                    connection = RootGestureConnection(
                        launched,
                        onVolumeDown,
                    )
                }
                connected = connection?.awaitReady(sessionId) == true
                return connection?.takeIf { connected }
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            } finally {
                if (!connected) {
                    try {
                        connection?.close()
                    } finally {
                        // The helper never accepted input if its handshake did not complete.
                        val opened = connection
                        if (opened != null) opened.terminateProcess() else process?.requestTermination()
                    }
                }
            }
        }
    }
}

/** Bounded writer: a stuck OS pipe cannot suspend the caller or its stop watcher indefinitely. */
private class RootCommandPipe(private val stream: OutputStream) {
    private data class Command(
        val text: String,
        val unlessStopped: Deferred<*>?,
        val sent: CompletableDeferred<CommandWrite> = CompletableDeferred(),
    )

    private val pending = LinkedBlockingDeque<Command>(5)
    private val closed = AtomicBoolean()

    init {
        Thread({
            val writer = stream.bufferedWriter()
            try {
                while (!closed.get()) {
                    val command = pending.pollFirst(25L, TimeUnit.MILLISECONDS) ?: continue
                    if (closed.get()) {
                        command.sent.complete(CommandWrite.FAILED)
                        break
                    }
                    if (command.unlessStopped?.isCompleted == true) {
                        command.sent.complete(CommandWrite.SUPERSEDED)
                        continue
                    }
                    try {
                        writer.write(command.text)
                        writer.newLine()
                        writer.flush()
                        command.sent.complete(CommandWrite.SENT)
                    } catch (_: IOException) {
                        command.sent.complete(CommandWrite.FAILED)
                        break
                    }
                }
            } finally {
                close()
                runCatching { stream.close() }
            }
        }, "DeltaRootCommands").apply { isDaemon = true; start() }
    }

    suspend fun send(
        text: String,
        priority: Boolean = false,
        unlessStopped: Deferred<*>? = null,
    ): CommandWrite {
        if (unlessStopped?.isCompleted == true) return CommandWrite.SUPERSEDED
        val command = Command(text, unlessStopped)
        val queued = synchronized(pending) {
            // Leave one slot for STOP and select it before ordinary queued messages.
            !closed.get() && if (priority) pending.offerFirst(command)
                else pending.remainingCapacity() > 1 && pending.offerLast(command)
        }
        if (!queued) {
            close()
            return CommandWrite.FAILED
        }
        val outcome = try {
            withTimeoutOrNull(RootSequenceProtocol.ACK_TIMEOUT_MS) { command.sent.await() } ?: CommandWrite.FAILED
        } finally {
            pending.remove(command)
        }
        if (outcome == CommandWrite.FAILED) close()
        return outcome
    }

    fun close() = synchronized(pending) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        while (true) {
            val abandoned = pending.pollFirst() ?: break
            abandoned.sent.complete(CommandWrite.FAILED)
        }
    }
}

private enum class CommandWrite { SENT, SUPERSEDED, FAILED }
