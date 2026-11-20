package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.*
import org.junit.Assert.*
import org.junit.Test

class ScriptConverterTest {
    @Test fun fingerprintDetectsChangedPitchConfiguration() {
        val profile = DefaultProfiles.delta()
        val midi = ParsedMidi(0,96,listOf(MidiTrackInfo("test",listOf(MidiNote(0,500,60,100,0)))),listOf(TempoPoint(0,500000)),500, emptyList())
        val script = ScriptConverter.convert(midi,"x.mid",0,MonophonyPolicy.HIGHEST_NOTE,0,profile,null).script
        assertTrue(ScriptConverter.matchesProfile(script,profile))
        val changed = profile.copy(pitches = profile.pitches + (Region.LOW to listOf(49,50,52,53,55,57,59,60)))
        assertFalse(ScriptConverter.matchesProfile(script,changed))
        assertFalse(script.mappingVerified)
        assertEquals(0L, script.tempos.single().startTimeMs)
    }
    @Test fun reportsUnmappedPitchWithoutChangingIt() {
        val midi = ParsedMidi(0,96,listOf(MidiTrackInfo("test",listOf(MidiNote(0,500,10,100,0)))),listOf(TempoPoint(0,500000)),500, emptyList())
        val result = ScriptConverter.convert(midi,"x.mid",0,MonophonyPolicy.LOWEST_NOTE,0,DefaultProfiles.delta(),null)
        assertTrue(result.script.events.isEmpty())
        assertTrue(result.warnings.any { it.contains("10") })
    }

    @Test fun reportsNoteOnParsingAndBothKindsOfOverlapLoss() {
        val midi = MidiParser.parse((
            "4d546864000000060000000100604d54726b00000020" +
                "00903c6400903b6418903e6418803e0000803b0030803c000090416400ff2f00"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val result = ScriptConverter.convert(
            midi, "overlap.mid", 0, MonophonyPolicy.HIGHEST_NOTE, 0, DefaultProfiles.delta(), null,
        )
        val report = result.compatibility

        assertEquals(4, report.sourceNoteCount)
        assertEquals(3, report.parsedNoteCount)
        assertEquals(2, report.retainedSourceNoteCount)
        assertEquals(1, report.overlapClippedNoteCount)
        assertEquals(1, report.overlapDroppedNoteCount)
        assertEquals(3, report.monophonicCount)
        assertEquals(375L, report.discardedOverlapMs)
        assertEquals(listOf(60, 62, 60), result.script.events.map { it.midiNote })
        val restored = ScriptCodec.decode(ScriptCodec.encode(result.script))
        assertEquals(result.script.provenance, restored.provenance)
        assertEquals(3, SongCompatibilityAnalyzer.analyze(restored).parsedNoteCount)
    }

    @Test fun recoveredTrackReportsOnlyCountsThatWereActuallyObserved() {
        val midi = ParsedMidi(
            0, 96, listOf(MidiTrackInfo("recovered", listOf(MidiNote(0, 100, 60, 100, 0)))),
            listOf(TempoPoint(0, 500_000)), 100, emptyList(), importStatus = ImportStatus.RECOVERED,
        )
        val report = ScriptConverter.convert(
            midi, "truncated.mid", 0, MonophonyPolicy.HIGHEST_NOTE, 0, DefaultProfiles.delta(), null,
        ).compatibility

        assertNull(report.sourceNoteCount)
        assertEquals(1, report.parsedNoteCount)
    }

    @Test fun boundsWarningsWithoutLosingTheUnmappedCount() {
        val notes = (0 until 300).map { MidiNote(it * 5L, 5, 10, 100, 0) }
        val midi = ParsedMidi(
            0, 96, listOf(MidiTrackInfo("test", notes)),
            listOf(TempoPoint(0, 500_000)), 1500, emptyList(),
        )
        val result = ScriptConverter.convert(
            midi, "unsupported.mid", 0, MonophonyPolicy.HIGHEST_NOTE, 0, DefaultProfiles.delta(), null,
        )
        assertEquals(300, result.compatibility.unmappedCount)
        assertTrue(result.warnings.size <= 101)
        assertTrue(result.warnings.last().contains("200"))
    }
}
