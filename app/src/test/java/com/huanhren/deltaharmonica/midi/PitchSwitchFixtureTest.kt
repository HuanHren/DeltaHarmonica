package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.playback.PlaybackPlanner
import org.junit.Assert.*
import org.junit.Test

class PitchSwitchFixtureTest {
    @Test fun durationFixturePreserves120300600MsThroughJsonAndPlanning() {
        val bytes=requireNotNull(javaClass.getResourceAsStream("/duration16-test.mid")).use { it.readBytes() }
        val midi=MidiParser.parse(bytes)
        val expected=listOf(120L,300L,600L,300L,120L,600L,300L,120L).let { it+it }
        assertEquals(expected,midi.tracks.single().notes.map { it.durationMs })
        val layout=DefaultLayouts.create(1).copy(referenceDisplay=ReferenceDisplay(2670,1200))
        val script=ScriptConverter.convert(midi,"duration16-test.mid",0,MonophonyPolicy.HIGHEST_NOTE,0,layout.profile!!,layout).script
        val restored=ScriptCodec.decode(ScriptCodec.encode(script))
        assertEquals(expected,restored.events.map { it.durationMs })
        val plan=PlaybackPlanner.plan(restored,layout,Region.NATURAL,false,0,16,1.0,minimumTouchMs=1)
        assertTrue(plan.fatalErrors.isEmpty())
        assertEquals(expected,plan.events.map { it.predictedEndMs-it.predictedStartMs })
    }
    @Test fun controlledMidiCoversRegionsSemitoneAndDurations() {
        val bytes=requireNotNull(javaClass.getResourceAsStream("/pitch-switch-test.mid")).use { it.readBytes() }
        val midi=MidiParser.parse(bytes)
        assertEquals(listOf(60,62,74,50,51,50,75,76),midi.tracks.single().notes.map { it.pitch })
        assertEquals(listOf(150L,300L,600L,300L,150L,600L,300L,150L),midi.tracks.single().notes.map { it.durationMs })
        val layout=DefaultLayouts.create(1).copy(referenceDisplay=ReferenceDisplay(2670,1200))
        val converted=ScriptConverter.convert(midi,"pitch-switch-test.mid",0,MonophonyPolicy.HIGHEST_NOTE,0,layout.profile!!,layout)
        val roundTrip=ScriptCodec.decode(ScriptCodec.encode(converted.script))
        assertEquals(midi.tracks.single().notes.map { it.startMs to it.durationMs },roundTrip.events.map { it.startTimeMs to it.durationMs })
        val plan=PlaybackPlanner.plan(roundTrip,layout,Region.NATURAL,false,0,8,1.0)
        assertTrue(plan.fatalErrors.isEmpty())
        assertEquals(3,plan.regionSwitches); assertEquals(4,plan.semitoneSwitches)
        assertEquals(converted.script.events.map { it.midiNote },plan.events.map { it.event.midiNote })
        assertEquals(roundTrip.events.map { it.durationMs },plan.events.map { it.predictedEndMs-it.predictedStartMs })
        assertFalse(converted.script.mappingVerified)
    }
}
