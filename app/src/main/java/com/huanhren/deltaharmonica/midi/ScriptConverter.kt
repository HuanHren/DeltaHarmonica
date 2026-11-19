package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.*
import java.security.MessageDigest

data class ConversionReport(val script: PerformanceScript, val warnings: List<String>, val stateChanges: Int)
object ScriptConverter {
    fun fingerprint(profile: InstrumentProfile, layout: InstrumentLayout? = null): String {
        val canonical = Region.entries.joinToString(";") { r -> "${r.name}:${profile.pitches[r]?.joinToString(",") ?: ""}" }
        val bytes = MessageDigest.getInstance("SHA-256").digest((profile.id + "|" + canonical + "|" + (layout?.targets?.filter { it.type == TargetType.NOTE }?.sortedBy { it.id }?.joinToString(";") { "${it.id}:${it.attributes["keyIndex"]}" } ?: "default")).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun matchesProfile(script: PerformanceScript, profile: InstrumentProfile, layout: InstrumentLayout? = null) =
        script.instrumentProfileId == profile.id && script.profileFingerprint == fingerprint(profile, layout)
    fun convert(midi: ParsedMidi, fileName: String, track: Int, policy: MonophonyPolicy, transpose: Int, profile: InstrumentProfile, layout: InstrumentLayout?): ConversionReport {
        require(track in midi.tracks.indices && transpose in -24..24)
        val mono = Monophony.convert(midi.tracks[track].notes, policy)
        val events = mutableListOf<ScriptEvent>(); val warnings = midi.warnings.toMutableList()
        if (mono.discardedMs > 0) warnings.add("单音化丢弃了 ${mono.discardedMs} 毫秒的重叠音符时长；保留片段不延长。")
        var region: Region? = null; var semitone: Boolean? = null; var changes = 0; var unmapped = 0
        val availableKeys=layout?.targets?.filter { it.type == TargetType.NOTE }?.mapNotNull { it.attributes["keyIndex"]?.toIntOrNull() }?.toSet()
        val choices=PitchMapper.optimize(profile,mono.notes.map { it.pitch+transpose },allowedKeys=availableKeys)
        for ((index,note) in mono.notes.withIndex()) {
            val pitch = note.pitch + transpose
            val choice = choices[index]
            val target = choice?.let { c -> layout?.targets?.find { it.type == TargetType.NOTE && it.attributes["keyIndex"] == c.keyIndex.toString() }?.id ?: if (layout == null) "NOTE_${c.keyIndex+1}" else null }
            if (choice == null || target == null) { unmapped++; warnings.add("${note.startMs} ms：音高 $pitch 无法精确映射到配置或布局，已省略"); continue }
            if (region != null && region != choice.region) changes++
            if (semitone != null && semitone != choice.semitone) changes++
            events.add(ScriptEvent(note.startMs,note.durationMs,pitch,target,choice.region,choice.semitone))
            region=choice.region; semitone=choice.semitone
        }
        val clock = MidiTempoClock(midi.ppq, midi.tempos)
        val script = PerformanceScript(schemaVersion=2, instrumentProfileId=profile.id, profileFingerprint=fingerprint(profile,layout), sourceMidiName=fileName,
            sourceTrack=track, monophonyPolicy=policy, mappingVerified=profile.verified, events=events, tempos=midi.tempos.map { ScriptTempo(clock.millisecondsAt(it.tick), it.microsecondsPerQuarter) }, transpose=transpose, ppq=midi.ppq, timeSignatures=midi.timeSignatures.map { ScriptTimeSignature(clock.millisecondsAt(it.tick), it.numerator, it.denominator) }, provenance=ScriptProvenance(midi.importStatus.name, warnings.toList(), mono.discardedMs, unmapped, midi.tracks[track].notes.size, mono.notes.size))
        ScriptCodec.encode(script)
        return ConversionReport(script,warnings,changes)
    }
}
