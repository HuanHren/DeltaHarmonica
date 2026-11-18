package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test

class MidiVolumeRecoveryTest {
    private fun file(vararg events: Int): ByteArray {
        val header = listOf(77,84,104,100,0,0,0,6,0,0,0,1,0,96,77,84,114,107,0,0,0,events.size)
        return (header + events.toList()).map { it.toByte() }.toByteArray()
    }
    @Test fun outOfRangeVolumeIsRecoveredExplicitlyWithoutMutatingInput() {
        val bytes = file(0,0xb0,7,255, 0,0x90,60,100,96,0x80,60,0,0,255,47,0)
        val original = bytes.clone()
        val result = MidiParser.parse(bytes)
        assertArrayEquals(original,bytes)
        assertEquals(ImportStatus.RECOVERED,result.importStatus)
        assertTrue(result.requiresConfirmation)
        assertEquals(60,result.tracks.single().notes.single().pitch)
        assertTrue(result.warnings.any { it.contains("音量") && it.contains("127") })
    }
    @Test fun invalidNoteDataIsNotReinterpreted() {
        val bytes = file(0,0x90,60,255,0,255,47,0)
        assertEquals(ImportStatus.CORRUPTED, assertThrows(MidiImportException::class.java) { MidiParser.parse(bytes) }.kind)
    }
}
