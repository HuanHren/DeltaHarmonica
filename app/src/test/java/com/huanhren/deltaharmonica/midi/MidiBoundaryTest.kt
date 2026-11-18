package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test

class MidiBoundaryTest {
    @Test fun rejectsOversizedEmbeddedEventBeforeAllocation() {
        val bytes = listOf(77,84,104,100,0,0,0,6,0,0,0,1,0,96,77,84,114,107,0,0,0,8,
            0,255,1,255,255,255,127,0).map { it.toByte() }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { MidiParser.parse(bytes) }
    }
    @Test fun rejectsOversizedFile() {
        assertThrows(IllegalArgumentException::class.java) { MidiParser.parse(ByteArray(2_000_001)) }
    }
    @Test fun policiesRespectHighestLowestAndLongest() {
        val n = listOf(MidiNote(0,1000,60,100,0), MidiNote(0,500,70,100,0))
        assertEquals(70, Monophony.convert(n,MonophonyPolicy.HIGHEST_NOTE).notes.first().pitch)
        assertEquals(60, Monophony.convert(n,MonophonyPolicy.LOWEST_NOTE).notes.first().pitch)
        assertEquals(60, Monophony.convert(n,MonophonyPolicy.LONGEST_NOTE).notes.first().pitch)
    }
}
