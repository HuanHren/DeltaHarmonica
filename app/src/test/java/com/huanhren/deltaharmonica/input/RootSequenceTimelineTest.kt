package com.huanhren.deltaharmonica.input

import org.junit.Assert.*
import org.junit.Test

class RootSequenceTimelineTest {
    private fun step(at: Long, lead: Long = 0, duration: Long = 50) =
        RootSequenceStep(at, listOf(RootStroke(1f, 2f, lead, duration)))

    @Test fun plansAllNotesOnOneClockWithoutPerNoteAppRoundTrips() {
        var now = 0L
        val starts = mutableListOf<Long>()
        val progress = mutableListOf<Int>()
        val result = RootSequenceTimeline.play(
            listOf(step(0), step(100), step(200)),
            now = { now }, waitFor = { deadline -> now = maxOf(now, deadline); 0L },
            playGesture = {
                starts.add(now)
                now += it.last().durationMs
                RootTouchOutcome("OK", now - it.last().durationMs, now)
            },
            onNote = { i, _ -> progress.add(i) },
        )
        assertEquals("OK", result.status)
        assertEquals(3, result.completed)
        assertEquals(listOf(0L, 100L, 200L), starts)
        assertEquals(listOf(0, 1, 2), progress)
    }

    @Test fun modifierLeadIsScheduledBeforeTheTargetNote() {
        var now = 0L
        val starts = mutableListOf<Long>()
        val result = RootSequenceTimeline.play(
            listOf(
                RootSequenceStep(40, listOf(
                    RootStroke(3f, 4f, 0, 80),
                    RootStroke(1f, 2f, 40, 50),
                )),
                step(160),
            ),
            now = { now }, waitFor = { deadline -> now = maxOf(now, deadline); 0L },
            playGesture = {
                starts.add(now)
                now += it.maxOf { stroke -> stroke.startMs + stroke.durationMs }
                RootTouchOutcome("OK", now - 50, now)
            },
            onNote = { _, _ -> },
        )
        assertTrue(result.completedAll)
        assertEquals(listOf(0L, 160L), starts)
    }

    @Test fun pauseFreezesUpcomingTimeWithoutSkippingRestOfGap() {
        var now = 0L
        var pausedOnce = false
        val starts = mutableListOf<Long>()
        val result = RootSequenceTimeline.play(
            listOf(step(0), step(300)),
            now = { now },
            waitFor = { deadline ->
                if (!pausedOnce && deadline == 300L) {
                    pausedOnce = true
                    now = 140L
                    now += 500L // user pauses for 500ms in the gap
                    500L
                } else { now = maxOf(now, deadline); 0L }
            },
            playGesture = {
                starts.add(now)
                now += it.last().durationMs
                RootTouchOutcome("OK", now - 50, now)
            },
            onNote = { _, _ -> },
        )
        assertTrue(result.completedAll)
        assertEquals(listOf(0L, 800L), starts)
    }

    @Test fun repeatedPausesAreNotCountedTwice() {
        var now = 0L
        var pauses = 0
        val starts = mutableListOf<Long>()
        val result = RootSequenceTimeline.play(
            listOf(step(0), step(100), step(200)),
            now = { now },
            waitFor = { deadline ->
                if (pauses < 2 && deadline == (if (pauses == 0) 100L else 400L)) {
                    val extra = if (pauses == 0) 200L else 100L
                    pauses++
                    now = deadline + extra
                    extra
                } else { now = maxOf(now, deadline); 0L }
            },
            playGesture = {
                starts.add(now)
                now += it.last().durationMs
                RootTouchOutcome("OK", now - 50, now)
            },
            onNote = { _, _ -> },
        )
        assertTrue(result.completedAll)
        assertEquals(2, pauses)
        assertEquals(listOf(0L, 300L, 500L), starts)
    }

