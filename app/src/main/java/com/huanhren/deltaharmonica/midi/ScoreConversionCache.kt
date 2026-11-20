package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.model.InstrumentProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal object ScoreConversionCache {
    // Bump the corresponding version when parsing semantics or the playable script changes.
    private const val MIDI_READER_VERSION = 1
    private const val DFHS_READER_VERSION = 1
    private const val CONVERTER_VERSION = 1
    private const val MAX_ENTRIES = 8
    private const val MAX_RETAINED_ELEMENTS = 40_000
    private const val HEX = "0123456789abcdef"

    data class Key(
        val sourceSha256: String,
        val format: ScoreFormat,
        val decoderVersion: Int,
        val converterVersion: Int,
        val profileSha256: String,
        val layoutSha256: String?,
        val track: Int,
        val policy: MonophonyPolicy,
        val transpose: Int,
    )

    private data class Entry(val report: ConversionReport, val weight: Int)
    private class Conversion {
        val lock = Mutex()
        var users = 0
        var report: ConversionReport? = null
    }
    private val cache = LinkedHashMap<Key, Entry>(MAX_ENTRIES, 0.75f, true)
    private val conversions = mutableMapOf<Key, Conversion>()
    private var weight = 0

    fun sha256(bytes: ByteArray): String = buildString(64) {
        for (digestByte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
            val value = digestByte.toInt() and 255
            append(HEX[value shr 4])
            append(HEX[value and 15])
        }
    }

    fun key(
        sourceSha256: String,
        format: ScoreFormat,
        track: Int,
        policy: MonophonyPolicy,
        transpose: Int,
        profile: InstrumentProfile,
        layout: InstrumentLayout?,
    ): Key {
        // Calibration positions and control roles matter; record UUIDs, timestamps and labels do not.
        val layoutSignature = layout?.let {
            listOf(
                it.orientation.name,
                it.coordinateSpace.name,
                it.referenceDisplay?.let { display -> Json.encodeToString(display) }.orEmpty(),
                Json.encodeToString(it.targets.map { target ->
                    target.copy(label = target.id, attributes = target.attributes.toSortedMap())
                }),
            ).joinToString("|")
        }
        return Key(
            sourceSha256, format, if (format == ScoreFormat.DFHS1) DFHS_READER_VERSION else MIDI_READER_VERSION,
            CONVERTER_VERSION, sha256(Json.encodeToString(profile).encodeToByteArray()),
            layoutSignature?.let { sha256(it.encodeToByteArray()) }, track, policy, transpose,
        )
    }

    suspend fun getOrConvert(key: Key, fileName: String, convert: suspend () -> ConversionReport): ConversionReport {
        val active = synchronized(cache) {
            cache[key]?.let { return it.report.named(fileName) }
            conversions.getOrPut(key, ::Conversion).also { it.users++ }
        }
        try {
            return active.lock.withLock {
                val result = active.report ?: convert().also {
                    active.report = it
                    retain(key, it)
                }
                result.named(fileName)
            }
        } finally {
            synchronized(cache) {
                if (--active.users == 0) conversions.remove(key)
            }
        }
    }

    private fun ConversionReport.named(fileName: String): ConversionReport =
        if (script.sourceMidiName == fileName) this else copy(script = script.copy(sourceMidiName = fileName))

    private fun retain(key: Key, result: ConversionReport) {
        val size = result.script.events.size + result.script.tempos.size + result.script.timeSignatures.size + result.warnings.size
        if (size > MAX_RETAINED_ELEMENTS) return
        synchronized(cache) {
            val entry = Entry(result, size)
            weight += size - (cache.put(key, entry)?.weight ?: 0)
            val oldest = cache.entries.iterator()
            while (cache.size > MAX_ENTRIES || weight > MAX_RETAINED_ELEMENTS) {
                weight -= oldest.next().value.weight
                oldest.remove()
            }
        }
    }
}
