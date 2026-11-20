package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.model.InstrumentProfile
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.TargetType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

enum class ScoreFormat { MIDI, RMID, ZIP_MIDI, JSON, DFHS1, MUSIC_XML, UNKNOWN }
enum class SongCompatibility { FULLY_COMPATIBLE, COMPATIBLE_WITH_ADAPTATION, PARTIALLY_COMPATIBLE, INCOMPATIBLE }

data class SongCompatibilityReport(
    val status: SongCompatibility,
    val format: ScoreFormat,
    val formatVersion: Int? = null,
    val sourceNoteCount: Int? = null,
    val playableNoteCount: Int = 0,
    val unmappedCount: Int = 0,
    val sourcePitchRange: IntRange? = null,
    val pitchRange: IntRange? = null,
    val noteDurationRangeMs: LongRange? = null,
    val durationMs: Long = 0,
    val tempoCount: Int = 0,
    val timeSignatureCount: Int = 0,
    val trackCount: Int? = null,
    val hasOverlaps: Boolean? = null,
    val hasChords: Boolean? = null,
    val monophonicCount: Int? = null,
    val discardedOverlapMs: Long = 0,
    val mappedRegions: Set<Region> = emptySet(),
    val semitoneNotes: Int = 0,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val parsedNoteCount: Int? = null,
    val retainedSourceNoteCount: Int? = null,
    val overlapClippedNoteCount: Int? = null,
    val overlapDroppedNoteCount: Int? = null,
)

internal data class MidiScoreSource(val format: ScoreFormat, val midi: ParsedMidi)

object SongCompatibilityAnalyzer {
    private const val MAX_MIDI_BYTES = 2_000_000
    private const val MAX_ZIP_ENTRIES = 128

    fun analyze(
        script: PerformanceScript,
        format: ScoreFormat = ScoreFormat.UNKNOWN,
        layout: InstrumentLayout? = null,
    ): SongCompatibilityReport {
        val provenance = script.provenance
        val events = script.events
        val errors = buildList {
            try {
                ScriptCodec.validate(script)
            } catch (e: IllegalArgumentException) {
                add("脚本数据无效")
            }
            if (events.isEmpty()) add("脚本没有可播放事件")
            if (script.schemaVersion == 1 || provenance == null) add("无法验证旧脚本是否存在未映射音符")
            if (layout != null && events.isNotEmpty() && PlaybackMapping.invalidEvent(script, layout) != null)
                add("乐器配置或键位映射不匹配")
        }
        val status = when {
            errors.isNotEmpty() -> SongCompatibility.INCOMPATIBLE
            (provenance?.unmappedCount ?: 0) > 0 || provenance?.importStatus == "RECOVERED" ->
                SongCompatibility.PARTIALLY_COMPATIBLE
            script.transpose != 0 || (provenance?.discardedMs ?: 0) > 0 || provenance?.importStatus == "WARNING" ->
                SongCompatibility.COMPATIBLE_WITH_ADAPTATION
            else -> SongCompatibility.FULLY_COMPATIBLE
        }
        return SongCompatibilityReport(
            status, format, formatVersion = when (format) {
                ScoreFormat.JSON -> script.schemaVersion
                ScoreFormat.DFHS1 -> 1
                else -> null
            },
            sourceNoteCount = provenance?.sourceNoteCount,
            playableNoteCount = events.size,
            unmappedCount = provenance?.unmappedCount ?: 0,
            pitchRange = events.minOfOrNull { it.midiNote }?.let { it..events.maxOf { event -> event.midiNote } },
            noteDurationRangeMs = events.minOfOrNull { it.durationMs }?.let { it..events.maxOf { event -> event.durationMs } },
            durationMs = events.lastOrNull()?.let {
                if (it.startTimeMs >= 0 && it.durationMs > 0 && it.startTimeMs <= Long.MAX_VALUE - it.durationMs)
                    it.startTimeMs + it.durationMs
                else 0
            } ?: 0,
            tempoCount = script.tempos.size,
            timeSignatureCount = script.timeSignatures.size,
            monophonicCount = provenance?.monophonicCount,
            discardedOverlapMs = provenance?.discardedMs ?: 0,
            mappedRegions = events.mapTo(mutableSetOf()) { it.pitchRegion },
            semitoneNotes = events.count { it.semitoneEnabled },
            warnings = provenance?.warnings.orEmpty(),
            errors = errors,
            parsedNoteCount = provenance?.parsedNoteCount,
            retainedSourceNoteCount = provenance?.retainedSourceNoteCount,
            overlapClippedNoteCount = provenance?.overlapClippedNoteCount,
            overlapDroppedNoteCount = provenance?.overlapDroppedNoteCount,
        )
    }

