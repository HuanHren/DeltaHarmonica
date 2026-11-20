package com.huanhren.deltaharmonica.midi

import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.read
import kotlinx.serialization.Serializable

@Serializable data class TimeSignaturePoint(val tick: Long, val numerator: Int, val denominator: Int)
@Serializable data class TempoPoint(val tick: Long, val microsecondsPerQuarter: Int)
data class MidiTrackInfo(
    val name: String,
    val notes: List<MidiNote>,
    val noteOnCount: Int = notes.size,
)
data class ParsedMidi(val format: Int, val ppq: Int, val tracks: List<MidiTrackInfo>, val tempos: List<TempoPoint>, val durationMs: Long, val warnings: List<String>, val timeSignatures: List<TimeSignaturePoint> = emptyList(), val importStatus: ImportStatus = ImportStatus.CLEAN, val requiresConfirmation: Boolean = false, val diagnostics: List<ImportDiagnostic> = emptyList())
object MidiParser {
    private const val MAX_BYTES = 2_000_000
    private const val MAX_EVENTS = 200_000
    fun parse(bytes: ByteArray): ParsedMidi {
        if (bytes.size > MAX_BYTES) throw MidiImportException(ImportStatus.CORRUPTED, "文件超过 2 MB，无法安全导入。")
        try {
            val smf = MidiContainer.extract(bytes)
            require(smf.size >= 14) { "MIDI 文件头截断" }
            require(smf.copyOfRange(0,4).contentEquals(byteArrayOf(77,84,104,100))) { "RMID 中的 data 不是标准 MIDI 文件" }
            val format = ((smf[8].toInt() and 255) shl 8) or (smf[9].toInt() and 255)
            if (format == 2) throw MidiImportException(ImportStatus.UNSUPPORTED, "检测到 MIDI Type 2，目前不支持这种序列格式。")
            if (format !in 0..1) throw MidiImportException(ImportStatus.UNSUPPORTED, "不支持的 MIDI 类型：$format。")
            val division = ((smf[12].toInt() and 255) shl 8) or (smf[13].toInt() and 255)
            if (division and 0x8000 != 0) throw MidiImportException(ImportStatus.UNSUPPORTED, "文件使用 SMPTE 时间基准，当前版本无法可靠换算音符时间。")
            require(division > 0) { "MIDI PPQ 时间基准无效" }
            val preflight = SmfPreflight.validate(smf)
            val result = parseValidated(preflight.bytes)
            if (preflight.status == ImportStatus.RECOVERED && result.tracks.sumOf { it.notes.size } == 0)
                throw MidiImportException(ImportStatus.CORRUPTED, "文件截断后没有完整音符可恢复。")
            val combined = preflight.warnings + result.warnings
            val status = if (preflight.status == ImportStatus.CLEAN && combined.isNotEmpty()) ImportStatus.WARNING else preflight.status
            val recoveredTrack = preflight.diagnostics.firstOrNull { it.type == "TRUNCATED_TRACK" }?.trackIndex
            val recoveredNotes = recoveredTrack?.let { "轨道 ${it+1} 已读取 ${result.tracks[it].notes.size} 个完整配对音符；可能丢失的音符数量无法确定。" }
            return result.copy(warnings = combined + listOfNotNull(recoveredNotes), importStatus = status, requiresConfirmation = preflight.requiresConfirmation || result.warnings.isNotEmpty(), diagnostics = preflight.diagnostics)
        } catch (e: MidiImportException) { throw e }
        catch (e: Exception) { throw MidiImportException(ImportStatus.CORRUPTED, "MIDI 文件损坏：${e.localizedMessage ?: "结构无效"}", e) }
    }
    private fun parseValidated(bytes: ByteArray): ParsedMidi {
        val music = Midi1Music().apply { read(bytes.toList()) }
        val format = music.format.toInt(); val ppq = music.deltaTimeSpec
        require(format in 0..1 && ppq in 1..32767 && music.tracks.size in 1..128) { "仅支持 Type 0/1 和 PPQ 时间格式" }
        require(format != 0 || music.tracks.size == 1) { "Type 0 必须只有一个轨道" }
        data class Ev(val tick: Long, val track: Int, val order: Int, val status: Int, val a: Int, val b: Int, val payload: ByteArray?)
        val all = mutableListOf<Ev>(); val names = MutableList(music.tracks.size) { "轨道 ${it + 1}" }
        music.tracks.forEachIndexed { ti, track ->
            var tick = 0L
            track.events.forEachIndexed { index, e ->
                require(e.deltaTime >= 0 && all.size < MAX_EVENTS) { "MIDI 事件过多或时间无效" }
                tick += e.deltaTime; require(tick <= 1_000_000_000L) { "MIDI 时间超过限制" }
                val m = e.message; val status = m.statusByte.toInt() and 255
                val extra = (m as? Midi1CompoundMessage)?.let { c -> c.extraData?.copyOfRange(c.extraDataOffset, c.extraDataOffset + c.extraDataLength) }
                val a = m.msb.toInt() and 255; val b = m.lsb.toInt() and 255
                if (status == 255 && a == 3 && extra != null) names[ti] = extra.toString(Charsets.UTF_8).take(120)
                all.add(Ev(tick,ti,index,status,a,b,extra))
            }
        }
        all.sortWith(compareBy<Ev> { it.tick }.thenBy { it.track }.thenBy { it.order })
        val tempoEvents = all.filter { it.status == 255 && it.a == 0x51 }.map { e ->
            val p = requireNotNull(e.payload) { "速度事件缺少数据" }; require(p.size == 3) { "速度事件长度错误" }
            val value = ((p[0].toInt() and 255) shl 16) or ((p[1].toInt() and 255) shl 8) or (p[2].toInt() and 255)
            require(value > 0) { "速度事件无效" }; TempoPoint(e.tick,value)
        }
        val signatures = all.filter { it.status == 255 && it.a == 0x58 }.map { e ->
            val p = requireNotNull(e.payload) { "拍号事件缺少数据" }; require(p.size == 4 && (p[0].toInt() and 255) > 0 && (p[1].toInt() and 255) <= 7) { "拍号事件无效" }
            TimeSignaturePoint(e.tick, p[0].toInt() and 255, 1 shl (p[1].toInt() and 255))
        }
        val tempos = listOf(TempoPoint(0,500000)) + tempoEvents
        val clock = MidiTempoClock(ppq, tempos)
        data class NoteKey(val track: Int, val channel: Int, val pitch: Int)
        data class Start(val tick: Long, val velocity: Int)
        val open = HashMap<NoteKey, ArrayDeque<Start>>()
        val tracks = List(names.size) { mutableListOf<MidiNote>() }
        val noteOnCounts = IntArray(names.size)
        val warnings = mutableListOf<String>()
        var roundedShortNotes = 0
        var lastNoteEnd = 0L
        for (e in all) {
            val code=e.status and 0xf0; val channel=e.status and 0x0f
            if (code != 0x80 && code != 0x90) continue
            require(e.a in 0..127 && e.b in 0..127) { "音符数据超出 MIDI 范围" }
            val key = NoteKey(e.track, channel, e.a)
            if (code == 0x90 && e.b > 0) {
                noteOnCounts[e.track]++
                open.getOrPut(key) { ArrayDeque() }.addLast(Start(e.tick, e.b))
            } else {
                val started = open[key]
                val start = started?.removeFirstOrNull()
                if (start == null) { if (warnings.size < 100) warnings.add("未匹配的 Note Off：轨道 ${e.track+1} 音高 ${e.a}"); continue }
                if (started.isEmpty()) open.remove(key)
                val begin = clock.millisecondsAt(start.tick)
                val end = clock.millisecondsAt(e.tick)
                if (e.tick == start.tick) {
                    if (warnings.size < 100) warnings.add("零时长音符：${e.a}")
                    continue
                }
                if (end == begin) roundedShortNotes++
                val duration = (end - begin).coerceAtLeast(1L)
                tracks[e.track].add(MidiNote(begin, duration, e.a, start.velocity, e.track))
                lastNoteEnd = maxOf(lastNoteEnd, begin + duration)
            }
        }
        if (roundedShortNotes > 0) warnings.add("$roundedShortNotes 个音符不足毫秒精度，按 1 ms 保留。")
        val unmatched = open.values.sumOf { it.size }
        if (unmatched > 0) warnings.add("$unmatched 个 Note On 缺少 Note Off")
        val duration = maxOf(lastNoteEnd, all.lastOrNull()?.let { clock.millisecondsAt(it.tick) } ?: 0L)
        return ParsedMidi(format, ppq, tracks.mapIndexed { i, n -> MidiTrackInfo(names[i], n.sortedBy { it.startMs }, noteOnCounts[i]) }, tempos,
            duration, warnings, signatures)
    }
}

