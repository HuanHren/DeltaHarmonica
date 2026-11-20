package com.huanhren.deltaharmonica.online

import com.huanhren.deltaharmonica.midi.MidiParser
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.midi.ScriptCodec
import com.huanhren.deltaharmonica.midi.ScriptConverter
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.DefaultProfiles
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class OnlineSongConverterTest {
    @Test fun onlineAndMidiUseTheSameTimingAndMappingRules() {
        val midi = MidiParser.parse(bytes(
            "4d546864000000060000000101e04d54726b0000002c" +
                "00ff510307a12000ff58040302081800903d648360803d0000ff51030f4240" +
                "00904064836080400000ff2f00"
        ))
        val layout = DefaultLayouts.create()
        val profile = requireNotNull(layout.profile)
        val policy = MonophonyPolicy.HIGHEST_NOTE
        val dfhs = score("4446485301e003010200e05de003f02e010003040200e0030200e0033de003e00340")
        val local = ScriptConverter.convert(midi, "piece.mid", 0, policy, -1, profile, layout).script
        val online = OnlineSongConverter.convert(dfhs, "piece.dfhs", policy, 0, profile, layout).script

        assertEquals(local.events, online.events)
        assertEquals(local.tempos.distinct(), online.tempos)
        assertEquals(local.timeSignatures, online.timeSignatures)
        assertEquals(local.profileFingerprint, online.profileFingerprint)
        assertEquals(-1, online.transpose)
        assertEquals(online, ScriptCodec.decode(ScriptCodec.encode(online)))
    }

    @Test fun preservesLargeSourceTransposeAndWarnsAboutUnmappedPitches() {
        val dfhs = score("4446485301e0035f0100e05d01000404000200e0036ce003e0032f")
        val profile = DefaultProfiles.delta()
        val output = OnlineSongConverter.convert(dfhs, "piece.dfhs", MonophonyPolicy.HIGHEST_NOTE, 0, profile, null)
        assertEquals(-48, output.script.transpose)
        assertEquals(listOf(60), output.script.events.map { it.midiNote })
        assertEquals(1, output.script.provenance?.unmappedCount)
        assertEquals(2, output.compatibility.sourceNoteCount)
        assertEquals(2, output.compatibility.parsedNoteCount)
        assertEquals(2, output.compatibility.retainedSourceNoteCount)
        assertEquals(0, output.compatibility.overlapDroppedNoteCount)
        assertEquals(1, output.compatibility.playableNoteCount)
        assertTrue(output.warnings.any { it.contains("已省略") })
        assertEquals(output.script, ScriptCodec.decode(ScriptCodec.encode(output.script)))
    }

    @Test fun rejectsAnEmptyPlayableScript() {
        val dfhs = score("4446485301e003000100e05d01000404000100e00300")
        assertThrows(IllegalArgumentException::class.java) {
            OnlineSongConverter.convert(dfhs, "piece.dfhs", MonophonyPolicy.HIGHEST_NOTE, 0, DefaultProfiles.delta(), null)
        }
    }

    @Test fun converterCapacityIsCheckedSeparatelyFromTheDecoder() {
        val dfhs = score("4446485301e003000100e05d01000404000100e0033c")
        val oversized = dfhs.copy(notes = List(100_001) { dfhs.notes.single() })
        val failure = assertThrows(IllegalArgumentException::class.java) {
            OnlineSongConverter.convert(oversized, "piece.dfhs", MonophonyPolicy.HIGHEST_NOTE, 0, DefaultProfiles.delta(), null)
        }
        assertTrue(failure.message.orEmpty().contains("10 万"))
    }

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun score(hex: String): DfhsScore {
        val compressed = java.io.ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            DeflaterOutputStream(compressed, deflater).use { it.write(bytes(hex)) }
        } finally {
            deflater.end()
        }
        return DfhsDecoder.decode(compressed.toByteArray())
    }
}
