package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test

class MidiParserTest {
    private fun bytes(vararg b: Int) = b.map { it.toByte() }.toByteArray()
    @Test fun parsesTypeZeroVelocityZeroAndTempo() {
        val data = bytes(77,84,104,100,0,0,0,6,0,0,0,1,0,96, 77,84,114,107,0,0,0,19,
            0,0xff,0x51,3,7,0xa1,0x20, 0,0x90,60,100, 96,0x90,60,0, 0,0xff,0x2f,0)
        val result = MidiParser.parse(data)
        assertEquals(0, result.format); assertEquals(1, result.tracks.size)
        assertEquals(500L, result.tracks[0].notes.single().durationMs)
    }
    @Test fun rejectsEmptyAndTruncated() {
        assertThrows(IllegalArgumentException::class.java) { MidiParser.parse(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { MidiParser.parse(bytes(77,84,104,100)) }
    }
}
