package com.huanhren.deltaharmonica.input

import org.junit.Assert.*
import org.junit.Test

class RootTouchTimelineTest {
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
        }.also { status = it.status }.completed
    }
}
