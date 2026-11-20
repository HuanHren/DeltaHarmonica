package com.huanhren.deltaharmonica.input

/** One playable note and its (optional) simultaneous region/semitone controls. */
internal data class RootSequenceStep(val noteAtMs: Long, val strokes: List<RootStroke>) {
    init {
        require(noteAtMs >= 0)
        require(strokes.size in 1..3)
        require(strokes.last().startMs <= noteAtMs)
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
    ): RootSequenceOutcome {
        require(steps.isNotEmpty())
        val origin = now()
        onStarted(origin)
        var pauseShift = 0L
        var completed = 0
        for ((index, step) in steps.withIndex()) {
            val startAt = origin + step.noteAtMs - step.strokes.last().startMs
            try {
                var pausedHere: Long
                do {
                    pausedHere = waitFor(startAt + pauseShift)
                    pauseShift += pausedHere
                } while (pausedHere > 0L)
            } catch (_: InterruptedException) {
                return RootSequenceOutcome("STOPPED", completed)
            } catch (_: Exception) {
                return RootSequenceOutcome("FAILED", completed)
            }
            // Never turn a stalled game/input dispatcher into a fast burst of stale notes.
            if (now() - (startAt + pauseShift) > 2_000L)
                return RootSequenceOutcome("FAILED", completed)
            val outcome = try {
                playGesture(step.strokes)
            } catch (_: Exception) {
                return RootSequenceOutcome("UNCONFIRMED", completed)
            }
            onNote(index, outcome)
            if (!outcome.completed) return RootSequenceOutcome(outcome.status, completed)
            completed++
        }
        return RootSequenceOutcome("OK", completed)
    }
}