    @Test fun stopBetweenNotesDoesNotStartTheNextStroke() {
        var now = 0L
        var calls = 0
        val result = RootSequenceTimeline.play(
            listOf(step(0), step(100)),
            now = { now },
            waitFor = { deadline ->
                if (calls > 0) throw InterruptedException()
                now = deadline
                0L
            },
            playGesture = {
                calls++
                RootTouchOutcome("OK", now, now + 50)
            },
            onNote = { _, _ -> },
        )
        assertEquals("STOPPED", result.status)
        assertEquals(1, result.completed)
        assertEquals(1, calls)
    }

    @Test fun inputStallDoesNotBurstCatchUpWithObsoleteNotes() {
        var now = 0L
        var injected = 0
        val result = RootSequenceTimeline.play(
            listOf(step(0), step(100)),
            now = { now },
            waitFor = { deadline -> now = deadline + 2_100L; 0L },
            playGesture = {
                injected++
                RootTouchOutcome("OK", now, now + 50)
            },
            onNote = { _, _ -> },
        )
        assertEquals("LATE", result.status)
        assertEquals(0, result.completed)
        assertEquals(0, injected)
    }

    @Test fun recoverableStallRephasesFollowingNotesAndPaysBackDuringRests() {
        var now = 0L
        var stalledOnce = false
        val downTimes = mutableListOf<Long>()
        val plannedTimes = mutableListOf<Long>()
        val result = RootSequenceTimeline.play(
            listOf(step(0), step(100), step(200).copy(minGapMs = 20), step(450)),
            now = { now },
            waitFor = { deadline ->
                now = maxOf(now, deadline)
                if (deadline == 100L && !stalledOnce) {
                    stalledOnce = true
                    now += 57L
                }
                0L
            },
            playGesture = {
                val down = now
                downTimes.add(down)
                now += it.last().durationMs
                RootTouchOutcome("OK", down, now)
            },
            onNote = { _, _ -> },
            onScheduledNote = { _, _, original -> plannedTimes.add(original) },
        )
        assertTrue(result.completedAll)
        assertEquals(listOf(0L, 157L, 239L, 450L), downTimes)
        assertEquals(listOf(0L, 100L, 200L, 450L), plannedTimes)
    }

    @Test fun repeatedStallsWithoutRestStopWhenPhaseShiftExceedsBudget() {
        var now = 0L
        val result = RootSequenceTimeline.play(
            List(5) { step(it * 50L) },
            now = { now },
            waitFor = { deadline -> now = maxOf(now, deadline) + 60L; 0L },
            playGesture = {
                val down = now
                now += it.last().durationMs
                RootTouchOutcome("OK", down, now)
            },
            onNote = { _, _ -> },
        )
        assertEquals("LATE", result.status)
        assertEquals(3, result.completed)
    }

    @Test fun failedInjectionDoesNotAdvanceProgressOrContinue() {
        val result = RootSequenceTimeline.play(
            listOf(step(0), step(100), step(200)),
            now = { 0L }, waitFor = { 0L },
            playGesture = { RootTouchOutcome("UNCONFIRMED", null, null) },
            onNote = { _, _ -> },
        )
        assertEquals("UNCONFIRMED", result.status)
        assertEquals(0, result.completed)
    }

    @Test fun missingOrInvalidNoteReleaseCannotCompleteASequence() {
        for (proof in listOf(
            RootTouchOutcome("OK", 10, null),
            RootTouchOutcome("OK", null, 60),
            RootTouchOutcome("OK", 10, 9),
            RootTouchOutcome("OK", 10, 10),
        )) {
            var gestures = 0
            val events = mutableListOf<String>()
            val result = RootSequenceTimeline.play(
                listOf(step(0), step(100)),
                now = { 0L }, waitFor = { 0L },
                playGesture = { gestures++; proof },
                onNote = { _, note -> events.add(note.status) },
            )

            assertEquals(RootSequenceOutcome("UNCONFIRMED", 0), result)
            assertEquals(listOf("UNCONFIRMED"), events)
            assertEquals(1, gestures)
        }
    }
}
