package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversionTest {
    @Test fun overlapPolicyProducesMonophonicSegments() {
        val notes = listOf(MidiNote(0, 1000, 60, 100, 0), MidiNote(200, 500, 67, 90, 0))
        val result = Monophony.convert(notes, MonophonyPolicy.HIGHEST_NOTE)
        assertEquals(listOf(60,67,60), result.notes.map { it.pitch })
        assertTrue(result.notes.zipWithNext().all { (a,b) -> a.startMs + a.durationMs <= b.startMs })
        assertTrue(result.discardedMs > 0)
    }
    @Test fun mappingRetainsPitchAndUsesPriorState() {
        val profile = DefaultProfiles.delta()
        val result = PitchMapper.map(profile, 72, Region.HIGH, false)!!
        assertEquals(Region.HIGH, result.region)
        assertFalse(result.semitone)
        assertEquals(0, result.keyIndex)
        assertNull(PitchMapper.map(profile, 20, null, null))
    }
    @Test fun scriptRoundTripAndVersionValidation() {
        val script = PerformanceScript(instrumentProfileId = "id", profileFingerprint = "hash", sourceMidiName = "test.mid", sourceTrack = 0,
            monophonyPolicy = MonophonyPolicy.HIGHEST_NOTE, mappingVerified = false,
            events = listOf(ScriptEvent(0, 250, 60, "NOTE_1", Region.NATURAL, false)))
        assertEquals(script, ScriptCodec.decode(ScriptCodec.encode(script)))
        assertThrows(IllegalArgumentException::class.java) { ScriptCodec.decode(ScriptCodec.encode(script).replace("\"schemaVersion\": 1", "\"schemaVersion\": 3")) }
    }
}

