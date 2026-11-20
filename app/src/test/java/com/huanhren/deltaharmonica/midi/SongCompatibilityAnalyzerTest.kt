package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.Region
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SongCompatibilityAnalyzerTest {
    private val layout = DefaultLayouts.create()
    private val profile = requireNotNull(layout.profile)
    private val mapped = ScriptEvent(0, 100, 60, "NOTE_1", Region.NATURAL, false)
    private val script = PerformanceScript(
        2, profile.id, ScriptConverter.fingerprint(profile, layout), "score.mid", 0,
        MonophonyPolicy.HIGHEST_NOTE, profile.verified, listOf(mapped),
        provenance = ScriptProvenance("CLEAN", emptyList(), 0, 0, 1, 1),
    )

    @Test fun recognizesMappedScriptAndReportsStructure() {
        val report = SongCompatibilityAnalyzer.analyze(script, ScoreFormat.MIDI, layout)
        assertEquals(SongCompatibility.FULLY_COMPATIBLE, report.status)
        assertEquals(1, report.sourceNoteCount)
        assertEquals(1, report.playableNoteCount)
        assertEquals(100L, report.durationMs)
        assertEquals(60..60, report.pitchRange)
    }

    @Test fun distinguishesAdaptationAndPartialCoverage() {
        val provenance = requireNotNull(script.provenance)
        val adapted = script.copy(transpose = 2, provenance = provenance.copy(discardedMs = 20))
        val partial = script.copy(provenance = provenance.copy(unmappedCount = 1, monophonicCount = 2, sourceNoteCount = 2))
        assertEquals(SongCompatibility.COMPATIBLE_WITH_ADAPTATION, SongCompatibilityAnalyzer.analyze(adapted).status)
        val report = SongCompatibilityAnalyzer.analyze(partial)
        assertEquals(SongCompatibility.PARTIALLY_COMPATIBLE, report.status)
        assertEquals(1, report.unmappedCount)
    }

    @Test fun rejectsAStoredScriptWithItsOldLayoutAndLegacyUnknownProvenance() {
        val modifiedLayout = layout.copy(targets = layout.targets.filterNot { it.id == "NOTE_1" })
        assertEquals(SongCompatibility.INCOMPATIBLE, SongCompatibilityAnalyzer.analyze(script, ScoreFormat.JSON, modifiedLayout).status)
        assertEquals(SongCompatibility.INCOMPATIBLE, SongCompatibilityAnalyzer.analyze(script.copy(schemaVersion = 1, provenance = null)).status)
    }

    @Test fun emptyAndStructurallyInvalidScriptsRemainIncompatible() {
        assertEquals(SongCompatibility.INCOMPATIBLE, SongCompatibilityAnalyzer.analyze(script.copy(events = emptyList())).status)
        assertEquals(SongCompatibility.INCOMPATIBLE,
            SongCompatibilityAnalyzer.analyze(script.copy(events = listOf(mapped.copy(durationMs = -10)))).status)
    }

    @Test fun readsSingleMidiFromZipWithoutChangingTheSmfParserContract() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/duration16-test.mid")).use { it.readBytes() }
        val archive = zip("parts/score.MID" to bytes, "readme.txt" to byteArrayOf(65))
        val prepared = SongCompatibilityAnalyzer.readMidi(archive)
        assertEquals(ScoreFormat.ZIP_MIDI, prepared.format)
        assertEquals(MidiParser.parse(bytes), prepared.midi)
        assertThrows(MidiImportException::class.java) { MidiParser.parse(archive) }
    }

    @Test fun rejectsAmbiguousOrOversizedZipPayloads() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/duration16-test.mid")).use { it.readBytes() }
        assertThrows(MidiImportException::class.java) {
            SongCompatibilityAnalyzer.readMidi(zip("a.mid" to bytes, "b.mid" to bytes))
        }
        val large = zip("a.mid" to bytes, "notice.txt" to ByteArray(2_000_000))
        assertThrows(MidiImportException::class.java) { SongCompatibilityAnalyzer.readMidi(large) }
    }

    @Test fun cacheKeysIncludeScoreBytesProfileLayoutAndConversionOptions() {
        val bytes = byteArrayOf(1, 2, 3)
        val digest = ScoreConversionCache.sha256(bytes)
        val base = ScoreConversionCache.key(digest, ScoreFormat.MIDI, 0, MonophonyPolicy.HIGHEST_NOTE, 0, profile, layout)
        assertEquals(base, ScoreConversionCache.key(digest, ScoreFormat.MIDI, 0, MonophonyPolicy.HIGHEST_NOTE, 0, profile, layout))
        assertTrue(base != ScoreConversionCache.key(ScoreConversionCache.sha256(bytes.copyOf(2)), ScoreFormat.MIDI, 0, MonophonyPolicy.HIGHEST_NOTE, 0, profile, layout))
        assertTrue(base != ScoreConversionCache.key(digest, ScoreFormat.MIDI, 1, MonophonyPolicy.HIGHEST_NOTE, 0, profile, layout))
        assertTrue(base != ScoreConversionCache.key(digest, ScoreFormat.MIDI, 0, MonophonyPolicy.LONGEST_NOTE, 0, profile, layout))
        assertTrue(base != ScoreConversionCache.key(digest, ScoreFormat.MIDI, 0, MonophonyPolicy.HIGHEST_NOTE, 1, profile, layout))
        assertTrue(base != ScoreConversionCache.key(digest, ScoreFormat.MIDI, 0, MonophonyPolicy.HIGHEST_NOTE, 0, profile.copy(verified = !profile.verified), layout))
        assertTrue(base != ScoreConversionCache.key(digest, ScoreFormat.MIDI, 0, MonophonyPolicy.HIGHEST_NOTE, 0, profile, layout.copy(targets = layout.targets.dropLast(1))))
    }

    @Test fun simultaneousRequestsShareAConversionAndKeepTheRequestedFileName() = runBlocking {
        val digest = ScoreConversionCache.sha256("simultaneous-test-case".encodeToByteArray())
        val key = ScoreConversionCache.key(digest, ScoreFormat.DFHS1, 0,
            MonophonyPolicy.HIGHEST_NOTE, 0, profile, layout)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            ScoreConversionCache.getOrConvert(key, "score.mid") {
                entered.complete(Unit)
                release.await()
                ConversionReport(script, emptyList(), 0)
            }
        }
        entered.await()
        val second = async {
            ScoreConversionCache.getOrConvert(key, "alias.mid") { error("Conversion was repeated") }
        }
        yield()
        release.complete(Unit)
        assertEquals(first.await().script.events, second.await().script.events)
        assertEquals("alias.mid", second.await().script.sourceMidiName)
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { archive ->
            files.forEach { (name, content) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(content)
                archive.closeEntry()
            }
        }
        bytes.toByteArray()
    }
}
