package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.input.TapResult
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTimelineSoakTest {
    private val layout = DefaultLayouts.create(1).copy(referenceDisplay = ReferenceDisplay(2670, 1200))
    private val profile = requireNotNull(layout.profile)

    @Test fun stalledResumeRestoresTheWindowWithinTheStartupTimeout() = runTest {
        val notes = List(2) { index -> ScriptEvent(1000L * index, 200L, 60, "NOTE_1", Region.NATURAL, false) }
        val script = PerformanceScript(2, profile.id, ScriptConverter.fingerprint(profile, layout), "resume", 0,
            MonophonyPolicy.HIGHEST_NOTE, false, notes, provenance = ScriptProvenance("CLEAN", emptyList(), 0, 0))
        val plan = PlaybackPlanner.plan(script, layout, Region.NATURAL, false, 0, notes.size, 1.0)
        val runner = PlaybackRunner { testScheduler.currentTime }
        var restored = 0
        val result = withTimeout(5_000) {
            runner.run(plan, {}, {}, { true }, { _, _ ->
                runner.requestPause()
                TapResult.Completed
            }, { restored++ }, onPhase = { if (it == PlaybackPhase.PAUSED) runner.requestResume() },
                dryRunCompleted = true, warningsConfirmed = true, onResuming = { awaitCancellation() })
        }
        assertNotNull(result.failure)
        assertEquals(1, result.completed)
        assertEquals(1, restored)
    }

    @Test fun tenThirtyAndSixtyMinuteSchedulesDoNotDriftOnAVirtualClock() = runTest {
        for ((minutes, speed) in listOf(10 to 2.0, 30 to 0.5, 60 to 1.0)) {
            val count = (minutes * 60 * speed).toInt()
            val notes = List(count) { index ->
                val pitch = listOf(60, 62, 62, 64)[index % 4]
                val hold = listOf(10L, 180L, 450L, 100L)[index % 4]
                ScriptEvent(index * 1_000L, hold, pitch, "NOTE_${listOf(1, 2, 2, 3)[index % 4]}", Region.NATURAL, false)
            }
            val script = PerformanceScript(2, profile.id, ScriptConverter.fingerprint(profile, layout), "soak", 0,
                MonophonyPolicy.HIGHEST_NOTE, false, notes, provenance = ScriptProvenance("CLEAN", emptyList(), 0, 0))
            val plan = PlaybackPlanner.plan(script, layout, Region.NATURAL, false, 0, count, speed)
            assertTrue(plan.fatalErrors.toString(), plan.fatalErrors.isEmpty())
            val runner = PlaybackRunner { testScheduler.currentTime }
            val origin = testScheduler.currentTime
            var dispatched = 0
            var restored = 0
            val result = runner.run(plan, {}, {}, { true }, { target, duration ->
                val expected = plan.events[dispatched++]
                assertEquals(expected.event.noteKeyId, target)
                assertEquals(expected.predictedStartMs, testScheduler.currentTime - origin)
                assertEquals(expected.predictedEndMs - expected.predictedStartMs, duration)
                delay(duration)
                TapResult.Completed
            }, { restored++ }, dryRunCompleted = true, warningsConfirmed = true)
            assertEquals(count, result.completed)
            assertEquals(0, result.remaining)
            assertEquals(0L, result.maxLatenessMs)
            assertEquals(plan.events.last().predictedEndMs, result.elapsedMs)
            assertEquals(1, restored)
        }
    }
}
