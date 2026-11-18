package com.huanhren.deltaharmonica.ui

import com.huanhren.deltaharmonica.data.*
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The ViewModel's import path, with document source and activation boundaries. */
class ImportPipeline(
    private val songs: SongLibraryRepository,
    private val midiWorkspace: MidiWorkspace,
    val imports: ImportCoordinator,
    private val activate: suspend (String) -> Unit = { loadLibrarySong(songs, it, midiWorkspace) },
    private val convert: (ParsedMidi, String, Int, MonophonyPolicy, Int, InstrumentProfile, InstrumentLayout?) -> ConversionReport = ScriptConverter::convert,
) {
    fun importDocument(read: suspend () -> Pair<String, ByteArray>): Boolean = imports.start {
        update(ImportPhase.READING, "正在读取文件…")
        songs.refresh()
        val (fileName, bytes) = read()
        checkActive()
        update(ImportPhase.PREFLIGHT, "正在检查文件…")
        val isJson = withContext(Dispatchers.Default) { bytes.toString(Charsets.UTF_8).trimStart().startsWith("{") }
        if (isJson) {
            update(ImportPhase.PARSING, "正在解析 JSON…")
            val script = withContext(Dispatchers.Default) { ScriptCodec.decodeImport(bytes.toString(Charsets.UTF_8)) }
            checkActive()
            update(ImportPhase.SAVING, "正在保存曲目…")
            val item = songs.add(fileName, SongSourceType.JSON, bytes, script, script.provenance?.warnings.orEmpty())
            update(ImportPhase.COMMITTED, "曲目已保存，正在加载…", item.id, true)
            checkActive()
            activate(item.id)
            midiWorkspace.candidateToken?.let { songs.discardCandidate(it) }
            midiWorkspace.candidateToken = null
            update(ImportPhase.COMMITTED, "已保存到曲库，请检查风险并开始预检", item.id, true)
        } else {
            update(ImportPhase.PARSING, "正在解析 MIDI…")
            val midi = withContext(Dispatchers.Default) { MidiParser.parse(bytes) }
            checkActive()
            val token = songs.stageMidi(bytes)
            try {
                checkActive()
                midiWorkspace.candidateToken?.let { songs.discardCandidate(it) }
                midiWorkspace.candidateToken = token; midiWorkspace.candidateName = fileName
                midiWorkspace.candidatePolicy = MonophonyPolicy.HIGHEST_NOTE; midiWorkspace.candidateTranspose = 0
                midiWorkspace.parsed = midi
                midiWorkspace.candidateTrack = midi.tracks.indexOfFirst { it.notes.isNotEmpty() }.coerceAtLeast(0)
            } catch (e: Throwable) {
                withContext(NonCancellable) { songs.discardCandidate(token) }
                throw e
            }
            update(ImportPhase.READY, if (midi.importStatus == ImportStatus.RECOVERED) "已恢复完整事件，请检查并确认可能丢失的音符" else "MIDI 已读取，请选择轨道并转换")
        }
    }

    fun convertMidi(layout: InstrumentLayout?): Boolean = imports.start {
        val midi = requireNotNull(midiWorkspace.parsed) { "请重新选择 MIDI 文件" }
        val token = requireNotNull(midiWorkspace.candidateToken) { "导入候选文件不存在" }
        val name = midiWorkspace.candidateName
        val track = midiWorkspace.candidateTrack; val policy = midiWorkspace.candidatePolicy; val transpose = midiWorkspace.candidateTranspose
        try {
            update(ImportPhase.CONVERTING, "正在转换 MIDI…")
            val converted = withContext(Dispatchers.Default) { convert(midi, name, track,
                policy, transpose, layout?.profile ?: DefaultProfiles.delta(), layout) }
            checkActive()
            update(ImportPhase.SAVING, "正在保存曲目…")
            val item = songs.addStagedMidi(name, token, converted.script, converted.warnings)
            midiWorkspace.candidateToken = null
            update(ImportPhase.COMMITTED, "曲目已保存，正在加载…", item.id, true)
            checkActive()
            activate(item.id)
            update(ImportPhase.COMMITTED, "转换完成并保存到曲库，请检查警告", item.id, true)
        } finally {
            withContext(NonCancellable) { if (midiWorkspace.candidateToken == token) { songs.discardCandidate(token); midiWorkspace.candidateToken = null; midiWorkspace.parsed = null } }
        }
    }
}
