package com.huanhren.deltaharmonica.online

import kotlinx.serialization.Serializable

@Serializable
data class OnlineEntry(
    val id: String,
    val title: String,
    val composer: String,
    val uploader: String,
    val tags: List<String>,
    val difficulty: Int,
    val bpm: Double,
    val durationMs: Long,
    val noteCount: Int,
    val updatedAt: Long,
    val avatar: String = "",
)

@Serializable
data class OnlineIndex(
    val version: Int,
    val count: Int,
    val updatedAt: Long,
    val shards: Map<String, Int>,
)

enum class OnlineSort { NEWEST, TITLE, DIFFICULTY }

data class OnlineCatalog(
    val entries: List<OnlineEntry>,
    val stale: Boolean,
    val index: OnlineIndex,
    val staleReason: String? = null,
) {
    fun search(query: String, tag: String? = null, sort: OnlineSort = OnlineSort.NEWEST): List<OnlineEntry> {
        val needle = query.trim()
        val category = tag?.trim().orEmpty()
        val matching = if (needle.isEmpty() && category.isEmpty()) entries else entries.filter { entry ->
            (category.isEmpty() || entry.tags.any { it.equals(category, ignoreCase = true) }) &&
                (needle.isEmpty() || entry.title.contains(needle, ignoreCase = true) ||
                    entry.composer.contains(needle, ignoreCase = true) ||
                    entry.uploader.contains(needle, ignoreCase = true) ||
                    entry.tags.any { it.contains(needle, ignoreCase = true) })
        }
        return when (sort) {
            OnlineSort.NEWEST -> matching
            OnlineSort.TITLE -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, OnlineEntry::title))
            OnlineSort.DIFFICULTY -> matching.sortedBy(OnlineEntry::difficulty)
        }
    }

    fun page(
        page: Int,
        pageSize: Int = 30,
        query: String = "",
        tag: String? = null,
        sort: OnlineSort = OnlineSort.NEWEST,
    ): List<OnlineEntry> {
        require(page >= 0 && pageSize > 0)
        val matches = search(query, tag, sort)
        val start = page.toLong() * pageSize
        if (start >= matches.size) return emptyList()
        return matches.subList(start.toInt(), (start + pageSize).coerceAtMost(matches.size.toLong()).toInt())
    }
}

data class OnlineScore(
    val entry: OnlineEntry,
    val payload: String,
    val stale: Boolean,
    val staleReason: String? = null,
)

class OnlineLibraryException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { INVALID_ID, NOT_FOUND, RATE_LIMITED, SERVER, NETWORK, TIMEOUT, PROTOCOL }
}
