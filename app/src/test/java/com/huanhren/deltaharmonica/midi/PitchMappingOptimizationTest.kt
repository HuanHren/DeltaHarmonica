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
}
