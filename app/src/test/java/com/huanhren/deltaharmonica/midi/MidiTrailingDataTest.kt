package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test

class MidiTrailingDataTest {
    @Test fun completeTrackWithTrailingBytesIsParsedWithExplicitWarning() {
        val valid = listOf(77,84,104,100,0,0,0,6,0,0,0,1,0,96,77,84,114,107,0,0,0,19,
            0,255,81,3,7,161,32,0,144,60,100,96,144,60,0,0,255,47,0)
        val result = MidiParser.parse((valid + listOf(144,60,100)).map { it.toByte() }.toByteArray())
        assertEquals(1, result.tracks.single().notes.size)
        assertTrue(result.warnings.any { it.contains("3") && it.contains("轨道外") })
    }
    @Test fun trailingBytesWithoutTrackEndAreRejected() {
        val data = listOf(77,84,104,100,0,0,0,6,0,0,0,1,0,96,77,84,114,107,0,0,0,4,0,144,60,100,99)
        assertThrows(IllegalArgumentException::class.java) { MidiParser.parse(data.map { it.toByte() }.toByteArray()) }
    }
}
