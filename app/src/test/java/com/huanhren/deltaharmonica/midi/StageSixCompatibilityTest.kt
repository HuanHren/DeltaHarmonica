package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.ReferenceDisplay

class StageSixCompatibilityTest {
    @Test fun continuousFixturesHavePredictableNotesAndNoUnmappedEvents() {
        val layout=DefaultLayouts.create(1).copy(referenceDisplay=ReferenceDisplay(2670,1200))
        for ((seconds,count) in listOf(60 to 120,180 to 360)) {
            val bytes=requireNotNull(javaClass.getResourceAsStream("/stage6-${seconds}s.mid")).use { it.readBytes() }
            val parsed=MidiParser.parse(bytes)
            assertEquals(count,parsed.tracks.single().notes.size)
            assertEquals((seconds*1000-250).toLong(),parsed.durationMs)
            val report=ScriptConverter.convert(parsed,"stage6.mid",0,MonophonyPolicy.HIGHEST_NOTE,0,layout.profile!!,layout)
            assertEquals(count,report.script.events.size)
            assertEquals(0,report.script.provenance!!.unmappedCount)
        }
    }
    private fun midi(ppq: Int, vararg track: Int): ByteArray {
        val header = listOf(77,84,104,100,0,0,0,6,0,0,0,1,ppq shr 8,ppq and 255,
            77,84,114,107,(track.size ushr 24) and 255,(track.size ushr 16) and 255,
            (track.size ushr 8) and 255,track.size and 255)
        return (header + track.toList()).map { it.toByte() }.toByteArray()
    }

    @Test fun defaultTempoAndDifferentPpqKeepTheSameMusicalDuration() {
        for ((ppq, delta) in listOf(96 to listOf(96), 480 to listOf(0x83,0x60))) {
            val source=midi(ppq, *(listOf(0,0x90,60,90) + delta + listOf(0x80,60,0,0,0xff,0x2f,0)).toIntArray())
            val result=MidiParser.parse(source)
            assertEquals(0,result.format)
            assertEquals(ppq,result.ppq)
            assertEquals("轨道 1",result.tracks.single().name)
            assertEquals(500L,result.tracks.single().notes.single().durationMs)
            assertEquals(500000,result.tempos.first().microsecondsPerQuarter)
        }
    }

    @Test fun incompleteNotePairIsReportedAndDoesNotAlterTheSource() {
        val source=midi(96, 0,0x90,60,90, 96,0x80,60,0, 0,0x90,62,90, 0,0xff,0x2f,0)
        val original=source.copyOf()
        val result=MidiParser.parse(source)
        assertArrayEquals(original,source)
        assertEquals(listOf(60),result.tracks.single().notes.map { it.pitch })
        assertTrue(result.requiresConfirmation)
        assertTrue(result.warnings.any { it.contains("Note On") })
    }

    @Test fun repeatedSamePitchRemainsTwoDistinctNotes() {
        val source=midi(96, 0,0x90,60,90, 96,0x80,60,0, 0,0x90,60,90, 96,0x90,60,0, 0,0xff,0x2f,0)
        val notes=MidiParser.parse(source).tracks.single().notes
        assertEquals(listOf(0L,500L),notes.map { it.startMs })
        assertEquals(listOf(500L,500L),notes.map { it.durationMs })
        assertEquals(listOf(60,60),notes.map { it.pitch })
    }
}
