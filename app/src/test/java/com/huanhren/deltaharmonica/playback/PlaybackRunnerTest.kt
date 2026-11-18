package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.input.TapResult
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackRunnerTest {
    private val layout = DefaultLayouts.create(1).copy(referenceDisplay=ReferenceDisplay(2670,1200))
    private val profile = layout.profile!!
    private val script = PerformanceScript(schemaVersion=2,instrumentProfileId=profile.id,profileFingerprint=ScriptConverter.fingerprint(profile,layout),sourceMidiName="x",sourceTrack=0,monophonyPolicy=MonophonyPolicy.HIGHEST_NOTE,mappingVerified=false,events=(0..7).map { ScriptEvent(it*300L,100,62,"NOTE_2",Region.NATURAL,false) },provenance=ScriptProvenance("CLEAN",emptyList(),0,0))
    @Test fun stopWaitsForCurrentGestureAndRestoresOnce() = runBlocking {
        val plan=PlaybackPlanner.plan(script,layout,Region.NATURAL,false,0,8,1.0)
        val runner=PlaybackRunner(); val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        var calls=0; var restore=0
        val job=async(start=CoroutineStart.UNDISPATCHED) {
            runner.run(plan, { }, { }, { true }, { _,_ -> calls++; started.complete(Unit); release.await(); TapResult.Completed }, { restore++ }, dryRunCompleted=true, warningsConfirmed=true)
        }
        started.await(); runner.requestStop(); assertFalse(job.isCompleted)
        release.complete(Unit); val result=job.await()
        assertEquals(1,calls); assertEquals(1,restore); assertEquals(7,result.remaining); assertTrue(result.stopped)
    }
    @Test fun failureStopsFutureNotesAndRestores() = runBlocking {
        val plan=PlaybackPlanner.plan(script,layout,Region.NATURAL,false,0,8,1.0)
        var count=0; var restore=0
        val result=PlaybackRunner().run(plan,{}, {}, { true }, { _,_ -> count++; TapResult.TimedOut }, { restore++ }, dryRunCompleted=true, warningsConfirmed=true)
        assertEquals(1,count); assertEquals(1,restore); assertEquals(8,result.remaining)
        assertNotNull(result.failure)
    }
    @Test fun refusesUnconfirmedWarningsAndRestoresAfterException() = runBlocking {
        val plan=PlaybackPlanner.plan(script,layout,Region.NATURAL,false,0,8,1.0)
        var restores=0
        try { PlaybackRunner().run(plan,{}, {}, { true }, { _,_ -> TapResult.Completed }, { restores++ }, dryRunCompleted=true) ; fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0,restores)
        val result=PlaybackRunner().run(plan,{}, {}, { true }, { _,_ -> error("broken") }, { restores++ }, dryRunCompleted=true,warningsConfirmed=true)
        assertNotNull(result.failure); assertEquals(1,restores); assertEquals(8,result.remaining)
    }
    @Test fun serializesControlsBeforeNotesAndRejectsConcurrentRun() = runBlocking {
        val plan=PlaybackPlanner.plan(script,layout,Region.LOW,true,0,8,1.0,controlMs=1,settleMs=0)
        val runner=PlaybackRunner(); val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val calls=mutableListOf<String>(); var restores=0
        val first=async(start=CoroutineStart.UNDISPATCHED) {
            runner.run(plan,{}, {}, { true }, { id,_ -> calls.add(id); if(calls.size==1) { started.complete(Unit); release.await() }; TapResult.Completed }, { restores++ }, dryRunCompleted=true,warningsConfirmed=true)
        }
        started.await()
        try { runner.run(plan,{}, {}, { true }, { _,_ -> TapResult.Completed }, {},dryRunCompleted=true,warningsConfirmed=true); fail() }
        catch (_: IllegalStateException) { }
        release.complete(Unit); val result=first.await()
        assertEquals(listOf("REGION_NATURAL","SEMITONE_TOGGLE","NOTE_2"),calls.take(3))
        assertEquals(8,result.completed); assertEquals(1,restores)
    }
    @Test fun auditionExecutesEightAndWholeSongExecutesAllEvents() = runBlocking {
        val song=script.copy(events=(0 until 149).map { ScriptEvent(it*2L,1,60,"NOTE_1",Region.NATURAL,false) })
        for (length in listOf(8,149)) {
            val plan=PlaybackPlanner.plan(song,layout,Region.NATURAL,false,0,length,1.0)
            var dispatched=0; var restored=0
            val result=PlaybackRunner().run(plan,{}, {}, { true }, { _,_ -> dispatched++; TapResult.Completed }, { restored++ },dryRunCompleted=true,warningsConfirmed=true)
            assertEquals(length,dispatched); assertEquals(length,result.completed); assertEquals(0,result.remaining); assertEquals(1,restored)
        }
    }
    @Test fun failedCombinedGestureStopsWithoutFurtherEventsAndRestores() = runBlocking {
        val plan=PlaybackPlanner.plan(script,layout,Region.LOW,false,0,8,1.0,combined=true)
        var combined=0; var singles=0; var restored=0
        val result=PlaybackRunner().run(plan,{}, {}, { true }, { _,_ -> singles++; TapResult.Completed }, { restored++ },
            dryRunCompleted=true,warningsConfirmed=true,dispatchCombined={ _,_ -> combined++; TapResult.Cancelled })
        assertEquals(1,combined); assertEquals(0,singles); assertEquals(8,result.remaining); assertEquals(1,restored)
        assertNotNull(result.failure)
    }
    @Test fun sendsEachScaledNoteDurationToTheGesture() = runBlocking {
        val song=script.copy(events=(0 until 8).map { i -> ScriptEvent(i*100L,if(i%2==0) 20 else 60,60,"NOTE_1",Region.NATURAL,false) })
        val plan=PlaybackPlanner.plan(song,layout,Region.NATURAL,false,0,8,2.0,combined=true)
        val durations=mutableListOf<Long>()
        val result=PlaybackRunner().run(plan,{}, {}, { true }, { _,_ -> fail("unexpected single gesture"); TapResult.Rejected }, {},dryRunCompleted=true,warningsConfirmed=true,
            dispatchCombined={ _,duration -> durations.add(duration); TapResult.Completed })
        assertEquals(8,result.completed)
        assertEquals(listOf(10L,30L,10L,30L,10L,30L,10L,30L),durations)
    }
    @Test fun failedOverlayRestoreIsReportedAndDoesNotLeaveTheGateLocked() = runBlocking {
        val plan=PlaybackPlanner.plan(script,layout,Region.NATURAL,false,0,8,1.0)
        val runner=PlaybackRunner(); var restores=0
        try {
            runner.run(plan,{}, {}, { true }, { _,_ -> TapResult.Cancelled }, { restores++; error("restore failed") },dryRunCompleted=true,warningsConfirmed=true)
            fail("restore failure must not be reported as success")
        } catch(e: IllegalStateException) { assertEquals("restore failed",e.message) }
        val result=runner.run(plan,{}, {}, { true }, { _,_ -> TapResult.Cancelled }, { restores++ },dryRunCompleted=true,warningsConfirmed=true)
        assertNotNull(result.failure); assertEquals(2,restores)
    }
    @Test fun nextNoteWaitsUntilPreviousCallback() = runBlocking {
        val plan=PlaybackPlanner.plan(script,layout,Region.NATURAL,false,0,8,1.0)
        val first=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val second=CompletableDeferred<Unit>()
        val runner=PlaybackRunner(); var calls=0
        val job=async(start=CoroutineStart.UNDISPATCHED) {
            runner.run(plan,{}, {}, { true }, { _,_ ->
                calls++; if(calls==1) { first.complete(Unit); release.await() } else { second.complete(Unit); runner.requestStop() }
                TapResult.Completed
            },{},dryRunCompleted=true,warningsConfirmed=true)
        }
        first.await(); delay(400); assertEquals(1,calls); release.complete(Unit); second.await(); job.await(); assertEquals(2,calls)
    }
    @Test fun failingStatusObserverCannotPreventOverlayRestore() = runBlocking {
        val plan=PlaybackPlanner.plan(script,layout,Region.NATURAL,false,0,8,1.0)
        var restored=0
        try {
            PlaybackRunner().run(plan,{}, {}, { true }, { _,_ -> TapResult.Cancelled }, { restored++ },
                onPhase={ if(it==PlaybackPhase.RESTORING) error("observer failed") },dryRunCompleted=true,warningsConfirmed=true)
            fail("observer error should remain visible")
        } catch (_: IllegalStateException) { }
        assertEquals(1,restored)
    }
}
