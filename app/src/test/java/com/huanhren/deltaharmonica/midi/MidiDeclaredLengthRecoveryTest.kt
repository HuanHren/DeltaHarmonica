package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test

class MidiDeclaredLengthRecoveryTest {
    @Test fun finalTrackCompleteButLengthOverstatesFileIsRecoveredWithWarning() {
        val v = listOf(77,84,104,100,0,0,0,6,0,0,0,1,0,96,77,84,114,107,0,0,0,16,
            0,144,60,100,96,128,60,0,0,255,47,0).map { it.toByte() }.toByteArray()
        val result = MidiParser.parse(v)
        assertEquals(ImportStatus.RECOVERED,result.importStatus)
        assertTrue(result.requiresConfirmation)
        assertEquals(60,result.tracks.single().notes.single().pitch)
    }
}
