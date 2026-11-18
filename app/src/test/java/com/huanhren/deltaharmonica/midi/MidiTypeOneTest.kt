package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test

class MidiTypeOneTest {
    @Test fun tempoChangesAcrossTracksAndVelocityZero() {
        val b = listOf(77,84,104,100,0,0,0,6,0,1,0,2,0,96,
            77,84,114,107,0,0,0,18,
            0,255,81,3,7,161,32, 96,255,81,3,15,66,64, 0,255,47,0,
            77,84,114,107,0,0,0,13,
            0,144,60,100, 0x81,0x40,144,60,0, 0,255,47,0).map { it.toByte() }.toByteArray()
        val result = MidiParser.parse(b)
        assertEquals(1, result.format)
        assertEquals(2, result.tracks.size)
        assertEquals(1500L, result.tracks[1].notes.single().durationMs)
        assertEquals(3, result.tempos.size)
    }
}

