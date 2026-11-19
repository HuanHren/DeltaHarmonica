package com.huanhren.deltaharmonica.midi

import org.junit.Assert.*
import org.junit.Test

class MidiTypeOneTest {
    @Test fun overlappingSameChannelAndPitchKeepTheirOwnTrackDurations() {
        val longNote = intArrayOf(0, 0x90, 60, 100, 96, 0x80, 60, 0, 0, 0xff, 0x2f, 0)
        val shortNote = intArrayOf(24, 0x90, 60, 80, 24, 0x90, 60, 0, 0, 0xff, 0x2f, 0)
        val parsed = MidiParser.parse(typeOne(96, longNote, shortNote))
        assertEquals(MidiNote(0L, 500L, 60, 100, 0), parsed.tracks[0].notes.single())
        assertEquals(MidiNote(125L, 125L, 60, 80, 1), parsed.tracks[1].notes.single())
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test fun fractionalTempoSegmentsDoNotLoseTicksInNotesOrExportedMetadata() {
        val tempos = (List(6) { listOf(1, 0xff, 0x51, 3, 7, 0xa1, 0x20) }.flatten() +
            listOf(0, 0xff, 0x2f, 0)).toIntArray()
        val notes = intArrayOf(0, 0x90, 60, 100, 6, 0x80, 60, 0, 0, 0xff, 0x2f, 0)
        val parsed = MidiParser.parse(typeOne(3, tempos, notes))
        assertEquals(1000L, parsed.tracks[1].notes.single().durationMs)
        assertEquals(1000L, parsed.durationMs)
        val profile = com.huanhren.deltaharmonica.model.DefaultProfiles.delta()
        val result = ScriptConverter.convert(parsed, "tempo.mid", 1, MonophonyPolicy.HIGHEST_NOTE, 0, profile, null)
        assertEquals(1000L, result.script.tempos.last().startTimeMs)
        assertEquals(1000L, result.script.events.single().durationMs)
    }

    private fun typeOne(ppq: Int, vararg tracks: IntArray): ByteArray {
        val header = listOf(77, 84, 104, 100, 0, 0, 0, 6, 0, 1, 0, tracks.size, ppq shr 8, ppq and 255)
        val chunks = tracks.flatMap { track ->
            listOf(77, 84, 114, 107, 0, 0, track.size shr 8, track.size and 255) + track.toList()
        }
        return (header + chunks).map(Int::toByte).toByteArray()
    }

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

