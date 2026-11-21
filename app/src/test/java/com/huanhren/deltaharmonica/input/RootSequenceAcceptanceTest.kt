package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.midi.MidiParser
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.midi.ScriptCodec
import com.huanhren.deltaharmonica.midi.ScriptConverter
import com.huanhren.deltaharmonica.model.Coordinates
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.ReferenceDisplay
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.model.ScreenSize
import com.huanhren.deltaharmonica.playback.PlaybackPlan
import com.huanhren.deltaharmonica.playback.PlaybackPlanner
import com.huanhren.deltaharmonica.playback.ROOT_RECOVERY_WINDOW_MS
import com.huanhren.deltaharmonica.playback.ROOT_STROKE_DISPATCH_MARGIN_MS
import com.huanhren.deltaharmonica.playback.RootPlaybackMetrics
import com.huanhren.deltaharmonica.playback.RootPlaybackTiming
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

class RootSequenceAcceptanceTest {
    private val layout = DefaultLayouts.create(1).copy(referenceDisplay = ReferenceDisplay(2670, 1200))
    private val profile = requireNotNull(layout.profile)
    private val points = layout.targets.associate { it.id to Coordinates.toScreen(it.position, ScreenSize(2670, 1200)) }
    private val targetAt = points.entries.associate { (id, point) -> point to id }
    private val timing = RootPlaybackTiming()

    @Test fun fullMidiScoreCompletesAtBothSpeedsWithOneDownAndUpPerNote() {
        val source = fullScore()
        for (speed in listOf(1.0, 2.0)) {
            val plan = prepare(source, speed)
            assertTrue(plan.regionSwitches > 0)
            assertTrue(plan.semitoneSwitches > 0)
            val helper = play(plan)
            verifyCompletion(plan, helper)
            reportTimings("B-full-360", speed, plan, helper)
        }
    }

    @Test fun mixedScoresKeepTimingAndResourcesBoundedAcrossTheLoadMatrix() {
        for (count in listOf(8, 16, 32, 360, 1_000, 4_096)) {
            val source = score(count, listOf(62, 62, 63, 79, 55, 55, 56, 76, 51, 51, 52, 75),
                listOf(220, 260, 315, 420, 600, 220, 250, 340, 500, 150, 190, 495),
                listOf(21, 85, 180, 290, 440, 22, 65, 129, 421, 31, 81, 179))
            for (speed in listOf(1.0, 2.0)) {
                val plan = prepare(source, speed)
                assertTrue(plan.regionSwitches > 0)
                assertTrue(plan.semitoneSwitches > 0)
                assertTrue(plan.extendedNotes > 0)
                assertTrue(plan.events.zipWithNext().any { (previous, next) ->
                    previous.event.noteKeyId == next.event.noteKeyId
                })
                val pauseIndex = count / 2
                val pauseMs = 731L
                var paused = false
                val helper = play(plan, beforeTouchWait = { deadline ->
                    val noteIndex = progress.size
                    val pauseOffset = if (paused) pauseMs else 0L
                    val plannedDown = origin + plan.events[noteIndex].predictedStartMs + pauseOffset
                    if (noteIndex % 4 == 3 && activeTarget == null && deadline == plannedDown) {
                        now = maxOf(now, deadline) + 5L
                    }
                }) {
                    if (progress.size == pauseIndex && !paused) {
                        paused = true
                        now += pauseMs
                        pauseMs
                    } else 0L
                }

                assertTrue(paused)
                verifyCompletion(plan, helper, pauseIndex, pauseMs, onsetDelay = { if (it % 4 == 3) 5L else 0L })
                reportTimings("mixed-$count", speed, plan, helper)
            }
        }
    }

    @Test fun aRecoverableDelayAfter342PreservesEveryFollowingDownAndUpAtBothSpeeds() {
        for (speed in listOf(1.0, 2.0)) {
            val plan = prepare(fullScore(), speed)
            val delayMs = timing.maxDownLatenessMs + 17L
            val helper = play(plan, afterBoundaryWait = {
                if (progress.size == 342) now += delayMs
            })

            verifyCompletion(plan, helper, onsetDelay = null)
            val receipts = helper.scheduledReceipts
            assertEquals((339..344).toList(), receipts.subList(339, 345).map { it.index })
            assertEquals((342..359).toList(), receipts.drop(342).map { it.index })
            // Reserved transport time in the control strokes can absorb part of the stall.
            val lateness = requireNotNull(receipts[342].outcome.noteDown) - receipts[342].planned
            assertTrue("${speed}x failed to exercise the recovery window: $lateness ms",
                lateness in (timing.maxDownLatenessMs + 1L)..delayMs)
            val final = receipts.last().outcome
            val (down, up) = helper.noteStrokes.takeLast(2)
            assertEquals(down.time, final.noteDown)
            assertEquals(up.time, final.noteUp)
            assertEquals(up, helper.strokes.last())
            assertEquals(up.ackTime, helper.now)
            reportTimings("B-full-360-rephase-after-342", speed, plan, helper)
        }
    }

