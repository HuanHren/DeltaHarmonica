package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test

class MidiCompatibilityBoundaryTest {
    private fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray()
    private fun file(track: ByteArray, declared: Int = track.size) = b(77,84,104,100,0,0,0,6,0,0,0,1,0,96,77,84,114,107,0,0,0,declared) + track
    @Test fun invalidVlqAndTruncatedDeclaredTrackAreNotSilentlyAccepted() {
        val invalidVlq = file(b(0x81,0x81,0x81,0x81,0x00,0xff,0x2f,0))
        assertEquals(ImportStatus.CORRUPTED, assertThrows(MidiImportException::class.java) { MidiParser.parse(invalidVlq) }.kind)
        val invalidLength = file(b(0,0xff,0x2f,0),1)
        assertEquals(ImportStatus.CORRUPTED, assertThrows(MidiImportException::class.java) { MidiParser.parse(invalidLength) }.kind)
    }
    @Test fun rmidMalformedChunkAndNonMidiAreRejected() {
        val invalid = b(82,73,70,70,14,0,0,0,82,77,73,68,100,97,116,97,20,0,0,0,1,2)
        assertEquals(ImportStatus.CORRUPTED, assertThrows(MidiImportException::class.java) { MidiParser.parse(invalid) }.kind)
        assertEquals(ImportStatus.UNSUPPORTED, assertThrows(MidiImportException::class.java) { MidiParser.parse(b(1,2,3,4)) }.kind)
    }
    @Test fun ordinaryMidiWithZeroPaddingPreservesNotes() {
        val track = b(0,144,60,100,96,128,60,0,0,255,47,0)
        val original = MidiParser.parse(file(track))
        val padded = MidiParser.parse(file(track)+b(0,0,0))
        assertEquals(ImportStatus.CLEAN,original.importStatus)
        assertEquals(original.tracks,padded.tracks)
        assertFalse(padded.requiresConfirmation)
        assertEquals(ImportStatus.WARNING,padded.importStatus)
    }
}
