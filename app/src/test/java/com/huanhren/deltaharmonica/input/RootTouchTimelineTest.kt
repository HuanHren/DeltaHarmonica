package com.huanhren.deltaharmonica.input

import org.junit.Assert.*
import org.junit.Test

class RootTouchTimelineTest {
    @Test fun continuousControlReleaseMustSettleBeforeNoteEvenWhenInjectionTakesTime() {
        var clock = 100L
        val steps = listOf(RootStroke(1f, 1f, 0, 40), RootStroke(2f, 2f, 70, 50))
        val times = mutableListOf<Long>()
        val result = RootTouchTimeline.play(
            steps, { clock }, { clock = maxOf(clock, it) },
            scheduledStartMs = 100L, maxDownLatenessMs = 40L, sequential = true,
        ) { kind, id, _, _, time ->
            times.add(time)
            if (id == 0 && kind == RootTouchKind.UP) clock += 18
            true
        }
        assertTrue(result.completed)
        assertEquals(listOf(100L, 140L, 188L, 238L), times)
    }

    @Test fun continuousNoteIsNotInjectedWhenControlReleaseStalls() {
        var clock = 100L
        val actions = mutableListOf<Pair<Int, RootTouchKind>>()
        val result = RootTouchTimeline.play(
            listOf(RootStroke(1f, 1f, 0, 40), RootStroke(2f, 2f, 70, 50)),
            { clock }, { clock = maxOf(clock, it) },
            scheduledStartMs = 100L, maxDownLatenessMs = 40L, sequential = true,
        ) { kind, id, _, _, _ ->
            actions.add(id to kind)
            if (kind == RootTouchKind.UP) clock += 60
            true
        }
        assertEquals("LATE", result.status)
        assertEquals(listOf(0 to RootTouchKind.DOWN, 0 to RootTouchKind.UP), actions)
    }
    @Test fun overlappingControlsKeepNoteHeldAndPreservePointerIds() {
        val fixture = Fixture()
        val result = fixture.play(listOf(
            RootStroke(0f, 1f, 0, 80), RootStroke(0f, 2f, 0, 80),
            RootStroke(0f, 3f, 40, 120),
        ))
        assertTrue(result)
        assertEquals(listOf(
            "DOWN:0:[0]@0", "DOWN:1:[0, 1]@0", "DOWN:2:[0, 1, 2]@40",
            "UP:0:[0, 1, 2]@80", "UP:1:[1, 2]@80", "UP:2:[2]@160",
        ), fixture.events)
    }

    @Test fun releaseUsesActualDownTimeAfterALateStart() {
        val fixture = Fixture()
        fixture.extraDelay = 19
        assertTrue(fixture.play(listOf(RootStroke(1f, 2f, 0, 30))))
        assertEquals(listOf("DOWN:0:[0]@19", "UP:0:[0]@49"), fixture.events)
    }

    @Test fun interruptionReleasesTheActiveNoteBeforeAnotherCanStart() {
        val fixture = Fixture()
        fixture.stop = true
        assertFalse(fixture.play(listOf(RootStroke(1f, 2f, 0, 5_000))))
        assertEquals(listOf("DOWN:0:[0]@0", "UP:0:[0]@0"), fixture.events)
    }

    @Test fun rejectedDownCancelsThePossiblyActivePointerWithoutRetry() {
        val fixture = Fixture()
        fixture.rejectDown = true
        assertFalse(fixture.play(listOf(RootStroke(1f, 2f, 0, 100))))
        assertEquals(listOf("DOWN:0:[0]@0", "CANCEL:0:[0]@0"), fixture.events)
    }

    @Test fun explicitlyRejectedFirstDownDoesNotCreateAReleaseObligation() {
        val fixture = Fixture()
        fixture.rejectBeforeDispatch = true
        assertFalse(fixture.play(listOf(RootStroke(1f, 2f, 0, 100))))
        assertEquals(listOf("DOWN:0:[0]@0"), fixture.events)
        assertEquals("FAILED", fixture.status)
    }

    @Test fun stoppingAnOverlappedGestureCancelsControlWithoutCompletingItsClick() {
        val fixture = Fixture()
        fixture.stopAt = 60
        assertFalse(fixture.play(listOf(RootStroke(0f, 1f, 0, 80), RootStroke(0f, 2f, 40, 1_000))))
        assertEquals(listOf("DOWN:0:[0]@0", "DOWN:1:[0, 1]@40", "UP:1:[0, 1]@60", "CANCEL:0:[0]@60"), fixture.events)
        assertEquals("STOPPED", fixture.status)
    }

    @Test fun failedCleanupKeepsTheReleaseUnconfirmed() {
        val fixture = Fixture()
        fixture.rejectDown = true
        fixture.rejectCancel = true
        assertFalse(fixture.play(listOf(RootStroke(1f, 2f, 0, 100))))
        assertEquals("UNCONFIRMED", fixture.status)
        assertEquals(setOf(0), fixture.uncertainPointerIds)
    }

    @Test fun transportLossRetainsOnlyPointersWithoutConfirmedRelease() {
        var now = 0L
        val outcome = RootTouchTimeline.play(
            listOf(RootStroke(1f, 1f, 0, 1_000), RootStroke(2f, 2f, 40, 1_000)),
            now = { now },
            waitUntil = {
                if (it > 40L) throw RootTransportInterrupted()
                now = it
            },
        ) { kind, _, _, _, _ -> kind != RootTouchKind.CANCEL }
        assertEquals("UNCONFIRMED", outcome.status)
        assertEquals(40L, outcome.noteDown)
        assertEquals(40L, outcome.noteUp)
        assertEquals(setOf(0), outcome.uncertainPointerIds)
    }

    private class Fixture {
        var now = 0L
        var extraDelay = 0L
        var stop = false
        var rejectDown = false
        var rejectBeforeDispatch = false
        var rejectCancel = false
        var stopAt: Long? = null
        var status: String? = null
        var uncertainPointerIds: Set<Int>? = null
        val events = mutableListOf<String>()

        fun play(strokes: List<RootStroke>): Boolean = RootTouchTimeline.play(
            strokes, now = { now }, waitUntil = {
                if (stop && it > now) throw InterruptedException()
                stopAt?.let { stopTime ->
                    if (it >= stopTime) {
                        now = stopTime
                        throw InterruptedException()
                    }
                }
                now = it + extraDelay
                extraDelay = 0
            },
        ) { kind, id, pointers, _, timestamp ->
            events += "$kind:$id:${pointers.keys.toList()}@$timestamp"
            if (rejectBeforeDispatch) throw RootDispatchDenied()
            !(rejectDown && kind == RootTouchKind.DOWN || rejectCancel && kind == RootTouchKind.CANCEL)
        }.also {
            status = it.status
            uncertainPointerIds = it.uncertainPointerIds
        }.completed
    }
}
