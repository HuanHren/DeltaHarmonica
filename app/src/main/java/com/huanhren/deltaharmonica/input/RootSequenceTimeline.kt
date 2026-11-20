package com.huanhren.deltaharmonica.input

/** One playable note with the ordered control strokes that must precede its DOWN. */
internal data class RootSequenceStep(
    val noteAtMs: Long,
    val strokes: List<RootStroke>,
    val minGapMs: Long = 0,
    val maxLatenessMs: Long = 40,
) {
    init {
        require(noteAtMs >= 0)
        require(strokes.size in 1..3)
        require(strokes.last().startMs <= noteAtMs)
        require(minGapMs in 0..500 && maxLatenessMs in 1..500)
    }
}

internal data class RootSequenceOutcome(val status: String, val completed: Int) {
    val completedAll: Boolean get() = status == "OK"
}

/**
 * Runs all notes against one monotonic time origin in the privileged helper.
 * The caller does not wait for an app-process round trip between notes.
 *
 * waitFor(deadline) returns the extra milliseconds spent paused while waiting.
 * The root worker never pauses an active note: it releases the current note
 * before waiting to start the next one.
 */
internal object RootSequenceTimeline {
    fun play(
        steps: List<RootSequenceStep>,
        now: () -> Long,
        waitFor: (Long) -> Long,
        playGesture: (List<RootStroke>) -> RootTouchOutcome,
        onNote: (Int, RootTouchOutcome) -> Unit,
        onStarted: (Long) -> Unit = {},
        playTimedGesture: ((RootSequenceStep, Long) -> RootTouchOutcome)? = null,
        onScheduledNote: (Int, RootTouchOutcome, Long) -> Unit = { _, _, _ -> },
    ): RootSequenceOutcome {
        require(steps.isNotEmpty())
        val origin = now()
        onStarted(origin)
        var pauseShift = 0L
        var completed = 0
        var releasedAt: Long? = null
        for ((index, step) in steps.withIndex()) {
            val startAt = origin + step.noteAtMs - step.strokes.last().startMs
            val nextSafeDown = releasedAt?.plus(step.minGapMs) ?: origin
            try {
                var pausedHere: Long
                do {
                    pausedHere = waitFor(maxOf(startAt + pauseShift, nextSafeDown))
                    pauseShift += pausedHere
                } while (pausedHere > 0L)
            } catch (_: InterruptedException) {
                return RootSequenceOutcome("STOPPED", completed)
            } catch (_: Exception) {
                return RootSequenceOutcome("FAILED", completed)
            }
            // Never turn a stalled game/input dispatcher into a fast burst of stale notes.
            val plannedStart = startAt + pauseShift
            if (now() - plannedStart > step.maxLatenessMs)
                return RootSequenceOutcome("LATE", completed)
            val outcome = try {
                playTimedGesture?.invoke(step, plannedStart) ?: playGesture(step.strokes)
            } catch (_: Exception) {
                return RootSequenceOutcome("UNCONFIRMED", completed)
            }
            releasedAt = now()
            onScheduledNote(index, outcome, origin + step.noteAtMs + pauseShift)
            onNote(index, outcome)
            if (!outcome.completed) return RootSequenceOutcome(outcome.status, completed)
            completed++
        }
        return RootSequenceOutcome("OK", completed)
    }
}