    internal fun includeSource(
        report: SongCompatibilityReport,
        format: ScoreFormat,
        version: Int,
        notes: List<MidiNote>,
        trackCount: Int,
        nonEmptyTrackCount: Int,
    ): SongCompatibilityReport {
        val ordered = if ((1 until notes.size).all { notes[it - 1].startMs <= notes[it].startMs }) notes
            else notes.sortedBy { it.startMs }
        var priorStart = -1L
        var lastEnd = 0L
        var lowest = 128
        var highest = -1
        var overlaps = false
        var chords = false
        for (note in ordered) {
            if (note.startMs < lastEnd) overlaps = true
            if (note.startMs == priorStart) chords = true
            priorStart = note.startMs
            lastEnd = maxOf(lastEnd, note.startMs + note.durationMs)
            lowest = minOf(lowest, note.pitch)
            highest = maxOf(highest, note.pitch)
        }
        return report.copy(
            status = if (nonEmptyTrackCount > 1 && report.status == SongCompatibility.FULLY_COMPATIBLE)
                SongCompatibility.COMPATIBLE_WITH_ADAPTATION else report.status,
            format = format,
            formatVersion = version,
            trackCount = trackCount,
            sourcePitchRange = if (highest >= lowest) lowest..highest else null,
            hasOverlaps = overlaps,
            hasChords = chords,
        )
    }

    internal fun failed(format: ScoreFormat, reason: String) = SongCompatibilityReport(
        SongCompatibility.INCOMPATIBLE, format, errors = listOf(reason),
    )

    internal fun readMidi(bytes: ByteArray, checkActive: () -> Unit = {}): MidiScoreSource {
        val format = when {
            bytes.startsWith("RIFF") -> ScoreFormat.RMID
            bytes.startsWith("PK\u0003\u0004") || bytes.startsWith("PK\u0005\u0006") ||
                bytes.startsWith("PK\u0007\u0008") -> ScoreFormat.ZIP_MIDI
            else -> ScoreFormat.MIDI
        }
        val source = if (format == ScoreFormat.ZIP_MIDI) readSingleMidiZip(bytes, checkActive) else bytes
        return MidiScoreSource(format, MidiParser.parse(source))
    }

    internal fun midiFormat(fileName: String): ScoreFormat = when (fileName.substringAfterLast('.', "").lowercase()) {
        "rmi", "rmid" -> ScoreFormat.RMID
        "zip" -> ScoreFormat.ZIP_MIDI
        else -> ScoreFormat.MIDI
    }

    private fun ByteArray.startsWith(signature: String) =
        size >= signature.length && signature.indices.all { this[it] == signature[it].code.toByte() }

    private fun readSingleMidiZip(bytes: ByteArray, checkActive: () -> Unit): ByteArray {
        if (bytes.size > MAX_MIDI_BYTES) corruptZip("ZIP 文件超过 2 MB")
        var candidate: ByteArray? = null
        var expanded = 0
        var entries = 0
        val buffer = ByteArray(8192)
        try {
            ZipInputStream(bytes.inputStream()).use { archive ->
                while (true) {
                    checkActive()
                    val entry = archive.nextEntry ?: break
                    if (++entries > MAX_ZIP_ENTRIES) corruptZip("ZIP 内文件过多")
                    val isMidi = !entry.isDirectory && entry.name.substringAfterLast('.').lowercase() in
                        setOf("mid", "midi", "rmi", "rmid")
                    if (isMidi && candidate != null)
                        throw MidiImportException(ImportStatus.UNSUPPORTED, "ZIP 含多个 MIDI，请先解压并选择其中一个")
                    val midi = if (isMidi) ByteArrayOutputStream() else null
                    while (true) {
                        checkActive()
                        val count = archive.read(buffer)
                        if (count < 0) break
                        if (count > MAX_MIDI_BYTES - expanded) corruptZip("ZIP 解压内容超过 2 MB")
                        expanded += count
                        midi?.write(buffer, 0, count)
                    }
                    if (midi != null) candidate = midi.toByteArray()
                }
            }
        } catch (e: IOException) {
            throw MidiImportException(ImportStatus.CORRUPTED, "ZIP 数据损坏", e)
        }
        return candidate ?: throw MidiImportException(ImportStatus.UNSUPPORTED, "ZIP 内没有可读取的 MIDI")
    }

    private fun corruptZip(message: String): Nothing = throw MidiImportException(ImportStatus.CORRUPTED, message)
}

/** Mapping checks shared between score assessment and playback planning. */
internal object PlaybackMapping {
    fun noteKeys(layout: InstrumentLayout): Map<String, Int> = layout.targets.asSequence().filter { it.type == TargetType.NOTE }
        .mapNotNull { target -> target.attributes["keyIndex"]?.toIntOrNull()?.let { target.id to it } }.toMap()

    fun isMapped(event: ScriptEvent, profile: InstrumentProfile?, keys: Map<String, Int>): Boolean {
        val pitch = keys[event.noteKeyId]?.let { profile?.pitches?.get(event.pitchRegion)?.getOrNull(it) }
        return pitch != null && pitch + (if (event.semitoneEnabled) 1 else 0) == event.midiNote
    }

    fun invalidEvent(script: PerformanceScript, layout: InstrumentLayout): Int? {
        val profile = layout.profile ?: return 0
        if (!ScriptConverter.matchesProfile(script, profile, layout)) return 0
        val keys = noteKeys(layout)
        return script.events.indexOfFirst { !isMapped(it, profile, keys) }.takeIf { it >= 0 }
    }
}