    @Test fun aSeverelyLateBoundaryAfter342DoesNotDispatchAnyOfTheRemaining18Notes() {
        for (speed in listOf(1.0, 2.0)) {
            val plan = prepare(fullScore(), speed)
            val helper = play(plan, afterBoundaryWait = {
                if (progress.size == 342) now += timing.maxDownLatenessMs + ROOT_RECOVERY_WINDOW_MS + 1L
            })

            assertEquals(RootSequenceOutcome("LATE", 342), helper.outcome)
            assertEquals(342, helper.progress.size)
            assertEquals(342 * 2, helper.noteStrokes.size)
            assertEquals(ScheduledReceipt(342, RootTouchOutcome("LATE", null, null),
                helper.origin + plan.events[342].predictedStartMs), helper.scheduledReceipts.last())
            assertNull(helper.activeTarget)
            reportTimings("B-full-360-stale-after-342", speed, plan, helper)
        }
    }

    @Test fun denseDoubleSpeedKeepsEveryPitchAndEnforcesEveryReleaseAndControlInterval() {
        val source = score(32, listOf(62, 62, 63, 63, 62, 74, 50, 50, 75, 75, 51, 51, 62, 62, 62, 67),
            listOf(133, 112, 150, 87, 112, 136, 120, 200), listOf(82, 57, 85, 50, 57, 75, 65, 135))
        val plan = prepare(source, 2.0)
        assertTrue(plan.extendedNotes > 0)
        assertTrue(plan.shortenedNotes > 0)
        assertTrue(plan.events.zipWithNext().any { (a, b) -> a.event.noteKeyId == b.event.noteKeyId })
        assertTrue(plan.warnings.any { it.contains("起音间隔不足") && it.contains("控制切换") })
        val helper = play(plan)
        verifyCompletion(plan, helper)
        reportTimings("C-dense-32", 2.0, plan, helper)
    }

    @Test fun fullSequenceResumeShiftsOnlyTheUnplayedNotes() {
        val pauseIndex = 178
        val pauseMs = 700L
        val plan = prepare(fullScore(), 1.0)
        var paused = false
        val helper = play(plan) {
            if (progress.size == pauseIndex && !paused) {
                paused = true
                now += pauseMs
                pauseMs
            } else 0L
        }
        verifyCompletion(plan, helper, pauseIndex, pauseMs)
    }

    @Test fun stopDuringFinalHoldReleasesThePointerWithoutCountingTheInterruptedNote() {
        for (speed in listOf(1.0, 2.0)) {
            val plan = prepare(fullScore(), speed)
            val helper = play(plan, beforeTouchWait = { deadline ->
                if (progress.size == plan.events.lastIndex && activeTarget?.startsWith("NOTE_") == true) {
                    now = deadline - 1
                    throw InterruptedException()
                }
            })
            assertEquals(RootSequenceOutcome("STOPPED", 359), helper.outcome)
            assertEquals("STOPPED", helper.progress.last().status)
            assertNull(helper.activeTarget)
            assertEquals(360, helper.noteStrokes.count { it.kind == RootTouchKind.DOWN })
            assertEquals(360, helper.noteStrokes.count { it.kind == RootTouchKind.UP })
            reportTimings("B-full-360-stop-during-final-hold", speed, plan, helper)
            verifyCompletion(plan, play(plan))
        }
    }

    @Test fun boundaryHeartbeatLossPreservesCompletedNotesAndReportsTransportFailure() {
        val plan = prepare(fullScore(), 1.0)
        val helper = play(plan) {
            if (progress.size == 342) throw RootTransportInterrupted()
            0L
        }
        assertEquals(RootSequenceOutcome("TRANSPORT", 342), helper.outcome)
        assertEquals(342, helper.progress.size)
        assertEquals(342, helper.noteStrokes.count { it.kind == RootTouchKind.UP })
        assertNull(helper.activeTarget)
        verifyCompletion(plan, play(plan))
    }

