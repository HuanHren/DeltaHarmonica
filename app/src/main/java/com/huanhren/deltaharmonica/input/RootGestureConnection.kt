package com.huanhren.deltaharmonica.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.huanhren.deltaharmonica.playback.CombinedGesture
import com.huanhren.deltaharmonica.playback.ContinuousPlaybackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal class RootGestureConnection private constructor(
    private val process: Process,
    private val onVolumeDown: (() -> Unit)?,
) {
    private val commands = process.outputStream.bufferedWriter()
    private val replies = Channel<String>(Channel.UNLIMITED)
    private val gate = Mutex()
    private var usable = true
    @Volatile private var volumeStopped = false

    init {
        Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.filter { it.startsWith("DELTA ") }.forEach { line ->
                        val reply = line.removePrefix("DELTA ")
                        when {
                            reply.startsWith("VOLUME ") -> {
                                volumeStopped = true
                                Log.i("DeltaRoot", "volume press t=${reply.substringAfter(' ')}")
                                Handler(Looper.getMainLooper()).post { onVolumeDown?.invoke() }
                            }
                            reply == "NO_VOLUME_MONITOR" -> Log.w("DeltaRoot", "built-in volume key monitor unavailable")
                            reply == "VOLUME_READY" -> Log.i("DeltaRoot", "built-in volume key monitor initialized")
                            else -> replies.trySend(reply)
                        }
                    }
                }
            } catch (_: IOException) {
                // A lost pipe is reported by the receiver as an uncertain release.
            } finally { replies.close() }
        }.apply { isDaemon = true; start() }
    }

    private suspend fun send(command: String) = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(commands) {
                commands.write(command)
                commands.newLine()
                commands.flush()
            }
        }.isSuccess
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
            val command = gesture.strokes.joinToString(";") { "${it.point.x},${it.point.y},${it.startMs},${it.durationMs}" }
            outstanding = true
            if (!send("PLAY $command")) return TapResult.Unavailable("Root 触摸命令写入失败")
            val timeout = gesture.strokes.maxOf { it.startMs + it.durationMs } + 1_500
            val reply = coroutineScope {
                val watcher = launch {
                    while (!stop()) delay(12)
                    send("STOP")
                }
                try { receive(timeout) } finally { watcher.cancel() }
            } ?: return TapResult.TimedOut
            outstanding = false
            val fields = reply.split(' ')
            if (fields.size != 4 || fields[0] != "DONE") {
                usable = false
                return TapResult.Unavailable("Root 触摸响应无效，释放待确认")
            }
            val outcome = RootTouchOutcome(
                fields[1],
                fields[2].toLongOrNull()?.takeIf { it >= 0 },
                fields[3].toLongOrNull()?.takeIf { it >= 0 },
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
            if (outstanding) withContext(NonCancellable) {
                usable = false
                send("STOP")
                receive(CLEANUP_TIMEOUT_MS)
            }
            gate.unlock()
        }
    }


    /** Sends the complete note timeline once; the privileged helper controls all DOWN/UP deadlines. */
    internal suspend fun playSequence(
        steps: List<RootSequenceStep>,
        stop: () -> Boolean,
        pause: () -> Boolean,
        onNote: (Int, Long?, Long?) -> Unit,
        onPaused: () -> Unit,
        onResumed: () -> Unit,
    ): ContinuousPlaybackResult {
        if (!gate.tryLock()) return ContinuousPlaybackResult(0, TapResult.Busy)
        var outstanding = false
        try {
            if (!usable) return ContinuousPlaybackResult(0, TapResult.Unavailable("Root 输入连接已失效"))
            if (steps.isEmpty() || steps.size > 100_000) return ContinuousPlaybackResult(0, TapResult.Unavailable("连续演奏音符数量无效"))
            if (stop() || volumeStopped) return ContinuousPlaybackResult(0, TapResult.Cancelled)
            val sent = withContext(Dispatchers.IO) {
                runCatching {
                    synchronized(commands) {
                        commands.write("QUEUE ${steps.size}")
                        commands.newLine()
                        for (step in steps) {
                            val strokes = step.strokes.joinToString(";") {
                                "${it.x},${it.y},${it.startMs},${it.durationMs}"
                            }
                            commands.write("ITEM ${step.noteAtMs}|$strokes")
                            commands.newLine()
                        }
                        commands.write("RUN")
                        commands.newLine()
                        commands.flush()
                    }
                }.isSuccess
            }
            outstanding = true
            if (!sent) {
                usable = false
                return ContinuousPlaybackResult(0, TapResult.Unavailable("连续触控计划发送失败"))
            }
            var observed = 0
            var end: ContinuousPlaybackResult? = null
            val finished = coroutineScope {
                val watcher = launch {
                    var lastPause = false
                    while (true) {
                        if (stop() || volumeStopped) {
                            send("STOP")
                            break
                        }
                        val desired = pause()
                        if (desired != lastPause) {
                            if (!send(if (desired) "PAUSE" else "RESUME")) break
                            lastPause = desired
                        }
                        delay(12)
                    }
                }
                try {
                    var missed = 0
                    while (end == null) {
                        val message = receive(1_000)
                        if (message == null) {
                            if (!process.isAlive || ++missed >= 4 || !send("PING")) break
                            continue
                        }
                        missed = 0
                        when {
                            message == "PONG" -> Unit
                            message == "PAUSED" -> onPaused()
                            message == "RESUMED" -> onResumed()
                            message.startsWith("EVENT ") -> {
                                val parts = message.split(' ')
                                val index = parts.getOrNull(1)?.toIntOrNull()
                                if (parts.size != 5 || index != observed || parts[2] != "OK") break
                                val down = parts[3].toLongOrNull()
                                val up = parts[4].toLongOrNull()
                                if (down == null || up == null || down < 0 || up < down) break
                                onNote(index, down, up)
                                observed++
                            }
                            message.startsWith("END ") -> {
                                val parts = message.split(' ')
                                val count = parts.getOrNull(2)?.toIntOrNull()
                                if (parts.size != 3 || count != observed) break
                                end = ContinuousPlaybackResult(count, when(parts[1]) {
                                    "OK" -> if (count == steps.size) TapResult.Completed else TapResult.Unavailable("Root 提前结束")
                                    "STOPPED" -> TapResult.Cancelled
                                    "FAILED" -> TapResult.Rejected
                                    else -> TapResult.Unavailable("Root 释放状态待确认")
                                })
                            }
                            else -> break
                        }
                    }
                    end
                } finally { watcher.cancel() }
            }
            if (finished != null) {
                outstanding = false
                if (finished.result is TapResult.Unavailable) usable = false
                return finished
            }
            usable = false
            return ContinuousPlaybackResult(observed, TapResult.Unavailable("Root 连续调度通信中断或响应无效"))
        } finally {
            if (outstanding) withContext(NonCancellable) {
                usable = false
                send("STOP")
                receive(CLEANUP_TIMEOUT_MS)
            }
            gate.unlock()
        }
    }

    private suspend fun receive(timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) { replies.receiveCatching().getOrNull() }

    /** EOF asks the helper to finish release before it acknowledges shutdown. */
    suspend fun close(): RootCleanup = withContext(NonCancellable) {
        withContext(Dispatchers.IO) { runCatching { commands.close() } }
        val outcome = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
            var reply = replies.receiveCatching().getOrNull()
            while (reply != null && reply.startsWith("DONE ")) reply = replies.receiveCatching().getOrNull()
            when (reply) {
                "CLOSED" -> RootCleanup.ACKNOWLEDGED
                "UNCONFIRMED" -> RootCleanup.REJECTED
                else -> RootCleanup.NO_ACK
            }
        } ?: RootCleanup.NO_ACK
        // If injection is still in flight, let the helper process STOP/EOF when it returns.
        if (outcome != RootCleanup.NO_ACK) withContext(Dispatchers.IO) {
            runCatching { process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        outcome
    }

    companion object {
        private const val CLEANUP_TIMEOUT_MS = 1_200L

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
                connected = connection?.receive(3_000) == "READY 0"
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
