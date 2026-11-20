package com.huanhren.deltaharmonica.input

internal data class RootStroke(val x: Float, val y: Float, val startMs: Long, val durationMs: Long)
internal enum class RootTouchKind { DOWN, UP, CANCEL }
internal class RootDispatchDenied : Exception()
private class RootDownLate : Exception()

internal data class RootTouchOutcome(val status: String, val noteDown: Long?, val noteUp: Long?) {
    val completed get() = status == "OK"
}

/** Each stroke keeps its own deadline based on the timestamp of its actual DOWN. */
internal object RootTouchTimeline {
    fun play(
        strokes: List<RootStroke>,
        now: () -> Long,
        waitUntil: (Long) -> Unit,
        scheduledStartMs: Long? = null,
        maxDownLatenessMs: Long = Long.MAX_VALUE,
        sequential: Boolean = false,
        settlingSlackMs: Long = 0L,
        emit: (RootTouchKind, Int, Map<Int, RootStroke>, Long, Long) -> Boolean,
    ): RootTouchOutcome {
        val pending = ArrayDeque(strokes.indices.sortedBy { strokes[it].startMs })
        val active = linkedMapOf<Int, RootStroke>()
        val deadlines = mutableMapOf<Int, Long>()
        val start = scheduledStartMs ?: now()
        val note = strokes.lastIndex
        var downTime = start
        var noteDown: Long? = null
        var noteUp: Long? = null
        var nextClearDown = start

        fun up(id: Int, time: Long) {
            check(emit(RootTouchKind.UP, id, active, downTime, time))
            if (id == note) noteUp = time
            active.remove(id)
            deadlines.remove(id)
            if (sequential) {
                pending.firstOrNull()?.let { next ->
                    val settle = strokes[next].startMs - strokes[id].startMs - strokes[id].durationMs
                    check(settle >= 0)
                    nextClearDown = now() + (settle - settlingSlackMs).coerceAtLeast(0L)
                }
            }
        }

        fun cancel(): Boolean = active.isEmpty() || runCatching {
            emit(RootTouchKind.CANCEL, active.keys.first(), active, downTime, now())
        }.getOrDefault(false)

        try {
            while (pending.isNotEmpty() || active.isNotEmpty()) {
                val nextDown = pending.firstOrNull()
                val nextUp = deadlines.minByOrNull { it.value }
                val plannedDown = nextDown?.let { start + strokes[it].startMs } ?: Long.MAX_VALUE
                val downAt = if (sequential && active.isNotEmpty()) Long.MAX_VALUE
                    else maxOf(plannedDown, nextClearDown)
                val upAt = nextUp?.value ?: Long.MAX_VALUE
                waitUntil(minOf(downAt, upAt))
                val time = now()
                if (upAt <= downAt) {
                    up(checkNotNull(nextUp).key, time)
                } else {
                    if (time - plannedDown > maxDownLatenessMs) throw RootDownLate()
                    val id = pending.removeFirst()
                    val stroke = strokes[id]
                    if (active.isEmpty()) downTime = time
                    active[id] = stroke
                    deadlines[id] = time + stroke.durationMs
                    try {
                        check(emit(RootTouchKind.DOWN, id, active, downTime, time))
                    } catch (e: RootDispatchDenied) {
                        active.remove(id)
                        deadlines.remove(id)
                        throw e
                    }
                    if (sequential) deadlines[id] = now() + stroke.durationMs
                    if (id == note) noteDown = time
                }
            }
        } catch (_: InterruptedException) {
            // Complete the note normally. Cancel partially performed control strokes.
            if (note in active) runCatching { up(note, now()) }
            val released = cancel()
            return RootTouchOutcome(if (released) "STOPPED" else "UNCONFIRMED", noteDown, noteUp)
        } catch (_: RootDownLate) {
            return RootTouchOutcome(if (cancel()) "LATE" else "UNCONFIRMED", noteDown, noteUp)
        } catch (_: Exception) {
            return RootTouchOutcome(if (cancel()) "FAILED" else "UNCONFIRMED", noteDown, noteUp)
        }
        return RootTouchOutcome("OK", noteDown, noteUp)
    }
}
