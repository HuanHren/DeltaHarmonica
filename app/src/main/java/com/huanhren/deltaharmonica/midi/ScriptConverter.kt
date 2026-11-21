package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.*
import java.security.MessageDigest

data class ConversionReport(
    val script: PerformanceScript,
    val warnings: List<String>,
    val stateChanges: Int,
    val compatibility: SongCompatibilityReport = SongCompatibilityAnalyzer.analyze(script),
)

internal data class ScriptTiming(
    val ppq: Int,
    val tempos: List<ScriptTempo>,
    val timeSignatures: List<ScriptTimeSignature>,
)

object ScriptConverter {
    private const val MAX_UNMAPPED_DETAILS = 100

    fun fingerprint(profile: InstrumentProfile, layout: InstrumentLayout? = null): String {
        val canonical = Region.entries.joinToString(";") { r -> "${r.name}:${profile.pitches[r]?.joinToString(",") ?: ""}" }
        val bytes = MessageDigest.getInstance("SHA-256").digest((profile.id + "|" + canonical + "|" + (layout?.targets?.filter { it.type == TargetType.NOTE }?.sortedBy { it.id }?.joinToString(";") { "${it.id}:${it.attributes["keyIndex"]}" } ?: "default")).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun matchesProfile(script: PerformanceScript, profile: InstrumentProfile, layout: InstrumentLayout? = null) =
        script.instrumentProfileId == profile.id && script.profileFingerprint == fingerprint(profile, layout)
    fun convert(midi: ParsedMidi, fileName: String, track: Int, policy: MonophonyPolicy, transpose: Int,
                profile: InstrumentProfile, layout: InstrumentLayout?): ConversionReport =
        convertMidi(midi, fileName, track, policy, transpose, profile, layout, SongCompatibilityAnalyzer.midiFormat(fileName))

    internal fun convertMidi(
        midi: ParsedMidi,
        fileName: String,
        track: Int,
        policy: MonophonyPolicy,
        transpose: Int,
        profile: InstrumentProfile,
        layout: InstrumentLayout?,
        format: ScoreFormat,
        checkActive: () -> Unit = {},
    ): ConversionReport {
        require(track in midi.tracks.indices && transpose in -24..24)
        val clock = MidiTempoClock(midi.ppq, midi.tempos)
        val timing = ScriptTiming(
            midi.ppq,
            midi.tempos.map { ScriptTempo(clock.millisecondsAt(it.tick), it.microsecondsPerQuarter) },
            midi.timeSignatures.map { ScriptTimeSignature(clock.millisecondsAt(it.tick), it.numerator, it.denominator) },
        )
        val selectedTrack = midi.tracks[track]
        val notes = selectedTrack.notes
        val countKnown = midi.diagnostics.none { it.type == "TRUNCATED_TRACK" && it.trackIndex == track } &&
            (midi.importStatus != ImportStatus.RECOVERED || midi.diagnostics.isNotEmpty())
        val result = convertTrack(
            notes, fileName, track, policy, transpose, profile, layout, timing, midi.importStatus,
            midi.warnings, checkActive, selectedTrack.noteOnCount.takeIf { countKnown },
        )
        return result.copy(compatibility = SongCompatibilityAnalyzer.includeSource(
            result.compatibility, format, midi.format, notes, midi.tracks.size, midi.tracks.count { it.notes.isNotEmpty() },
        ))
    }

    internal fun convertTrack(
        notes: List<MidiNote>,
        fileName: String,
        track: Int,
        policy: MonophonyPolicy,
        transpose: Int,
        profile: InstrumentProfile,
        layout: InstrumentLayout?,
        timing: ScriptTiming,
        importStatus: ImportStatus = ImportStatus.CLEAN,
        sourceWarnings: List<String> = emptyList(),
        checkActive: () -> Unit = {},
        sourceNoteCount: Int? = notes.size,
    ): ConversionReport {
        val mono = Monophony.convert(notes, policy, checkActive)
        checkActive()
        val events = mutableListOf<ScriptEvent>(); val warnings = sourceWarnings.toMutableList()
        if (mono.discardedMs > 0) warnings.add("单音化丢弃了 ${mono.discardedMs} 毫秒的重叠音符时长；保留片段不延长。")
        var region: Region? = null; var semitone: Boolean? = null; var changes = 0; var unmapped = 0
        val targetsByKey = layout?.targets?.filter { it.type == TargetType.NOTE }
            ?.mapNotNull { target -> target.attributes["keyIndex"]?.toIntOrNull()
                ?.takeIf { it.toString() == target.attributes["keyIndex"] }?.let { it to target.id } }
            ?.distinctBy { it.first }?.toMap()
        val choices = PitchMapper.optimize(profile, mono.notes.map { it.pitch + transpose },
            allowedKeys = targetsByKey?.keys, checkActive = checkActive)
        for ((index,note) in mono.notes.withIndex()) {
            if (index % 1024 == 0) checkActive()
            val pitch = note.pitch + transpose
            val choice = choices[index]
            val target = choice?.let { if (targetsByKey == null) "NOTE_${it.keyIndex + 1}" else targetsByKey[it.keyIndex] }
            if (choice == null || target == null) {
                unmapped++
                if (unmapped <= MAX_UNMAPPED_DETAILS)
                    warnings.add("${note.startMs} ms：音高 $pitch 无法精确映射到配置或布局，已省略")
                continue
            }
            if (region != null && region != choice.region) changes++
            if (semitone != null && semitone != choice.semitone) changes++
            events.add(ScriptEvent(note.startMs,note.durationMs,pitch,target,choice.region,choice.semitone))
            region=choice.region; semitone=choice.semitone
        }
        if (unmapped > MAX_UNMAPPED_DETAILS)
            warnings.add("另有 ${unmapped - MAX_UNMAPPED_DETAILS} 个单音片段无法精确映射，已省略")
        val script = PerformanceScript(schemaVersion=2, instrumentProfileId=profile.id, profileFingerprint=fingerprint(profile,layout), sourceMidiName=fileName,
            sourceTrack=track, monophonyPolicy=policy, mappingVerified=profile.verified, events=events, tempos=timing.tempos,
            transpose=transpose, ppq=timing.ppq, timeSignatures=timing.timeSignatures,
            provenance=ScriptProvenance(
                importStatus.name, warnings.toList(), mono.discardedMs, unmapped, sourceNoteCount, mono.notes.size,
                parsedNoteCount = notes.size,
                retainedSourceNoteCount = mono.retainedSourceNoteCount,
                overlapClippedNoteCount = mono.overlapClippedNoteCount,
                overlapDroppedNoteCount = mono.overlapDroppedNoteCount,
            ))
        ScriptCodec.validate(script)
        return ConversionReport(script,warnings,changes)
    }
}
