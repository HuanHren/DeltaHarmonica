package com.huanhren.deltaharmonica.input

import java.util.UUID

internal data class RootHelperIdentity(val pid: Int, val startTicks: Long)
internal data class RootProtocolFrame(val sessionId: String, val dispatcherId: String, val operation: Long, val payload: String)
internal enum class RootDispatcherStatus { STARTING, READY, RUNNING, STOPPING, TERMINATED, UNRESPONSIVE }
internal enum class RootDispatcherFault {
    PEER_EXITED, PIPE_FAILED, PROTOCOL_ERROR, HEARTBEAT_TIMEOUT, RECEIPT_TIMEOUT, NATIVE_TIMEOUT, STOP_TIMEOUT,
    WORKER_FAILED, RELEASE_UNCONFIRMED,
}

/** Private, versioned text framing shared by the app and the helper from the same APK. */
internal object RootSequenceProtocol {
    const val VERSION = 4
    const val MAX_NOTES = 100_000
    const val CHUNK_SIZE = 32
    const val BUFFER_SIZE = 256
    const val ACK_TIMEOUT_MS = 1_200L
    private const val MAX_STROKES_TEXT = 200
    private const val MAX_STEP_TEXT = MAX_STROKES_TEXT + 72

    fun validSessionId(sessionId: String): Boolean =
        runCatching { UUID.fromString(sessionId).toString() == sessionId }.getOrDefault(false)

    fun encodeFrame(sessionId: String, dispatcherId: String, operation: Long, payload: String): String =
        "DELTA $sessionId $dispatcherId $operation $payload"

    fun decodeFrame(line: String, sessionId: String): RootProtocolFrame? {
        val fields = line.split(' ', limit = 5)
        if (fields.size != 5 || fields[0] != "DELTA" || fields[1] != sessionId || fields[4].isEmpty()) return null
        val operation = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return RootProtocolFrame(sessionId, fields[2], operation, fields[4])
    }

    fun decodeReady(reply: String): RootHelperIdentity? {
        val fields = reply.split(' ')
        if (fields.size != 4 || fields[0] != "READY" || fields[1] != VERSION.toString()) return null
        val pid = fields[2].toIntOrNull()?.takeIf { it > 1 } ?: return null
        val startTicks = fields[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return RootHelperIdentity(pid, startTicks)
    }

    fun decodeProcessStat(stat: String): RootHelperIdentity? {
        // comm can contain spaces and parentheses. Linux fields after its last ')' begin at field 3.
        val commStart = stat.indexOf('(')
        val commEnd = stat.lastIndexOf(')')
        if (commStart <= 0 || commEnd <= commStart) return null
        val pid = stat.substring(0, commStart).trim().toIntOrNull()?.takeIf { it > 1 } ?: return null
        val startTicks = stat.substring(commEnd + 1).trim().split(Regex("\\s+"))
            .getOrNull(19)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return RootHelperIdentity(pid, startTicks)
    }
    fun encode(step: RootSequenceStep): String =
        "${step.noteAtMs},${step.minGapMs},${step.maxLatenessMs}|${encodeStrokes(step.strokes)}"

    fun encodeStrokes(strokes: List<RootStroke>): String = strokes.joinToString(";") {
        "${it.x},${it.y},${it.startMs},${it.durationMs}"
    }

    fun decodeStrokes(text: String, sequence: Boolean = false): List<RootStroke>? = runCatching {
        require(text.length <= MAX_STROKES_TEXT)
        text.split(';', limit = 4).map { stroke ->
            val values = stroke.split(',', limit = 5)
            require(values.size == 4)
            RootStroke(values[0].toFloat(), values[1].toFloat(), values[2].toLong(), values[3].toLong()).also {
                require(it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.y >= 0)
                require(it.startMs in 0..MAX_GESTURE_DURATION_MS && it.durationMs in 1..MAX_GESTURE_DURATION_MS)
                require(sequence || it.startMs <= MAX_GESTURE_DURATION_MS - it.durationMs)
            }
        }.also { require(it.size in 1..3) }
    }.getOrNull()

    fun decodeChunk(text: String): List<RootSequenceStep>? = runCatching {
        require(text.length <= CHUNK_SIZE * (MAX_STEP_TEXT + 1))
        text.split('~', limit = CHUNK_SIZE + 1).also { require(it.size <= CHUNK_SIZE) }.map {
            val parts = it.split('|', limit = 2)
            val fields = parts[0].split(',', limit = 4)
            require(fields.size == 3)
            val at = fields[0].toLong()
            val gap = fields[1].toLong()
            val lateness = fields[2].toLong()
            require(at in 0..Long.MAX_VALUE / 4)
            val strokes = checkNotNull(decodeStrokes(parts[1], sequence = true))
            require(strokes.first().startMs == 0L)
            require(strokes.zipWithNext().all { (previous, next) ->
                previous.startMs + previous.durationMs <= next.startMs
            })
            RootSequenceStep(at, strokes, gap, lateness)
        }
    }.getOrNull()
}

/** Commit transfers a complete index-ordered timeline to one worker without copying it. */
internal class RootSequenceUpload(private val expected: Int) {
    init { require(expected in 1..RootSequenceProtocol.MAX_NOTES) }

    private var steps: ArrayList<RootSequenceStep>? = ArrayList()
    val received: Int get() = steps?.size ?: expected

    fun append(first: Int, payload: String): Boolean {
        val current = steps ?: return false
        if (first != current.size) return false
        val chunk = RootSequenceProtocol.decodeChunk(payload) ?: return false
        if (chunk.size > expected - current.size) return false
        var last = current.lastOrNull()?.noteAtMs ?: 0
        for (step in chunk) {
            if (step.noteAtMs < last) return false
            last = step.noteAtMs
        }
        current.addAll(chunk)
        return true
    }

    fun commit(): List<RootSequenceStep>? {
        val current = steps?.takeIf { it.size == expected } ?: return null
        steps = null
        return current
    }
}