    @Test fun heartbeatLossDuringANoteReleasesThePointerAndDoesNotAdvanceProgress() {
        val plan = prepare(fullScore(), 2.0)
        val helper = play(plan, beforeTouchWait = { deadline ->
            if (progress.size == 72 && activeTarget?.startsWith("NOTE_") == true) {
                now = deadline - 1
                throw RootTransportInterrupted()
            }
        })
        assertEquals(RootSequenceOutcome("TRANSPORT", 72), helper.outcome)
        assertEquals("TRANSPORT", helper.progress.last().status)
        assertNull(helper.activeTarget)
        assertEquals(73, helper.noteStrokes.count { it.kind == RootTouchKind.UP })
        verifyCompletion(plan, play(plan))
    }

    @Test fun repeatedFullScoresCoverTenThirtyAndSixtyMinutesOnTheVirtualClock() {
        val plan = prepare(fullScore(), 2.0)
        var playedMs = 0L
        var runs = 0
        for (minutes in listOf(10, 30, 60)) {
            while (playedMs < minutes * 60_000L) {
                val helper = play(plan)
                assertEquals(RootSequenceOutcome("OK", 360), helper.outcome)
                assertEquals(360, helper.noteStrokes.count { it.kind == RootTouchKind.UP })
                assertNull(helper.activeTarget)
                playedMs += helper.now - helper.origin
                runs++
            }
            println("Root software soak ${minutes} min elapsedMs=$playedMs completedSessions=$runs")
        }
    }

    private fun reportTimings(label: String, speed: Double, plan: PlaybackPlan, helper: MockRootHelper) {
        val down = helper.noteStrokes.filter { it.kind == RootTouchKind.DOWN }
        val up = helper.noteStrokes.filter { it.kind == RootTouchKind.UP }
        val timed = helper.scheduledReceipts.filter { it.outcome.completed }
        val cancelled = helper.scheduledReceipts.count { it.outcome.status == "STOPPED" }
        val failures = helper.scheduledReceipts.size - timed.size - cancelled
        val metrics = RootPlaybackMetrics(timing.maxDownLatenessMs)
        for ((index, receipt, planned) in timed) {
            val event = plan.events[index]
            metrics.record(planned, requireNotNull(receipt.noteDown), requireNotNull(receipt.noteUp),
                event.predictedEndMs - event.predictedStartMs,
                event.predictedStartMs - event.requestedStartMs - plan.rootPreRollMs)
        }
        val finalUpOffsetMs = timed.lastOrNull()?.let { (index, receipt, planned) ->
            val event = plan.events[index]
            requireNotNull(receipt.noteUp) - planned - (event.predictedEndMs - event.predictedStartMs)
        }
        val totalDelayMs = timed.sumOf { requireNotNull(it.outcome.noteDown) - it.planned }
        println("$label ${speed}x planned=${plan.events.size} down=${down.size} up=${up.size} " +
            "completed=${helper.outcome.completed} cancelled=$cancelled failed=$failures unstarted=${plan.events.size - down.size} " +
            "regionSwitches=${plan.regionSwitches} semitoneSwitches=${plan.semitoneSwitches} " +
            "extendedNotes=${plan.extendedNotes} shortenedNotes=${plan.shortenedNotes} " +
            "${metrics.summary()} totalDispatchDelayMs=$totalDelayMs finalCompletedUpOffsetMs=$finalUpOffsetMs " +
            "plannedMs=${plan.estimatedTotalMs} elapsedMs=${helper.now - helper.origin} " +
            "activePointerCleared=${helper.activeTarget == null} gameAudio=NOT_TESTED")
    }

