package com.huanhren.deltaharmonica.input

internal data class RootStroke(val x: Float, val y: Float, val startMs: Long, val durationMs: Long)
internal enum class RootTouchKind { DOWN, UP, CANCEL }
internal class RootDispatchDenied : Exception()
internal class RootDispatchSuppressed(val reason: Exception) : Exception()
internal class RootTransportInterrupted : Exception()
private class RootDownLate : Exception()

internal data class RootTouchOutcome(
    val status: String,
    val noteDown: Long?,
    val noteUp: Long?,
    val uncertainPointerIds: Set<Int>? = null,
) {
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

        fun release(status: String): RootTouchOutcome {
            val cancelled = active.isEmpty() || runCatching {
                emit(RootTouchKind.CANCEL, active.keys.first(), active, downTime, now())
            }.getOrDefault(false)
            if (!cancelled) return RootTouchOutcome("UNCONFIRMED", noteDown, noteUp, active.keys.toSet())
            active.clear()
            deadlines.clear()
            return RootTouchOutcome(status, noteDown, noteUp)
        }

        fun interrupted(status: String): RootTouchOutcome {
            // Complete the note normally. Cancel partially performed control strokes.
            if (note in active) runCatching { up(note, now()) }
            return release(status)
        }

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
                    } catch (e: RootDispatchSuppressed) {
                        // The watchdog vetoed this DOWN before the native call began.
                        active.remove(id)
                        deadlines.remove(id)
                        throw e.reason
                    }
                    if (sequential) deadlines[id] = now() + stroke.durationMs
                    if (id == note) noteDown = time
                }
            }
        } catch (_: InterruptedException) {
            return interrupted("STOPPED")
        } catch (_: RootTransportInterrupted) {
            return interrupted("TRANSPORT")
        } catch (_: RootDownLate) {
            return release("LATE")
        } catch (_: Exception) {
            return release("FAILED")
        }
        return RootTouchOutcome("OK", noteDown, noteUp)
    }
}
