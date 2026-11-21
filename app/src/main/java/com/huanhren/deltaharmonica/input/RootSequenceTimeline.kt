package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.playback.ROOT_MAX_TIMELINE_SHIFT_MS
import com.huanhren.deltaharmonica.playback.ROOT_RECOVERY_WINDOW_MS
import com.huanhren.deltaharmonica.playback.ROOT_STROKE_DISPATCH_MARGIN_MS

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
        var timelineShift = 0L
        var completed = 0
        var releasedAt: Long? = null
        var previousPlannedUp = 0L
        val maxShift = ((steps.last().noteAtMs + steps.last().strokes.last().durationMs) / 50L)
            .coerceIn(ROOT_RECOVERY_WINDOW_MS, ROOT_MAX_TIMELINE_SHIFT_MS)
        for ((index, step) in steps.withIndex()) {
            val lead = step.strokes.last().startMs
            val plannedFirstDown = step.noteAtMs - lead
            // Reclaim phase delay only from a rest left after mandatory touch spacing.
            val rest = (plannedFirstDown - previousPlannedUp - step.minGapMs - ROOT_STROKE_DISPATCH_MARGIN_MS)
                .coerceAtLeast(0L)
            timelineShift = (timelineShift - rest).coerceAtLeast(0L)
            val startAt = origin + plannedFirstDown + timelineShift
            val nextSafeDown = releasedAt?.plus(step.minGapMs) ?: origin
            try {
                var pausedHere: Long
                do {
                    pausedHere = waitFor(maxOf(startAt + pauseShift, nextSafeDown))
                    pauseShift += pausedHere
                } while (pausedHere > 0L)
            } catch (_: InterruptedException) {
                return RootSequenceOutcome("STOPPED", completed)
            } catch (_: RootTransportInterrupted) {
                return RootSequenceOutcome("TRANSPORT", completed)
            } catch (_: Exception) {
                return RootSequenceOutcome("FAILED", completed)
            }
            // Never turn a stalled game/input dispatcher into a fast burst of stale notes.
            val plannedStart = startAt + pauseShift
            if (now() - plannedStart > step.maxLatenessMs + ROOT_RECOVERY_WINDOW_MS)
                return RootSequenceOutcome("LATE", completed)
            val receipt = try {
                playTimedGesture?.invoke(step, plannedStart) ?: playGesture(step.strokes)
            } catch (_: Exception) {
                return RootSequenceOutcome("UNCONFIRMED", completed)
            }
            releasedAt = now()
            val plannedNoteAt = origin + step.noteAtMs + pauseShift
            val down = receipt.noteDown
            val up = receipt.noteUp
            val outcome = if (receipt.completed && (down == null || down < plannedNoteAt || up == null || up <= down)) {
                receipt.copy(status = "UNCONFIRMED", noteUp = null)
            } else receipt
            onScheduledNote(index, outcome, plannedNoteAt)
            onNote(index, outcome)
            if (!outcome.completed) return RootSequenceOutcome(outcome.status, completed)
            completed++
            previousPlannedUp = step.noteAtMs + step.strokes.last().durationMs
            val noteDelay = checkNotNull(down) - (plannedStart + lead)
            if (noteDelay > step.maxLatenessMs) {
                // Preserve the phase after a recoverable stall instead of replaying stale events.
                timelineShift += noteDelay
                if (timelineShift > maxShift && completed < steps.size) {
                    return RootSequenceOutcome("LATE", completed)
                }
            }
        }
        return RootSequenceOutcome("OK", completed)
    }
}
