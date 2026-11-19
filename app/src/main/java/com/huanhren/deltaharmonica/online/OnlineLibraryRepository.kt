package com.huanhren.deltaharmonica.online

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OnlineLibraryRepository internal constructor(
    cacheDirectory: File,
    private val config: OnlineLibraryConfig,
    private val now: () -> Long,
) {
    constructor(cacheDirectory: File) : this(cacheDirectory, OnlineLibraryConfig(), System::currentTimeMillis)

    private val cache = OnlineLibraryCache(cacheDirectory)
    private val client = OnlineLibraryClient(config, now)
    private val requests = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val catalogFlights = Flights<Unit, OnlineCatalog>(requests)
    private val detailFlights = Flights<String, OnlineScore>(requests)

    suspend fun catalog(force: Boolean = false): OnlineCatalog = withContext(Dispatchers.IO) {
        val epoch = cache.generation()
        catalogFlights.await(Unit) { fetchCatalog(force, epoch) }
    }

    suspend fun detail(
        id: String,
        force: Boolean = false,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): OnlineScore = withContext(Dispatchers.IO) {
        if (!OnlineLibraryProtocol.validId(id)) {
            throw OnlineLibraryException(OnlineLibraryException.Kind.INVALID_ID, "曲谱编号无效")
        }
        val epoch = cache.generation()
        detailFlights.await(id) { fetchDetail(id, force, onProgress, epoch) }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) { cache.clear() }

    private suspend fun fetchCatalog(force: Boolean, epoch: Long): OnlineCatalog {
        val cached = cache.read(CATALOG_FILE, MAX_CATALOG_BYTES)
        val previous = cached?.let { record ->
            try {
                Json.decodeFromString<CatalogSnapshot>(record.text).also { snapshot ->
                    require(snapshot.index.version == 1 && snapshot.entries.size == snapshot.index.count)
                    require(snapshot.entries.all { OnlineLibraryProtocol.validId(it.id) })
                }
            } catch (_: IllegalArgumentException) {
                cache.write(CATALOG_FILE, null, epoch, MAX_CATALOG_BYTES)
                null
            }
        }
        if (!force && previous != null && cached.isFresh(now())) return previous.toCatalog()

        try {
            val response = client.read(
                OnlineLibraryConfig.INDEX_KEY, OnlineLibraryConfig.MAX_INDEX_BYTES, config.catalogTtlMs, force,
            )
            val index = OnlineLibraryProtocol.index(response.text)
            val unchangedEntries = previous?.takeIf { it.index == index }?.entries
            val pages = if (unchangedEntries == null) fetchShards(index, force) else emptyList()
            val entries = unchangedEntries ?: pages.flatMap(Shard::entries)
                .sortedWith(compareByDescending(OnlineEntry::updatedAt).thenBy(String.CASE_INSENSITIVE_ORDER, OnlineEntry::title))
            if (entries.size != index.count || entries.map(OnlineEntry::id).toSet().size != entries.size) {
                throw OnlineLibraryException(OnlineLibraryException.Kind.PROTOCOL, "曲库目录正在更新，请稍后刷新")
            }
            val snapshot = CatalogSnapshot(index, entries)
            val ttl = cacheTtl(listOf(response.ttlMs) + pages.map(Shard::ttlMs))
            currentCoroutineContext().ensureActive()
            cache.write(
                CATALOG_FILE,
                ttl?.let { OnlineLibraryCache.Record(Json.encodeToString(snapshot), now() + it) },
                epoch,
                MAX_CATALOG_BYTES,
            )
            return snapshot.toCatalog()
        } catch (error: OnlineLibraryException) {
            currentCoroutineContext().ensureActive()
            return previous?.toCatalog(reason = error.message) ?: throw error
        }
    }

    private suspend fun fetchShards(index: OnlineIndex, force: Boolean): List<Shard> = coroutineScope {
        index.shards.filterValues { it > 0 }.map { (id, count) ->
            async {
                val response = client.read(
                    OnlineLibraryConfig.shardKey(id), OnlineLibraryConfig.MAX_SHARD_BYTES, config.catalogTtlMs, force,
                )
                Shard(OnlineLibraryProtocol.shard(response.text, id, count), response.ttlMs)
            }
        }.awaitAll()
    }

    private suspend fun fetchDetail(
        id: String,
        force: Boolean,
        onProgress: ((Long, Long?) -> Unit)?,
        epoch: Long,
    ): OnlineScore {
        val maxBytes = OnlineLibraryConfig.MAX_DETAIL_BYTES
        val name = "score-$id.cache"
        val cached = cache.read(name, maxBytes)
        val previous = cached?.let {
            try {
                OnlineLibraryProtocol.detail(it.text, id)
            } catch (_: OnlineLibraryException) {
                cache.write(name, null, epoch, maxBytes)
                null
            }
        }
        if (!force && previous != null && cached.isFresh(now())) return previous

        try {
            val response = client.read(OnlineLibraryConfig.scoreKey(id), maxBytes, config.detailTtlMs, force, onProgress)
            val score = OnlineLibraryProtocol.detail(response.text, id)
            currentCoroutineContext().ensureActive()
            val record = response.ttlMs?.let { OnlineLibraryCache.Record(response.text, now() + it) }
            cache.write(name, record, epoch, maxBytes)
            return score
        } catch (error: OnlineLibraryException) {
            currentCoroutineContext().ensureActive()
            if (error.kind == OnlineLibraryException.Kind.NOT_FOUND) {
                cache.write(name, null, epoch, maxBytes)
                throw error
            }
            return previous?.copy(stale = true, staleReason = error.message) ?: throw error
        }
    }

    private fun cacheTtl(values: List<Long?>): Long? =
        if (values.any { it == null }) null else values.filterNotNull().minOrNull()

    @Serializable
    private data class CatalogSnapshot(val index: OnlineIndex, val entries: List<OnlineEntry>) {
        fun toCatalog(reason: String? = null) = OnlineCatalog(entries, reason != null, index, reason)
    }

    private data class Shard(val entries: List<OnlineEntry>, val ttlMs: Long?)

    // A waiter owns its share of a request. Other screens or events can join
    // that request, and the last departing waiter cancels unfinished work.
    private class Flights<K, V>(private val scope: CoroutineScope) {
        private class Flight<T>(val job: Deferred<T>, var waiters: Int = 0)

        private val active = mutableMapOf<K, Flight<V>>()

        suspend fun await(key: K, load: suspend () -> V): V {
            val flight = synchronized(active) {
                active.getOrPut(key) { Flight(scope.async(start = CoroutineStart.LAZY) { load() }) }
                    .also { it.waiters++ }
            }
            try {
                return flight.job.await()
            } finally {
                synchronized(active) {
                    flight.waiters--
                    if (flight.waiters == 0) {
                        active.remove(key)
                        flight.job.cancel()
                    }
                }
            }
        }
    }

    private companion object {
        const val CATALOG_FILE = "catalog.cache"
        const val MAX_CATALOG_BYTES = 16 * 1024 * 1024
    }
}
