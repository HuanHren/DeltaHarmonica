package com.huanhren.deltaharmonica.model

import org.junit.Assert.*
import org.junit.Test

class InstrumentProfileTest {
    @Test fun defaultProfileHasCandidatePitchesAndIndependentState() {
        val profile = DefaultProfiles.delta()
        assertFalse(profile.verified)
        assertEquals(listOf(48,50,52,53,55,57,59,60), profile.pitches.getValue(Region.LOW))
        assertEquals(listOf(60,62,64,65,67,69,71,72), profile.pitches.getValue(Region.NATURAL))
        assertEquals(listOf(72,74,76,77,79,81,83,84), profile.pitches.getValue(Region.HIGH))
        val state = InstrumentState(Region.LOW, true).withRegion(Region.HIGH)
        assertTrue(state.semitoneEnabled)
        assertEquals(73, profile.pitch(state, 0))
        assertEquals(Region.HIGH, state.withSemitone(false).region)
    }
    @Test fun defaultTemplateHasTwelveTypedTargets() {
        val targets = DefaultLayouts.create(1).targets
        assertEquals((1..8).map { "NOTE_$it" } + listOf("REGION_HIGH", "REGION_NATURAL", "REGION_LOW", "SEMITONE_TOGGLE"), targets.map { it.id })
        assertEquals(8, targets.count { it.type == TargetType.NOTE })
        assertEquals(3, targets.count { it.type == TargetType.REGION })
        assertEquals(1, targets.count { it.type == TargetType.TOGGLE })
    }
}