    private fun verifyCompletion(
        plan: PlaybackPlan,
        helper: MockRootHelper,
        pauseIndex: Int = 0,
        pauseMs: Long = 0L,
        onsetDelay: ((Int) -> Long)? = { 0L },
    ) {
        val noteCount = plan.events.size
        assertEquals(RootSequenceOutcome("OK", noteCount), helper.outcome)
        assertEquals(noteCount, helper.progress.count { it.completed })
        assertNull(helper.activeTarget)
        assertEquals(noteCount * 2, helper.noteStrokes.size)
        var region = Region.NATURAL
        var semitone = false
        val downStrokes = helper.strokes.filter { it.kind == RootTouchKind.DOWN }
        val expectedTargets = buildList {
            for (note in plan.events) {
                if (note.regionSwitch) add("REGION_${note.event.pitchRegion.name}")
                if (note.semitoneSwitch) add("SEMITONE_TOGGLE")
                add(note.event.noteKeyId)
            }
        }
        assertEquals(expectedTargets, downStrokes.map { it.target })
        val playedPitches = downStrokes.mapNotNull { stroke ->
            when {
                stroke.target.startsWith("REGION_") -> {
                    region = Region.valueOf(stroke.target.removePrefix("REGION_"))
                    null
                }
                stroke.target == "SEMITONE_TOGGLE" -> {
                    semitone = !semitone
                    null
                }
                else -> profile.pitches.getValue(region)[stroke.target.removePrefix("NOTE_").toInt() - 1] +
                    if (semitone) 1 else 0
            }
        }
        assertEquals(plan.events.map { it.event.midiNote }, playedPitches)
        assertEquals(2 * (plan.events.size + plan.regionSwitches + plan.semitoneSwitches), helper.strokes.size)
        helper.strokes.chunked(2).filter { !it.first().target.startsWith("NOTE_") }.forEach { (down, up) ->
            assertEquals(timing.controlHoldMs, up.time - down.ackTime)
        }
        helper.noteStrokes.chunked(2).forEachIndexed { index, (down, up) ->
            val planned = plan.events[index]
            val shift = if (index >= pauseIndex) pauseMs else 0L
            assertEquals(RootTouchKind.DOWN, down.kind)
            assertEquals(RootTouchKind.UP, up.kind)
            assertEquals(planned.event.noteKeyId, down.target)
            assertEquals(down.target, up.target)
            val targetOnset = helper.origin + planned.predictedStartMs + shift
            assertEquals(targetOnset, helper.scheduledReceipts[index].planned)
            if (onsetDelay == null) assertTrue(down.time >= targetOnset)
            else assertEquals(targetOnset + onsetDelay(index), down.time)
            assertEquals(planned.predictedEndMs - planned.predictedStartMs, up.time - down.ackTime)
            assertTrue(up.time - down.ackTime >= timing.minNoteHoldMs)
            assertEquals(down.time, helper.progress[index].noteDown)
            assertEquals(up.time, helper.progress[index].noteUp)
        }
        helper.strokes.chunked(2).zipWithNext().forEach { (old, next) ->
            val up = old.last()
            val down = next.first()
            val minimumGap = when {
                !up.target.startsWith("NOTE_") -> timing.controlSettleMs
                up.target == down.target -> timing.sameKeyGapMs
                else -> timing.differentKeyGapMs
            }
            assertTrue("Physical release-to-DOWN gap for ${up.target} -> ${down.target}",
                down.time - up.ackTime >= minimumGap)
        }
        helper.noteStrokes.chunked(2).zipWithNext().filter { (old, next) -> old.first().target == next.first().target }
            .forEach { (old, next) -> assertTrue(next.first().time - old.last().ackTime >= timing.sameKeyGapMs) }
        assertEquals(helper.noteStrokes.last().ackTime, helper.now)
    }

