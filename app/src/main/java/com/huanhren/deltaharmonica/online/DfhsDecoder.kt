package com.huanhren.deltaharmonica.online

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.roundToLong

data class DfhsScore(
    val version: Int,
    val ppq: Int,
    val transpose: Int,
    val tempos: List<DfhsTempo>,
    val timeSignatures: List<DfhsTimeSignature>,
    val measureStartsTicks: List<Long>,
    val notes: List<DfhsNote>,
)

data class DfhsTempo(val startTick: Long, val bpm: Double, val timeMs: Long)

data class DfhsTimeSignature(
    val startTick: Long,
    val numerator: Int,
    val denominator: Int,
    val timeMs: Long,
)

data class DfhsNote(
    val pitch: Int,
    val startTick: Long,
    val durationTick: Long,
    val startMs: Long,
    val durationMs: Long,
    val beat: Double,
    val durationBeats: Double,
)

enum class DfhsFormatFailure { INVALID_DATA, TOO_LARGE, UNSUPPORTED_VERSION }

class DfhsFormatException(
    val failure: DfhsFormatFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object DfhsDecoder {
    private const val VERSION = 1
    private const val BASE64_URL_CHARS = 256 * 1024
    private const val COMPRESSED_BYTES = BASE64_URL_CHARS * 3 / 4
    private const val INFLATED_BYTES = 8 * 1024 * 1024
    private const val MAX_TEMPOS = 4096
    private const val MAX_SIGNATURES = 1024
    private const val MAX_MEASURES = 100_000
    private const val MAX_NOTES = 200_000
    private const val MAX_SAFE_TICK = (1L shl 53) - 1 // JavaScript's maximum exact wire integer.
    private const val MAX_TIME_MS = Long.MAX_VALUE / MAX_NOTES // Keep sums of note durations representable.
    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun decodeBase64(payload: String): ByteArray {
        if (payload.length > BASE64_URL_CHARS) tooLarge("曲谱文件太大")
        val tailSize = payload.length % 4
        requireValid(payload.isNotEmpty() && tailSize != 1 && payload.all { it in BASE64_ALPHABET }) {
            "曲谱编码无效"
        }
        val lastDigit = BASE64_ALPHABET.indexOf(payload.last())
        requireValid((tailSize != 2 || lastDigit % 16 == 0) && (tailSize != 3 || lastDigit % 4 == 0)) {
            "曲谱编码结尾无效"
        }
        return Base64.getUrlDecoder().decode(payload)
    }

    fun decode(compressed: ByteArray, checkActive: () -> Unit = {}): DfhsScore {
        if (compressed.size > COMPRESSED_BYTES) tooLarge("曲谱文件太大")
        requireValid(compressed.isNotEmpty()) { "曲谱文件为空" }
        val reader = Reader(inflate(compressed, checkActive), checkActive)
        for (character in "DFHS") {
            requireValid(reader.byte() == character.code) { "曲谱文件格式无效" }
        }
        val version = reader.byte()
        if (version != VERSION) {
            throw DfhsFormatException(DfhsFormatFailure.UNSUPPORTED_VERSION, "曲谱版本 $version 暂不支持")
        }
        val ppq = reader.boundedInt(24, 9600, "曲谱时间精度无效")
        val zigZag = reader.boundedInt(0, 96, "曲谱移调无效")
        val transpose = if (zigZag % 2 == 0) zigZag / 2 else -(zigZag + 1) / 2

        val tempos = readTempos(reader, ppq)
        val clock = TempoClock(ppq, tempos)
        val signatures = readSignatures(reader, clock)
        val measures = reader.readDeltaSection(MAX_MEASURES, 1, "曲谱小节过多") { it }
        val notes = reader.readDeltaSection(MAX_NOTES, 3, "曲谱音符数量超过解码上限") { tick ->
            val duration = reader.varint()
            requireValid(duration > 0) { "曲谱音符时值无效" }
            val pitch = reader.byte()
            requireValid(pitch <= 127) { "曲谱音高无效" }
            requireValid(duration <= MAX_SAFE_TICK - tick) { "曲谱节拍超出范围" }
            val beat = tick.toDouble() / ppq
            val durationBeats = duration.toDouble() / ppq
            val start = clock.millisecondsAt(beat)
            val end = clock.millisecondsAt(beat + durationBeats)
            val startMs = start.roundToLong()
            val durationMs = (end - start).roundToLong().coerceAtLeast(1)
            requireValid(startMs <= MAX_TIME_MS - durationMs) {
                "曲谱音符时间过长"
            }
            DfhsNote(pitch, tick, duration, startMs, durationMs, beat, durationBeats)
        }
        requireValid(notes.isNotEmpty()) { "曲谱没有音符" }
        requireValid(reader.remaining == 0) { "曲谱文件包含多余数据" }
        return DfhsScore(version, ppq, transpose, tempos, signatures, measures, notes)
    }

    private fun readTempos(reader: Reader, ppq: Int): List<DfhsTempo> {
        var previous: DfhsTempo? = null
        var milliseconds = 0.0
        val tempos = reader.readDeltaSection(MAX_TEMPOS, 2, "曲谱变速点过多") { tick ->
            val bpm = reader.boundedInt(1, 100_000, "曲谱速度无效") / 100.0
            val prior = previous
            if (prior == null) {
                requireValid(tick == 0L) { "曲谱缺少起始速度" }
            } else {
                milliseconds += (tick.toDouble() / ppq - prior.startTick.toDouble() / ppq) * 60_000 / prior.bpm
            }
            DfhsTempo(tick, bpm, roundedTime(milliseconds)).also { previous = it }
        }
        return tempos.ifEmpty { listOf(DfhsTempo(0, 120.0, 0)) }
    }

    private fun readSignatures(reader: Reader, clock: TempoClock): List<DfhsTimeSignature> {
        val signatures = reader.readDeltaSection(MAX_SIGNATURES, 3, "曲谱拍号过多") { tick ->
            DfhsTimeSignature(
                tick,
                reader.boundedInt(1, 32, "曲谱拍号无效"),
                reader.boundedInt(1, 64, "曲谱拍号无效"),
                roundedTime(clock.millisecondsAt(tick.toDouble() / clock.ppq)),
            )
        }
        return signatures.ifEmpty { listOf(DfhsTimeSignature(0, 4, 4, 0)) }
    }

    // The web and desktop readers round each tempo anchor before interpolating within that tempo.
    private class TempoClock(val ppq: Int, private val tempos: List<DfhsTempo>) {
        private val beatStarts = DoubleArray(tempos.size) { tempos[it].startTick.toDouble() / ppq }

        fun millisecondsAt(beat: Double): Double {
            var from = 0
            var until = beatStarts.size
            while (from + 1 < until) {
                val middle = (from + until) / 2
                if (beatStarts[middle] <= beat) from = middle else until = middle
            }
            val tempo = tempos[from]
            val time = tempo.timeMs + (beat - beatStarts[from]) * 60_000 / tempo.bpm
            requireValid(time >= 0 && time <= MAX_TIME_MS.toDouble()) { "曲谱时间过长" }
            return time
        }
    }

    private fun inflate(compressed: ByteArray, checkActive: () -> Unit): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream(8192)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                checkActive()
                val count = inflater.inflate(buffer)
                requireValid(count != 0 || inflater.finished()) { "曲谱压缩数据不完整" }
                if (count > INFLATED_BYTES - output.size()) tooLarge("曲谱解压后太大")
                output.write(buffer, 0, count)
            }
            requireValid(inflater.remaining == 0) { "曲谱压缩文件包含多余数据" }
            return output.toByteArray()
        } catch (error: DataFormatException) {
            throw DfhsFormatException(DfhsFormatFailure.INVALID_DATA, "曲谱压缩数据损坏", error)
        } finally {
            inflater.end()
        }
    }

    private class Reader(private val bytes: ByteArray, private val checkActive: () -> Unit) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset

        fun byte(): Int {
            requireValid(remaining > 0) { "曲谱数据不完整" }
            if (offset % 4096 == 0) checkActive()
            return bytes[offset++].toInt() and 0xff
        }

        fun varint(): Long {
            var value = 0L
            for (shift in 0..49 step 7) {
                val byte = byte()
                val bits = byte and 0x7f
                requireValid(shift < 49 || bits <= 15) { "曲谱数值超出范围" }
                value = value or (bits.toLong() shl shift)
                if (byte < 0x80) {
                    requireValid(shift == 0 || bits != 0) { "曲谱数值编码无效" }
                    return value
                }
            }
            throw DfhsFormatException(DfhsFormatFailure.INVALID_DATA, "曲谱数值编码过长")
        }

        fun boundedInt(min: Int, max: Int, message: String): Int {
            val value = varint()
            requireValid(value >= min && value <= max) { message }
            return value.toInt()
        }

        fun <T> readDeltaSection(max: Int, minimumRecordBytes: Int, overflowMessage: String, read: (Long) -> T): List<T> {
            val count = varint()
            if (count > max) tooLarge(overflowMessage)
            requireValid(count <= remaining / minimumRecordBytes) { "曲谱数据不完整" }
            var tick = 0L
            return List(count.toInt()) {
                tick = addTicks(tick, varint())
                read(tick)
            }
        }
    }

    private fun roundedTime(milliseconds: Double): Long {
        requireValid(milliseconds >= 0 && milliseconds <= MAX_TIME_MS.toDouble()) { "曲谱时间过长" }
        return milliseconds.roundToLong()
    }

    private fun addTicks(tick: Long, delta: Long): Long {
        requireValid(delta <= MAX_SAFE_TICK - tick) { "曲谱节拍超出范围" }
        return tick + delta
    }

    private inline fun requireValid(condition: Boolean, message: () -> String) {
        if (!condition) throw DfhsFormatException(DfhsFormatFailure.INVALID_DATA, message())
    }

    private fun tooLarge(message: String): Nothing = throw DfhsFormatException(DfhsFormatFailure.TOO_LARGE, message)
}
