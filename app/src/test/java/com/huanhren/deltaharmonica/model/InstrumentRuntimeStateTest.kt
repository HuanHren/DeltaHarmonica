package com.huanhren.deltaharmonica.model

import com.huanhren.deltaharmonica.input.TapResult
import org.junit.Assert.*
import org.junit.Test

class InstrumentRuntimeStateTest {
    @Test fun unknownToggleCannotBeAssumed() {
        val s = InstrumentRuntimeState()
        assertEquals(SyncStatus.UNKNOWN, s.syncStatus)
        assertEquals(ControlDecision.NEEDS_SYNC, s.planSemitone(true))
        assertEquals(ControlDecision.SEND, s.planRegion(Region.HIGH))
    }
    @Test fun regionAndSemitoneAreIndependentAndTrackedNotConfirmed() {
        val s = InstrumentRuntimeState().confirmRegion(Region.LOW).confirmSemitone(false)
        val next = s.regionResult(Region.HIGH, TapResult.Completed)
        assertEquals(Region.HIGH, next.pitchRegion)
        assertEquals(false, next.semitoneEnabled)
        assertEquals(SyncStatus.APP_TRACKED, next.syncStatus)
        val toggled = next.semitoneResult(true, TapResult.Completed)
        assertEquals(Region.HIGH, toggled.pitchRegion)
        assertEquals(true, toggled.semitoneEnabled)
        assertEquals(ControlDecision.SKIP, toggled.planSemitone(true))
        assertEquals(ControlDecision.SKIP, toggled.planRegion(Region.HIGH))
    }
    @Test fun failedAndCancelledSwitchesInvalidateOnlyAffectedState() {
        val s = InstrumentRuntimeState().confirmRegion(Region.NATURAL).confirmSemitone(true)
        for (result in listOf(TapResult.Cancelled, TapResult.TimedOut, TapResult.Rejected)) {
            val next = s.semitoneResult(false, result)
            assertNull(next.semitoneEnabled); assertEquals(Region.NATURAL, next.pitchRegion)
            assertNotEquals(SyncStatus.USER_CONFIRMED, next.syncStatus)
        }
    }
    @Test fun confirmationsAndResetAreExplicit() {
        val s = InstrumentRuntimeState().confirmRegion(Region.HIGH).confirmSemitone(false)
        assertEquals(SyncStatus.USER_CONFIRMED, s.syncStatus)
        assertEquals(SyncStatus.UNKNOWN, s.reset().syncStatus)
        assertNull(s.reset().pitchRegion)
    }
}
