package com.huanhren.deltaharmonica.midi

enum class ImportStatus { CLEAN, WARNING, RECOVERED, UNSUPPORTED, CORRUPTED }
data class ImportDiagnostic(
    val type: String, val offset: Int, val trackIndex: Int? = null,
    val suspiciousEvents: Int? = null, val possibleLostNotes: Int? = null,
)
class MidiImportException(val kind: ImportStatus, message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

internal object MidiContainer {
    fun extract(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) throw MidiImportException(ImportStatus.CORRUPTED, "文件为空，无法读取 MIDI。")
        fun isAt(offset: Int, signature: String) = bytes.size >= offset + signature.length && signature.indices.all { bytes[offset+it].toInt() and 255 == signature[it].code }
        if (isAt(0,"MThd")) return bytes
        if (isAt(0,"PK\u0003\u0004") || isAt(0,"PK\u0005\u0006") || isAt(0,"PK\u0007\u0008"))
            throw MidiImportException(ImportStatus.UNSUPPORTED, "这是 ZIP 压缩包，请先解压并选择其中的 MIDI 文件。")
        val text = bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
        if (text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<?xml"))
            throw MidiImportException(ImportStatus.UNSUPPORTED, "文件不是有效的 MIDI，可能下载到了网页。")
        if (!isAt(0,"RIFF")) throw MidiImportException(ImportStatus.UNSUPPORTED, "不支持的文件格式：请选择 SMF 或 RMID MIDI 文件。")
        fun little(i: Int): Long {
            if (i < 0 || i.toLong() + 4 > bytes.size) throw MidiImportException(ImportStatus.CORRUPTED, "RIFF 长度字段被截断。")
            return (0..3).fold(0L) { value, n -> value or ((bytes[i+n].toLong() and 255) shl (8*n)) }
        }
        if (bytes.size < 12 || !isAt(8,"RMID")) throw MidiImportException(ImportStatus.UNSUPPORTED, "RIFF 容器不是 RMID MIDI。")
        val end = little(4) + 8
        if (end < 12 || end > bytes.size) throw MidiImportException(ImportStatus.CORRUPTED, "RMID 容器长度超出文件。")
        if (end != bytes.size.toLong()) throw MidiImportException(ImportStatus.CORRUPTED, "RMID 容器后存在无法确认的数据。")
        var pos = 12; var midi: ByteArray? = null
        while (pos.toLong() < end) {
            if (pos.toLong() + 8 > end) throw MidiImportException(ImportStatus.CORRUPTED, "RMID 子块头在偏移 $pos 处截断。")
            val size = little(pos+4); val start = pos.toLong()+8; val next = start + size + (size and 1)
            if (next > end) throw MidiImportException(ImportStatus.CORRUPTED, "RMID 子块在偏移 $pos 处超出容器边界。")
            if (isAt(pos,"data")) {
                if (midi != null) throw MidiImportException(ImportStatus.CORRUPTED, "RMID 含多个 data 子块，无法确定应使用哪一个。")
                midi = bytes.copyOfRange(start.toInt(), (start+size).toInt())
            }
            pos = next.toInt()
        }
        return midi ?: throw MidiImportException(ImportStatus.CORRUPTED, "RMID 缺少 MIDI data 子块。")
    }
}
