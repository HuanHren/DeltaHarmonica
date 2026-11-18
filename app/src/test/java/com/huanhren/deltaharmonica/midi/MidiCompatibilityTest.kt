package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test

class MidiCompatibilityTest {
    private fun data(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun smf(track: ByteArray, format: Int = 0, division: Int = 96) = data(77,84,104,100,0,0,0,6,0,format,0,1,division shr 8,division and 255,77,84,114,107,0,0,0,track.size) + track
    private val valid get() = smf(data(0,144,60,100,96,128,60,0,0,255,47,0))
    @Test fun detectsContainersAndUnsupportedTimeFormats() {
        fun reason(bytes: ByteArray) = assertThrows(MidiImportException::class.java) { MidiParser.parse(bytes) }.kind
        assertEquals(ImportStatus.UNSUPPORTED, reason(data(0x50,0x4b,3,4)))
        assertEquals(ImportStatus.UNSUPPORTED, reason("<!DOCTYPE html>".toByteArray()))
        assertEquals(ImportStatus.UNSUPPORTED, reason(smf(data(0,255,47,0),2)))
        assertEquals(ImportStatus.UNSUPPORTED, reason(smf(data(0,255,47,0),0,0xe728)))
        assertEquals(ImportStatus.CORRUPTED, reason(byteArrayOf()))
    }
    @Test fun rmidChunkPaddingAndDataAreHonored() {
        val midi = valid
        val junk = data(74,85,78,75,1,0,0,0,42,0)
        val chunk = data(100,97,116,97,midi.size,0,0,0) + midi + (if (midi.size % 2 == 1) data(0) else byteArrayOf())
        val body = data(82,77,73,68) + junk + chunk
        val riff = data(82,73,70,70,body.size,0,0,0) + body
        assertEquals(MidiParser.parse(valid).tracks, MidiParser.parse(riff).tracks)
    }
    @Test fun runningStatusUnknownMetaAndSysexPreserveNotes() {
        val track = data(0,255,0x70,2,1,2, 0,240,2,1,247, 0,144,60,100, 96,60,0, 0,255,47,0)
        val midi = MidiParser.parse(smf(track))
        assertEquals(1,midi.tracks.single().notes.size)
        assertEquals(500L,midi.tracks.single().notes.single().durationMs)
    }
    @Test fun trailingDataRequiresConsentExceptZeroPadding() {
        val recovered = MidiParser.parse(valid + data(144,60,100))
        assertTrue(recovered.requiresConfirmation)
        assertEquals(ImportStatus.WARNING,recovered.importStatus)
        val padding = MidiParser.parse(valid + data(0,0))
        assertFalse(padding.requiresConfirmation)
    }
    @Test fun lastTrackTruncationRecoversOnlyCompletedEvents() {
        val full = smf(data(0,144,60,100,96,128,60,0,0,144,62,100,96,128,62,0,0,255,47,0))
        val result = MidiParser.parse(full.copyOf(full.size - 8))
        assertEquals(ImportStatus.RECOVERED,result.importStatus)
        assertTrue(result.requiresConfirmation)
        assertEquals(listOf(60), result.tracks.single().notes.map { it.pitch })
        assertTrue(result.warnings.any { it.contains("轨道 1") && it.contains("偏移") })
        assertEquals(0, result.diagnostics.single().trackIndex)
        assertNotNull(result.diagnostics.single().offset)
        assertNull(result.diagnostics.single().possibleLostNotes)
    }
}
