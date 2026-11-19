package com.huanhren.deltaharmonica.online

import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

internal object OnlineLibraryProtocol {
    const val MAX_PAYLOAD_CHARS = 256 * 1024
    private const val MAX_ENTRIES = 25_000
    private val shardId = Regex("[0-9a-f]")
    private val scoreId = Regex("[A-Za-z0-9_-]{6,32}")
    private val payloadPattern = Regex("[A-Za-z0-9_-]+")

    fun validId(id: String): Boolean = scoreId.matches(id)

    fun index(text: String): OnlineIndex = parse("index", text) { json ->
        val version = json.required("v").integer()
        require(version == 1)
        val count = json.required("n").integer()
        require(count in 0..MAX_ENTRIES)
        val updatedAt = json.required("t").updatedAt()
        val shards = (json.required("s") as JsonObject).mapValues { (id, rows) ->
            require(shardId.matches(id))
            rows.integer().also { require(it in 0..MAX_ENTRIES) }
        }
        require(shards.values.sum() == count)
        OnlineIndex(version, count, updatedAt, shards)
    }

    fun shard(text: String, id: String, expectedCount: Int): List<OnlineEntry> = parse("catalog/$id", text) { json ->
        val rows = json.required("r") as JsonArray
        require(rows.size == expectedCount)
        rows.map { value ->
            val row = value as JsonArray
            require(row.size == 11)
            entry(row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7], row[8], row[9], row[10])
        }
    }

    fun detail(text: String, id: String): OnlineScore = parse("score/$id", text) { json ->
        val entry = entry(
            json.required("i"), json.required("t"), json.required("c"), json.required("u"),
            json.required("a"), json.required("g"), json.required("d"), json.required("b"),
            json.required("l"), json.required("n"), json.required("m"),
        )
        require(entry.id == id)
        val payload = json.required("p").text(MAX_PAYLOAD_CHARS)
        require(payload.length % 4 != 1 && payloadPattern.matches(payload))
        OnlineScore(entry, payload, stale = false)
    }

    private fun entry(
        id: JsonElement, title: JsonElement, composer: JsonElement, uploader: JsonElement,
        avatar: JsonElement, tags: JsonElement, difficulty: JsonElement, bpm: JsonElement,
        duration: JsonElement, notes: JsonElement, updated: JsonElement,
    ): OnlineEntry = OnlineEntry(
        id = id.text(32).also { require(validId(it)) },
        title = title.text(160).also { require(it.isNotBlank()) },
        composer = composer.text(160),
        uploader = uploader.text(160),
        tags = (tags as JsonArray).also { require(it.size <= 8) }
            .map { it.text(32).also { tag -> require(tag.isNotBlank()) } },
        difficulty = difficulty.integer().also { require(it in 0..5) },
        bpm = bpm.number().also { require(it in 0.01..100_000.0) },
        durationMs = duration.number().also { require(it in 0.0..9_007_199_254_740_991.0) }.roundToLong(),
        noteCount = notes.integer().also { require(it in 0..1_000_000) },
        updatedAt = updated.updatedAt(),
        avatar = avatar.text(512),
    )

    private fun JsonObject.required(key: String): JsonElement = requireNotNull(get(key))
    private fun JsonElement.text(max: Int): String =
        (this as JsonPrimitive).also { require(it.isString) }.content.also { require(it.length <= max) }
    private fun JsonElement.numeric(): JsonPrimitive = (this as JsonPrimitive).also { require(!it.isString) }
    private fun JsonElement.integer(): Int = numeric().int
    private fun JsonElement.number(): Double = numeric().double
    private fun JsonElement.updatedAt(): Long =
        numeric().long.also { require(it in 0..253_402_300_799_999L) }

    private inline fun <T> parse(key: String, text: String, read: (JsonObject) -> T): T {
        try {
            return read(JsonpParser.parse(text, key))
        } catch (error: IllegalArgumentException) {
            throw invalidMetadata(error)
        } catch (error: ClassCastException) {
            throw invalidMetadata(error)
        }
    }

    private fun invalidMetadata(cause: Throwable) =
        OnlineLibraryException(OnlineLibraryException.Kind.PROTOCOL, "曲库返回的曲目信息有误", cause)
}