    private fun prepare(notes: List<SourceNote>, speed: Double): PlaybackPlan {
        // At 120 BPM and 500 PPQ the ticks and millisecond timestamps are the same.
        val midi = Sequence(Sequence.PPQ, 500)
        val track = midi.createTrack()
        for (note in notes) {
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, note.pitch, 90), note.start))
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 0, note.pitch, 0), note.start + note.hold))
        }
        val bytes = ByteArrayOutputStream()
        assertTrue(MidiSystem.write(midi, 0, bytes) > 0)
        val parsed = MidiParser.parse(bytes.toByteArray())
        assertEquals(notes, parsed.tracks.single().notes.map { SourceNote(it.startMs, it.durationMs, it.pitch) })
        val report = ScriptConverter.convert(parsed, "root-acceptance.mid", 0, MonophonyPolicy.HIGHEST_NOTE, 0, profile, layout)
        assertEquals(notes.size, report.compatibility.sourceNoteCount)
        assertEquals(notes.size, report.compatibility.parsedNoteCount)
        assertEquals(notes.size, report.compatibility.monophonicCount)
        assertEquals(0L, report.compatibility.discardedOverlapMs)
        val script = ScriptCodec.decode(ScriptCodec.encode(report.script))
        assertEquals(notes.size, PlaybackPlanner.mappedEventCount(script, layout))
        val plan = PlaybackPlanner.plan(script, layout, Region.NATURAL, false, 0, notes.size, speed, rootTiming = timing)
        assertTrue(plan.fatalErrors.toString(), plan.fatalErrors.isEmpty())
        assertEquals(notes.size, plan.events.size)
        assertEquals(notes.indices.toList(), plan.events.map { it.sourceIndex })
        assertEquals(notes.map { it.pitch }, plan.events.map { it.event.midiNote })
        return plan
    }

    private fun play(
        plan: PlaybackPlan,
        beforeTouchWait: MockRootHelper.(Long) -> Unit = {},
        afterBoundaryWait: MockRootHelper.() -> Unit = {},
        atBoundary: MockRootHelper.() -> Long = { 0L },
    ): MockRootHelper {
        val planned = RootSequencePlanner.build(plan, points)
        val upload = RootSequenceUpload(planned.size)
        planned.chunked(RootSequenceProtocol.CHUNK_SIZE).forEachIndexed { index, chunk ->
            assertTrue(upload.append(index * RootSequenceProtocol.CHUNK_SIZE, chunk.joinToString("~", transform = RootSequenceProtocol::encode)))
        }
        val steps = requireNotNull(upload.commit())
        assertEquals(planned, steps)
        val helper = MockRootHelper()
        helper.outcome = RootSequenceTimeline.play(
            steps, now = { helper.now },
            waitFor = { deadline ->
                val pause = helper.atBoundary()
                if (pause == 0L) {
                    helper.now = maxOf(helper.now, deadline)
                    helper.afterBoundaryWait()
                }
                pause
            },
            playGesture = { error("Sequence gestures must use the planned clock") },
            playTimedGesture = { step, start ->
                RootTouchTimeline.play(
                    step.strokes, now = { helper.now },
                    waitUntil = { deadline ->
                        helper.beforeTouchWait(deadline)
                        helper.now = maxOf(helper.now, deadline)
                    },
                    scheduledStartMs = start,
                    maxDownLatenessMs = step.maxLatenessMs + ROOT_RECOVERY_WINDOW_MS,
                    sequential = true,
                    settlingSlackMs = ROOT_STROKE_DISPATCH_MARGIN_MS,
                    emit = { kind, id, active, _, time -> helper.emit(kind, active.getValue(id), time) },
                )
            },
            onNote = { index, receipt ->
                assertEquals(helper.progress.size, index)
                helper.progress.add(receipt)
            },
            onScheduledNote = { index, receipt, planned ->
                assertEquals(helper.scheduledReceipts.size, index)
                helper.scheduledReceipts.add(ScheduledReceipt(index, receipt, planned))
            },
        )
        return helper
    }

    private data class EmittedStroke(val kind: RootTouchKind, val target: String, val time: Long, val ackTime: Long)

    private data class ScheduledReceipt(val index: Int, val outcome: RootTouchOutcome, val planned: Long)

    private inner class MockRootHelper {
        val origin = 1_000L
        var now = origin
        var activeTarget: String? = null
        val strokes = mutableListOf<EmittedStroke>()
        val noteStrokes get() = strokes.filter { it.target.startsWith("NOTE_") }
        val progress = mutableListOf<RootTouchOutcome>()
        val scheduledReceipts = mutableListOf<ScheduledReceipt>()
        lateinit var outcome: RootSequenceOutcome

        fun emit(kind: RootTouchKind, stroke: RootStroke, time: Long): Boolean {
            val target = targetAt.getValue(ScreenPoint(stroke.x, stroke.y))
            when (kind) {
                RootTouchKind.DOWN -> {
                    assertNull("Sequential helper must have only one active pointer", activeTarget)
                    activeTarget = target
                }
                RootTouchKind.UP, RootTouchKind.CANCEL -> {
                    assertEquals(target, activeTarget)
                    activeTarget = null
                }
            }
            now += 2L // Deterministic per-injection transport cost for this software model.
            strokes.add(EmittedStroke(kind, target, time, now))
            return true
        }
    }

    private data class SourceNote(val start: Long, val hold: Long, val pitch: Int)

    // Same note, interval and hold pattern as scripts/generate-root-game-fixtures.ps1.
    private fun fullScore() = score(360, listOf(75, 55, 63, 79, 51, 67, 73, 57, 49, 81, 68, 53, 78, 62, 58, 74),
        listOf(430, 420, 490, 660), listOf(190, 120, 310, 440))

    private fun score(count: Int, pitches: List<Int>, intervals: List<Int>, holds: List<Int>): List<SourceNote> {
        var start = 0L
        return List(count) { index ->
            SourceNote(start, holds[index % holds.size].toLong(), pitches[index % pitches.size]).also {
                start += intervals[index % intervals.size]
            }
        }
    }
}
