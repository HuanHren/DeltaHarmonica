package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.*
import org.junit.Assert.*
import org.junit.Test

class ScriptConverterTest {
    @Test fun fingerprintDetectsChangedPitchConfiguration() {
        val profile = DefaultProfiles.delta()
        val midi = ParsedMidi(0,96,listOf(MidiTrackInfo("test",listOf(MidiNote(0,500,60,100,0)))),listOf(TempoPoint(0,500000)),500, emptyList())
        val script = ScriptConverter.convert(midi,"x.mid",0,MonophonyPolicy.HIGHEST_NOTE,0,profile,null).script
        assertTrue(ScriptConverter.matchesProfile(script,profile))
        val changed = profile.copy(pitches = profile.pitches + (Region.LOW to listOf(49,50,52,53,55,57,59,60)))
        assertFalse(ScriptConverter.matchesProfile(script,changed))
        assertFalse(script.mappingVerified)
        assertEquals(0L, script.tempos.single().startTimeMs)
    }
    @Test fun reportsUnmappedPitchWithoutChangingIt() {
        val midi = ParsedMidi(0,96,listOf(MidiTrackInfo("test",listOf(MidiNote(0,500,10,100,0)))),listOf(TempoPoint(0,500000)),500, emptyList())
        val result = ScriptConverter.convert(midi,"x.mid",0,MonophonyPolicy.LOWEST_NOTE,0,DefaultProfiles.delta(),null)
        assertTrue(result.script.events.isEmpty())
        assertTrue(result.warnings.any { it.contains("10") })
    }
}
