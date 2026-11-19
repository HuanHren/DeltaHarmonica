package com.huanhren.deltaharmonica.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class DfhsDecoderTest {
    @Test
    fun matchesUpstreamTempoMapGolden() {
        val score = DfhsDecoder.decode(DfhsDecoder.decodeBase64(TEMPO_MAP))

        assertEquals(1, score.version)
        assertEquals(384, score.ppq)
        assertEquals(-3, score.transpose)
        assertEquals(
            listOf(DfhsTempo(0, 90.0, 0), DfhsTempo(2304, 150.0, 4000), DfhsTempo(4608, 72.5, 6400)),
            score.tempos
        )
        assertEquals(
            listOf(DfhsTimeSignature(0, 3, 4, 0), DfhsTimeSignature(4608, 6, 8, 6400)),
            score.timeSignatures
        )
        assertEquals(listOf(0L, 1152L, 2304L, 3456L, 4608L, 5760L, 6912L, 8064L, 9216L, 10368L), score.measureStartsTicks)
        assertEquals(
            listOf(67, 69, 71, 72, 74, 76, 78, 79, 81, 76, 72, 69, 67, 69, 71, 72, 74, 76,
                78, 79, 81, 76, 72, 69, 55, 57, 59, 60, 62, 64, 66, 67, 69, 64, 60, 57),
            score.notes.map { it.pitch }
        )
        assertEquals((0L..10080L step 288).toList(), score.notes.map { it.startTick })
        assertEquals(List(9) { listOf(288L, 288L, 288L, 144L) }.flatten(), score.notes.map { it.durationTick })
        assertEquals(
            listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L, 3000L, 3500L, 4000L, 4300L, 4600L,
                4900L, 5200L, 5500L, 5800L, 6100L, 6400L, 7021L, 7641L, 8262L, 8883L, 9503L,
                10124L, 10745L, 11366L, 11986L, 12607L, 13228L, 13848L, 14469L, 15090L, 15710L,
                16331L, 16952L, 17572L, 18193L),
            score.notes.map { it.startMs }
        )
        assertEquals(
            listOf(500L, 500L, 500L, 250L, 500L, 500L, 500L, 250L, 300L, 300L, 300L, 150L, 300L,
                300L, 300L, 150L, 621L, 621L, 621L, 310L, 621L, 621L, 621L, 310L, 621L, 621L,
                621L, 310L, 621L, 621L, 621L, 310L, 621L, 621L, 621L, 310L),
            score.notes.map { it.durationMs }
        )
        assertEquals(26.25, score.notes.last().beat, 0.0)
        assertEquals(0.375, score.notes.last().durationBeats, 0.0)
    }

    @Test
    fun preservesOverlappingAndShortNotesFromUpstreamGolden() {
        val score = DfhsDecoder.decode(DfhsDecoder.decodeBase64(CHORDS))
        assertEquals(listOf(60, 64, 67, 72, 72, 65, 69, 71, 74, 55), score.notes.map { it.pitch })
        assertEquals(listOf(0L, 0L, 19L, 960L, 970L, 1344L, 1728L, 2112L, 2126L, 3840L), score.notes.map { it.startTick })
        assertEquals(listOf(960L, 960L, 960L, 192L, 576L, 864L, 288L, 8L, 384L, 480L), score.notes.map { it.durationTick })
        assertEquals(listOf(0L, 0L, 20L, 1000L, 1010L, 1400L, 1800L, 2200L, 2215L, 4000L), score.notes.map { it.startMs })
        assertEquals(listOf(1000L, 1000L, 1000L, 200L, 600L, 900L, 300L, 8L, 400L, 500L), score.notes.map { it.durationMs })
    }

    @Test
    fun integratesTempoChangesDuringANote() {
        val score = decodeRaw(scoreBytes(tempos = listOf(0L to 12000L, 480L to 6000L), notes = byteArrayOf(0xF0.toByte(), 1, 0xE0.toByte(), 3, 60)))
        assertEquals(DfhsNote(60, 240, 480, 250, 750, 0.5, 1.0), score.notes.single())
    }

    @Test
    fun defaultsTempoAndSignatureAndRoundsSubMillisecondNotesUp() {
        val score = decodeRaw(scoreBytes(ppq = 9600))
        assertEquals(listOf(DfhsTempo(0, 120.0, 0)), score.tempos)
        assertEquals(listOf(DfhsTimeSignature(0, 4, 4, 0)), score.timeSignatures)
        assertEquals(1L, score.notes.single().durationMs)
    }

    @Test
    fun decodesLargePlausibleScoresWithinTheAndroidDecodingLimit() {
        val rawNotes = ByteArray(360_000) { index ->
            when (index % 3) {
                0, 1 -> 1
                else -> 60
            }
        }
        val score = decodeRaw(scoreBytes(noteCount = 120_000, notes = rawNotes))
        assertEquals(120_000, score.notes.size)
        assertEquals(120_000L, score.notes.last().startTick)
        assertEquals(125_000L, score.notes.last().startMs)
    }

    @Test
    fun rejectsNonCanonicalBase64Url() {
        for (invalid in listOf("", "A", "AA=", " AA", "+w", "/w", "AB", "Aā", "AA\n")) {
            assertThrows(invalid, DfhsFormatException::class.java) { DfhsDecoder.decodeBase64(invalid) }
        }
    }

    @Test
    fun rejectsOversizePayloadBeforeDecoding() {
        val bytes = ByteArray(192 * 1024 + 1)
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertThrows(DfhsFormatException::class.java) { DfhsDecoder.decodeBase64(text) }
        assertThrows(DfhsFormatException::class.java) { DfhsDecoder.decode(bytes) }
    }

    @Test
    fun rejectsMalformedAndTruncatedCompressedStreams() {
        val valid = DfhsDecoder.decodeBase64(TEMPO_MAP)
        for (invalid in listOf(byteArrayOf(1, 2, 3), valid.copyOf(valid.size / 2), valid.copyOf(valid.size - 1))) {
            assertThrows(DfhsFormatException::class.java) { DfhsDecoder.decode(invalid) }
        }
    }

    @Test
    fun rejectsTrailingBytesOnBothSidesOfCompression() {
        val raw = scoreBytes()
        assertThrows(DfhsFormatException::class.java) { DfhsDecoder.decode(deflate(raw) + 0) }
        assertThrows(DfhsFormatException::class.java) { decodeRaw(raw + 0) }
    }

    @Test
    fun rejectsExpansionOverEightMiB() {
        val packed = deflate(ByteArray(8 * 1024 * 1024 + 1))
        val error = assertThrows(DfhsFormatException::class.java) { DfhsDecoder.decode(packed) }
        assertEquals(DfhsFormatFailure.TOO_LARGE, error.failure)
    }

    @Test
    fun rejectsMagicVersionAndPpqOutsideTheProtocol() {
        assertThrows(DfhsFormatException::class.java) { decodeRaw(scoreBytes().also { it[0] = 0 }) }
        val error = assertThrows(DfhsFormatException::class.java) { decodeRaw(scoreBytes(version = 2)) }
        assertEquals(DfhsFormatFailure.UNSUPPORTED_VERSION, error.failure)
        for (ppq in listOf(23L, 9601L)) {
            assertThrows(DfhsFormatException::class.java) { decodeRaw(scoreBytes(ppq = ppq)) }
        }
    }

    @Test
    fun rejectsUnterminatedOverflowingAndOverlongVarints() {
        val prefix = scoreBytes().copyOf(5)
        val tooLarge = ByteArray(8) { 0xFF.toByte() }.also { it[7] = 16 }
        val cases = listOf(
            byteArrayOf(0x80.toByte()),
            ByteArray(9) { 0x80.toByte() },
            tooLarge,
            byteArrayOf(0x80.toByte(), 0),
        )
        for (invalid in cases) {
            assertThrows(DfhsFormatException::class.java) { decodeRaw(prefix + invalid) }
        }
    }

    @Test
    fun rejectsInvalidTempoSignatureAndSectionCounts() {
        for (bpm in listOf(0L, 100_001L)) {
            assertThrows(DfhsFormatException::class.java) { decodeRaw(scoreBytes(tempos = listOf(0L to bpm))) }
        }
        assertThrows(DfhsFormatException::class.java) { decodeRaw(scoreBytes(signatureFields = byteArrayOf(1, 0, 4, 0))) }
        val validHeader = scoreBytes().copyOf(8)
        val oversizedSections = listOf(
            listOf(4_097L),
            listOf(0L, 1_025L),
            listOf(0L, 0L, 100_001L),
            listOf(0L, 0L, 0L, 200_001L),
        )
        for (counts in oversizedSections) {
            val fields = ByteArrayOutputStream().apply { counts.forEach { varint(it) } }.toByteArray()
            val error = assertThrows(DfhsFormatException::class.java) { decodeRaw(validHeader + fields) }
            assertEquals(DfhsFormatFailure.TOO_LARGE, error.failure)
        }
    }

    @Test
    fun preservesTicksLargerThanSignedInt() {
        val delta = 2_147_483_648L
        val note = ByteArrayOutputStream().apply {
            varint(delta)
            varint(480)
            write(67)
        }.toByteArray()

        val score = decodeRaw(scoreBytes(notes = note))
        assertEquals(delta, score.notes.single().startTick)
        assertEquals(2_236_962_133L, score.notes.single().startMs)
        assertEquals(500L, score.notes.single().durationMs)
    }

    @Test
    fun rejectsCumulativeTicksBeyondTheExactWireRange() {
        val measures = ByteArrayOutputStream().apply {
            varint(2)
            varint((1L shl 53) - 1)
            varint(1)
        }.toByteArray()

        assertThrows(DfhsFormatException::class.java) { decodeRaw(scoreBytes(measureFields = measures)) }
    }

    @Test
    fun rejectsOutOfRangeTransposeAndKeepsEncoderExtremes() {
        for (zigZag in listOf(97L, 98L)) {
            assertThrows(DfhsFormatException::class.java) { decodeRaw(scoreBytes(zigZag = zigZag)) }
        }
        assertEquals(-48, decodeRaw(scoreBytes(zigZag = 95)).transpose)
        assertEquals(48, decodeRaw(scoreBytes(zigZag = 96)).transpose)
    }

    @Test
    fun rejectsZeroDurationIllegalPitchAndMissingNotes() {
        for (notes in listOf(byteArrayOf(0, 0, 60), byteArrayOf(0, 1, 128.toByte()), byteArrayOf(0, 1))) {
            assertThrows(DfhsFormatException::class.java) { decodeRaw(scoreBytes(notes = notes)) }
        }
        assertThrows(DfhsFormatException::class.java) { decodeRaw(scoreBytes(noteCount = 0, notes = byteArrayOf())) }
    }

    @Test
    fun stopsWhenTheCallerCancels() {
        val packed = DfhsDecoder.decodeBase64(TEMPO_MAP)
        assertThrows(CancellationException::class.java) {
            DfhsDecoder.decode(packed) { throw CancellationException() }
        }
    }

    private fun decodeRaw(bytes: ByteArray): DfhsScore = DfhsDecoder.decode(deflate(bytes))

    private fun scoreBytes(
        version: Int = 1,
        ppq: Long = 480,
        zigZag: Long = 0,
        tempos: List<Pair<Long, Long>> = emptyList(),
        signatureFields: ByteArray = byteArrayOf(0),
        measureFields: ByteArray = byteArrayOf(0),
        noteCount: Long = 1L,
        notes: ByteArray = byteArrayOf(0, 1, 60),
    ): ByteArray = ByteArrayOutputStream().apply {
        write("DFHS".toByteArray(Charsets.US_ASCII))
        write(version)
        varint(ppq)
        varint(zigZag)
        varint(tempos.size.toLong())
        for ((delta, bpm) in tempos) {
            varint(delta)
            varint(bpm)
        }
        write(signatureFields)
        write(measureFields)
        varint(noteCount)
        write(notes)
    }.toByteArray()

    private fun ByteArrayOutputStream.varint(value: Long) {
        var rest = value
        while (rest >= 128) {
            write((rest.toInt() and 127) or 128)
            rest = rest ushr 7
        }
        write(rest.toInt())
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            DeflaterOutputStream(output, deflater).use { it.write(bytes) }
        } finally {
            deflater.end()
        }
        return output.toByteArray()
    }

    companion object {
        // Upstream 87447ee, scripts/desktop-fixtures.ts and desktop/DFH.Core.Tests/FixtureTests.cs.
        private const val TEMPO_MAP = "c3HzCGZsYGZlZljh1iA0o7RB6JIFEwMzS4MKGwcXQwMnOlRhWMDkvIBpAZMriHBfwDSB0QPE8gIRPiDCDyTmD2IFwsWASiYwgnVQotccRFiCCGuQmA2IZQciHECEE0gMYTxYDKhkAqMlAA"
        private const val CHORDS = "c3HzCGZ8wMzAyPAglpGBhYWBi-EAuw0QOwgfYHdey36A0YPrAIvHN6YHbI4NzAuYXBuYOdz5Gpi9NvE-YDYHAA"
    }
}
