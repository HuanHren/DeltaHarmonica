package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.*
import org.junit.Assert.*
import org.junit.Test

class PitchMappingOptimizationTest {
    @Test fun looksAheadToAvoidUnnecessaryIntermediateRegion() {
        val choices=PitchMapper.optimize(DefaultProfiles.delta(),listOf(59,59,72,74))
        assertEquals(listOf(Region.LOW,Region.LOW,Region.HIGH,Region.HIGH),choices.map { it?.region })
        assertEquals(1,choices.zipWithNext().count { (a,b) -> a?.region != b?.region })
        assertEquals(choices,PitchMapper.optimize(DefaultProfiles.delta(),listOf(59,59,72,74)))
    }
    @Test fun initialStateAndAvailableKeysAreRespected() {
        val profile=DefaultProfiles.delta()
        val choices=PitchMapper.optimize(profile,listOf(72,73),Region.HIGH,false,setOf(0))
        assertEquals(listOf(Region.HIGH,Region.HIGH),choices.map { it?.region })
        assertEquals(listOf(false,true),choices.map { it?.semitone })
        assertNull(PitchMapper.optimize(profile,listOf(20)).single())
    }

    @Test fun movesRegionChangeIntoAvailableTimeBeforeDenseNotes() {
        val profile = InstrumentProfile(
            "test", "Test", mapOf(Region.NATURAL to listOf(60, 64), Region.HIGH to listOf(60, 62)),
        )
        val pitches = listOf(60, 20, 62)
        val choices = PitchMapper.optimize(
            profile, pitches, Region.NATURAL, false,
            transitionDelayMs = { previousIndex, index, previous, next ->
                if (index == 2 && previousIndex == 0 && previous?.region != next.region) 50L else 0L
            },
        )

        assertEquals(listOf(Region.NATURAL, null, Region.HIGH),
            PitchMapper.optimize(profile, pitches, Region.NATURAL, false).map { it?.region })
        assertEquals(listOf(Region.HIGH, null, Region.HIGH), choices.map { it?.region })
    }

    @Test fun considersDifferentKeysForTheSamePitchAndStateWhenRetriggerTimeMatters() {
        val profile = InstrumentProfile("test", "Test", mapOf(Region.NATURAL to listOf(60, 60)))
        val choices = PitchMapper.optimize(
            profile, listOf(60, 60), Region.NATURAL, false,
            transitionDelayMs = { _, _, previous, next -> if (previous?.keyIndex == next.keyIndex) 15L else 0L },
        )

        assertEquals(setOf(0, 1), choices.map { it?.keyIndex }.toSet())
        assertTrue(choices.all { it?.region == Region.NATURAL && !it.semitone })
    }
}
