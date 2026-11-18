package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackPlanTest {
    @Test fun gestureOverheadIsBudgetedOnlyWhenTheOriginalTimelineHasNoRoom() {
        val dense = script().copy(events=(0..2).map { ScriptEvent(it*100L,100,62,"NOTE_2",Region.NATURAL,false) })
        val plan=PlaybackPlanner.plan(dense,layout,Region.NATURAL,false,0,3,1.0,gestureOverheadMs=30)
        assertEquals(listOf(0L,130L,260L), plan.events.map { it.predictedStartMs })
        assertTrue(plan.warnings.any { it.contains("30") && it.contains("估算") })
        val spacious=dense.copy(events=dense.events.mapIndexed { i,e -> e.copy(startTimeMs=i*500L) })
        val room=PlaybackPlanner.plan(spacious,layout,Region.NATURAL,false,0,3,1.0,gestureOverheadMs=30)
        assertEquals(listOf(0L,500L,1000L),room.events.map { it.predictedStartMs })
    }

    @Test fun controlGestureOverheadIsIncludedInTheFirstEventBudget() {
        val plan=PlaybackPlanner.plan(script(),layout,Region.LOW,false,0,8,1.0,gestureOverheadMs=30)
        assertEquals(280L,plan.events.first().predictedStartMs)
        assertEquals(100L,plan.events.first().predictedEndMs-plan.events.first().predictedStartMs)
    }
    @Test fun extremeScriptTimeMustNotOverflowIntoPlayablePlan() {
        val original = script()
        val event = original.events.first()
        val extreme = original.copy(events=listOf(event.copy(startTimeMs=0, durationMs=100),
            event.copy(startTimeMs=Long.MAX_VALUE-1000, durationMs=100)))
        val calibrated = layout.copy(referenceDisplay=ReferenceDisplay(2670,1200))
        val plan = PlaybackPlanner.plan(extreme, calibrated, Region.NATURAL, false, 0, 2, .5)
        assertTrue("超大时间必须阻止播放", plan.fatalErrors.isNotEmpty())
        assertTrue("规划时间不能发生负数溢出", plan.events.all { it.predictedStartMs >= 0 && it.predictedEndMs >= it.predictedStartMs })
    }
    private val layout = DefaultLayouts.create(1).copy(referenceDisplay = ReferenceDisplay(2670,1200))
    private val profile = layout.profile!!
    private fun script() = PerformanceScript(schemaVersion=2, instrumentProfileId=profile.id,
        profileFingerprint=ScriptConverter.fingerprint(profile,layout), sourceMidiName="test.mid",sourceTrack=0,
        monophonyPolicy=MonophonyPolicy.HIGHEST_NOTE,mappingVerified=false,
        events=(0..7).map { ScriptEvent(it*100L,100L,62,"NOTE_2",Region.NATURAL,false) },
        provenance=ScriptProvenance(importStatus="CLEAN",warnings=emptyList(),discardedMs=0,unmappedCount=0))
    @Test fun planWarnsOfTimingAndAccountsForSwitchesWithoutOverlap() {
        val plan=PlaybackPlanner.plan(script(),layout,Region.LOW,true,0,8,1.0)
        assertTrue(plan.fatalErrors.isEmpty())
        assertEquals(1,plan.regionSwitches); assertEquals(1,plan.semitoneSwitches)
        assertEquals(8,plan.events.size)
        assertTrue(plan.events.zipWithNext().all { (a,b) -> a.predictedEndMs <= b.predictedStartMs })
        assertTrue(plan.warnings.isNotEmpty())
    }
    @Test fun legacyAndUnmappedCannotSilentlyPass() {
        val old = script().copy(schemaVersion=1,provenance=null)
        val plan=PlaybackPlanner.plan(old,layout,Region.NATURAL,false,0,8,1.0)
        assertTrue(plan.warnings.any { it.contains("历史") })
        val invalid=script().copy(provenance=script().provenance!!.copy(unmappedCount=1))
        assertTrue(PlaybackPlanner.plan(invalid,layout,Region.NATURAL,false,0,8,1.0).fatalErrors.isNotEmpty())
    }
    @Test fun rejectsInvalidTargetAndUnsafeDuration() {
        val s=script(); val broken=s.copy(events=s.events.toMutableList().apply { this[0]=this[0].copy(noteKeyId="missing",durationMs=3000) })
        assertTrue(PlaybackPlanner.plan(broken,layout,Region.NATURAL,false,0,8,1.0).fatalErrors.isNotEmpty())
    }
    @Test fun provenanceRoundTripsAndAppearsInDryRun() {
        val s=script().copy(provenance=ScriptProvenance("RECOVERED",listOf("轨道被截断"),101,0))
        val decoded=ScriptCodec.decode(ScriptCodec.encode(s))
        assertEquals(s.provenance,decoded.provenance)
        val plan=PlaybackPlanner.plan(decoded,layout,Region.NATURAL,false,0,8,.75)
        assertTrue(plan.warnings.any { it.contains("轨道被截断") })
        assertTrue(plan.warnings.any { it.contains("101") })
    }
    @Test fun completeSongHasNoSixteenEventLimitAndRetainsEveryEvent() {
        val many=script().copy(events=(0 until 149).map { ScriptEvent(it*300L,100,60,"NOTE_1",Region.NATURAL,false) })
        val plan=PlaybackPlanner.plan(many,layout,Region.NATURAL,false,0,many.events.size,1.0)
        assertEquals(149,plan.events.size)
        assertTrue(plan.fatalErrors.isEmpty())
        assertEquals(148,plan.events.last().sourceIndex)
    }
    @Test fun switchDelayCanBeAbsorbedByRestAndDoesNotGrowAutomatically() {
        val notes=(0 until 8).map { i -> ScriptEvent(if(i==0) 0 else 1000L+i*300,100,62,"NOTE_2",Region.NATURAL,false) }
        val plan=PlaybackPlanner.plan(script().copy(events=notes),layout,Region.LOW,false,0,8,1.0)
        assertEquals(280,plan.events.first().predictedStartMs)
        assertEquals(plan.events[1].requestedStartMs,plan.events[1].predictedStartMs)
        assertEquals(plan.events.last().requestedStartMs,plan.events.last().predictedStartMs)
    }
    @Test fun invalidEventOutsideAuditionStillBlocksPlayback() {
        val many=script().copy(events=(0 until 149).map { ScriptEvent(it*300L,100,60,if(it==148) "missing" else "NOTE_1",Region.NATURAL,false) })
        val plan=PlaybackPlanner.plan(many,layout,Region.NATURAL,false,0,8,1.0)
        assertTrue(plan.fatalErrors.any { it.contains("149") })
        assertEquals(148,PlaybackPlanner.mappedEventCount(many,layout))
    }
    @Test fun doubleSpeedScalesBothStartAndDuration() {
        val plan=PlaybackPlanner.plan(script(),layout,Region.NATURAL,false,0,8,2.0)
        assertEquals(50L,plan.events[1].requestedStartMs)
        assertEquals(50L,plan.events[0].predictedEndMs-plan.events[0].predictedStartMs)
    }
    @Test fun combinedSwitchAddsOnlyGestureLeadAndTracksActualState() {
        val plan=PlaybackPlanner.plan(script(),layout,Region.LOW,true,0,8,1.0,combined=true)
        assertEquals(1,plan.regionSwitches); assertEquals(1,plan.semitoneSwitches)
        assertEquals(40L,plan.events.first().predictedStartMs)
        assertFalse(plan.events[1].regionSwitch); assertFalse(plan.events[1].semitoneSwitch)
        assertEquals(plan.events.last().requestedStartMs+250,plan.events.last().predictedStartMs)
    }
    @Test fun sharedConfirmedStateDrivesFirstSwitchAndUnknownIsRejected() {
        val previous=PlaybackPreparation.runtimeState.value
        try {
            PlaybackPreparation.runtimeState.value=InstrumentRuntimeState().confirmRegion(Region.LOW).confirmSemitone(true)
            val shared=PlaybackPreparation.runtimeState.value
            assertEquals(SyncStatus.USER_CONFIRMED,shared.syncStatus)
            val plan=PlaybackPlanner.plan(script(),layout,shared.pitchRegion,shared.semitoneEnabled,0,8,1.0,combined=true)
            assertTrue(plan.events.first().regionSwitch); assertTrue(plan.events.first().semitoneSwitch)
            val unknown=PlaybackPlanner.plan(script(),layout,null,null,0,8,1.0,combined=true)
            assertTrue(unknown.fatalErrors.any { it.contains("确认") })
        } finally { PlaybackPreparation.runtimeState.value=previous }
    }
    @Test fun shortNotesAreExtendedAndCountedWithoutOverlapping() {
        val song=script().copy(events=(0 until 8).map { ScriptEvent(it*100L,50,60,"NOTE_1",Region.NATURAL,false) })
        val plan=PlaybackPlanner.plan(song,layout,Region.NATURAL,false,0,8,1.0,minimumTouchMs=150)
        assertEquals(8,plan.extendedNotes)
        assertTrue(plan.events.all { it.predictedEndMs-it.predictedStartMs == 150L })
        assertTrue(plan.events.zipWithNext().all { (a,b) -> a.predictedEndMs <= b.predictedStartMs })
        assertTrue(plan.warnings.any { it.contains("8") && it.contains("最短") })
    }
    @Test fun speedScalesNoteStartAndDurationInBothDirections() {
        val slow=PlaybackPlanner.plan(script(),layout,Region.NATURAL,false,0,8,.5)
        val fast=PlaybackPlanner.plan(script(),layout,Region.NATURAL,false,0,8,2.0)
        assertEquals(200L,slow.events[1].requestedStartMs)
        assertEquals(200L,slow.events[0].predictedEndMs-slow.events[0].predictedStartMs)
        assertEquals(50L,fast.events[1].requestedStartMs)
        assertEquals(50L,fast.events[0].predictedEndMs-fast.events[0].predictedStartMs)
    }
    @Test fun waitingShowsSelectedEventCountInsteadOfZero() {
        val plan=PlaybackPlanner.plan(script(),layout,Region.NATURAL,false,0,8,1.0)
        val state=PlaybackProgress.waitingForGame(plan)
        assertEquals(8,state.total); assertEquals(0,state.completed); assertEquals(PlaybackStatus.WAITING_FOR_GAME,state.phase)
        val song=script().copy(events=(0 until 149).map { ScriptEvent(it*300L,100,60,"NOTE_1",Region.NATURAL,false) })
        val full=PlaybackPlanner.plan(song,layout,Region.NATURAL,false,0,149,1.0)
        assertEquals(149,PlaybackProgress.waitingForGame(full).total)
        try { PlaybackProgress.waitingForGame(full.copy(events=emptyList())); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun playbackOptimizesAmbiguousPitchFromConfirmedCurrentState() {
        val song=script().copy(events=(0 until 8).map { ScriptEvent(it*300L,100,72,"NOTE_8",Region.NATURAL,false) })
        val plan=PlaybackPlanner.plan(song,layout,Region.HIGH,false,0,8,1.0)
        assertTrue(plan.fatalErrors.isEmpty())
        assertEquals(0,plan.regionSwitches)
        assertTrue(plan.events.all { it.event.noteKeyId=="NOTE_1" && it.event.pitchRegion==Region.HIGH && it.event.midiNote==72 })
    }
    @Test fun unknownRegionCanBeSelectedButUnknownSemitoneIsRejected() {
        val knownSemi=PlaybackPlanner.plan(script(),layout,null,false,0,8,1.0)
        assertTrue(knownSemi.fatalErrors.isEmpty()); assertTrue(knownSemi.events.first().regionSwitch)
        assertTrue(PlaybackPlanner.plan(script(),layout,null,null,0,8,1.0).fatalErrors.isNotEmpty())
    }
}
