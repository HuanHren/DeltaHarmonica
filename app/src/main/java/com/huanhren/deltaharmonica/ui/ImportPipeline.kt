package com.huanhren.deltaharmonica.ui

import com.huanhren.deltaharmonica.data.*
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.online.OnlineSongConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The ViewModel's import path, with document source and activation boundaries. */
class ImportPipeline(
    private val songs: SongLibraryRepository,
    private val midiWorkspace: MidiWorkspace,
    val imports: ImportCoordinator,
    private val activate: suspend (String) -> Unit = { loadLibrarySong(songs, it, midiWorkspace) },
    private val convert: ((ParsedMidi, String, Int, MonophonyPolicy, Int, InstrumentProfile, InstrumentLayout?) -> ConversionReport)? = null,
    private val selectedLayout: () -> InstrumentLayout? = { null },
    private val recordTiming: (String, Long) -> Unit = { _, _ -> },
) {
    fun importDocument(read: suspend () -> Pair<String, ByteArray>): Boolean = imports.start {
        update(ImportPhase.READING, "正在读取文件…")
        songs.refresh()
        val readStarted = System.nanoTime()
        val (fileName, bytes) = read()
        recordTiming("document_read", readStarted)
        checkActive()
        update(ImportPhase.PREFLIGHT, "正在检查文件…")
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in setOf("xml", "musicxml", "mxl"))
            throw MidiImportException(ImportStatus.UNSUPPORTED, "当前版本尚未支持 MusicXML")
        val isDfhs = extension == "dfhs"
        val isJson = !isDfhs && (extension == "json" || withContext(Dispatchers.Default) {
            bytes.firstOrNull { it !in JSON_WHITESPACE } == '{'.code.toByte()
        })
        if (isJson || isDfhs) {
            val layout = selectedLayout()
            val sourceType = if (isJson) SongSourceType.JSON else SongSourceType.LOCAL_DFHS
            if (isJson) update(ImportPhase.PARSING, "正在解析 JSON…")
            else update(ImportPhase.CONVERTING, "正在转换 DFHS1…")
            val parseStarted = System.nanoTime()
            val report = withContext(Dispatchers.Default) {
                if (!isJson) OnlineSongConverter.convertCompressed(bytes, fileName, MonophonyPolicy.HIGHEST_NOTE, 0,
                    layout?.profile ?: DefaultProfiles.delta(), layout) { ensureActive() }
                else {
                    val script = ScriptCodec.decodeImport(bytes.toString(Charsets.UTF_8))
                    val compatibility = SongCompatibilityAnalyzer.analyze(script, ScoreFormat.JSON, layout)
                    ConversionReport(script, compatibility.warnings, 0, compatibility)
                }
            }
            recordTiming(if (isJson) "json_decode" else "dfhs_decode_convert", parseStarted)
            checkActive()
            update(ImportPhase.SAVING, "正在保存曲目…")
            val item = songs.add(fileName, sourceType, bytes, report.script, report.warnings)
            update(ImportPhase.COMMITTED, "曲目已保存，正在加载…", item.id, true)
            checkActive()
            activate(item.id)
            keepSourceFacts(item.id, report)
            midiWorkspace.candidateToken?.let { songs.discardCandidate(it) }
            midiWorkspace.candidateToken = null
            midiWorkspace.candidateHash = null
            update(ImportPhase.COMMITTED, if (isJson) "已保存到曲库，请检查风险并开始预检"
                else "转换完成并保存到曲库，请检查警告", item.id, true)
        } else {
            update(ImportPhase.PARSING, "正在解析 MIDI…")
            val parseStarted = System.nanoTime()
            val staged = songs.stageMidiSource(bytes)
            recordTiming("midi_parse_stage", parseStarted)
            val token = staged.token
            val midi = staged.source.midi
            try {
                checkActive()
                midiWorkspace.candidateToken?.let { songs.discardCandidate(it) }
                midiWorkspace.candidateToken = token; midiWorkspace.candidateName = fileName
                midiWorkspace.candidateFormat = staged.source.format
                midiWorkspace.candidateHash = staged.contentHash
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
        val format = midiWorkspace.candidateFormat
        val hash = midiWorkspace.candidateHash
        val track = midiWorkspace.candidateTrack; val policy = midiWorkspace.candidatePolicy; val transpose = midiWorkspace.candidateTranspose
        try {
            update(ImportPhase.CONVERTING, "正在转换 MIDI…")
            val convertStarted = System.nanoTime()
            val converted = withContext(Dispatchers.Default) {
                val profile = layout?.profile ?: DefaultProfiles.delta()
                convert?.invoke(midi, name, track, policy, transpose, profile, layout) ?: run {
                    val convertMidi = { ScriptConverter.convertMidi(midi, name, track, policy, transpose,
                        profile, layout, format) { ensureActive() } }
                    if (hash == null) convertMidi()
                    else ScoreConversionCache.getOrConvert(
                        ScoreConversionCache.key(hash, format, track, policy, transpose, profile, layout), name,
                    ) { convertMidi() }
                }
            }
            recordTiming("midi_convert", convertStarted)
            checkActive()
            update(ImportPhase.SAVING, "正在保存曲目…")
            val item = songs.addStagedMidi(name, token, converted.script, converted.warnings, hash)
            midiWorkspace.candidateToken = null
            midiWorkspace.candidateHash = null
            update(ImportPhase.COMMITTED, "曲目已保存，正在加载…", item.id, true)
            checkActive()
            activate(item.id)
            keepSourceFacts(item.id, converted)
            update(ImportPhase.COMMITTED, "转换完成并保存到曲库，请检查警告", item.id, true)
        } finally {
            withContext(NonCancellable) {
                if (midiWorkspace.candidateToken == token) {
                    songs.discardCandidate(token)
                    midiWorkspace.candidateToken = null
                    midiWorkspace.candidateHash = null
                    midiWorkspace.parsed = null
                }
            }
        }
    }

    private fun keepSourceFacts(songId: String, conversion: ConversionReport) {
        if (midiWorkspace.songId != songId) return
        val activated = midiWorkspace.report ?: return
        val source = conversion.compatibility
        val verified = activated.compatibility
        val status = if (verified.status == SongCompatibility.FULLY_COMPATIBLE &&
            source.status == SongCompatibility.COMPATIBLE_WITH_ADAPTATION) source.status else verified.status
        midiWorkspace.report = activated.copy(compatibility = verified.copy(
            status = status, format = source.format, formatVersion = source.formatVersion, trackCount = source.trackCount,
            sourcePitchRange = source.sourcePitchRange, hasOverlaps = source.hasOverlaps, hasChords = source.hasChords,
        ))
        if (source.format == ScoreFormat.ZIP_MIDI) midiWorkspace.sourceFormat = "ZIP MIDI"
        else midiWorkspace.sourceFormat = source.format.name
    }

    private companion object {
        val JSON_WHITESPACE = byteArrayOf(9, 10, 13, 32)
    }
}
