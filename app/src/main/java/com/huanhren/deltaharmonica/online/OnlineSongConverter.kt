package com.huanhren.deltaharmonica.online

import com.huanhren.deltaharmonica.midi.ConversionReport
import com.huanhren.deltaharmonica.midi.MidiNote
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.midi.ScriptConverter
import com.huanhren.deltaharmonica.midi.ScriptTempo
import com.huanhren.deltaharmonica.midi.ScriptTimeSignature
import com.huanhren.deltaharmonica.midi.ScriptTiming
import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.model.InstrumentProfile
import kotlin.math.roundToLong

internal object OnlineSongConverter {
    fun convert(
        score: DfhsScore,
        fileName: String,
        policy: MonophonyPolicy,
        userTranspose: Int,
        profile: InstrumentProfile,
        layout: InstrumentLayout?,
        checkActive: () -> Unit = {},
    ): ConversionReport {
        require(score.notes.isNotEmpty()) { "曲谱没有音符" }
        require(score.notes.size <= 100_000) { "这首曲谱超过 10 万音符，无法转换" }
        require(userTranspose in -24..24) { "演奏移调超出可用范围" }
        val notes = score.notes.mapIndexed { index, note ->
            if (index % 1024 == 0) checkActive()
            MidiNote(note.startMs, note.durationMs, note.pitch, velocity = 90, track = 0)
        }
        val tempos = score.tempos.map {
            val micros = (60_000_000.0 / it.bpm).roundToLong()
            require(micros in 1..Int.MAX_VALUE.toLong()) { "曲谱速度超出演奏器支持范围" }
            ScriptTempo(it.timeMs, micros.toInt())
        }
        val signatures = score.timeSignatures.map { ScriptTimeSignature(it.timeMs, it.numerator, it.denominator) }
        return ScriptConverter.convertTrack(
            notes, fileName, 0, policy, score.transpose + userTranspose, profile, layout,
            ScriptTiming(score.ppq, tempos, signatures), checkActive = checkActive,
        ).also {
            require(it.script.events.isNotEmpty()) { "当前乐器配置无法演奏这首曲谱的音高" }
        }
    }
}
