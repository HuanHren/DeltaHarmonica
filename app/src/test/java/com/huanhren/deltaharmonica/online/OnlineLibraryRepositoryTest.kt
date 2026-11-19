package com.huanhren.deltaharmonica.online

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineLibraryRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private lateinit var cache: File
    private lateinit var config: OnlineLibraryConfig
    private var now = 1_000_000L

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.start()
        cache = temporary.newFolder()
        config = OnlineLibraryConfig(
            baseUrl = "http://127.0.0.1:${server.address.port}/harmonica",
            connectTimeoutMs = 500,
            readTimeoutMs = 500,
            retryDelayMs = 5,
        )
    }

    @After
    fun stopServer() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun repository(settings: OnlineLibraryConfig = config) =
        OnlineLibraryRepository(cache, settings) { now }

    @Test
    fun catalogMergesPopulatedShardsAndSupportsLocalFilteringSortingAndPages() = runBlocking {
        val indexCalls = AtomicInteger()
        route("index") { indexCalls.incrementAndGet(); reply(index) }
        route("catalog/0") { reply(shard("0", first)) }
        route("catalog/a") { reply(shard("a", second)) }

        val repo = repository()
        val catalog = repo.catalog()
        assertEquals(2, catalog.index.count)
        assertEquals(listOf("song_a", "song_0"), catalog.entries.map(OnlineEntry::id))
        assertEquals(listOf("song_0"), catalog.search("  CELINE ").map(OnlineEntry::id))
        assertEquals(listOf("song_a"), catalog.search("上传者", tag = "游戏").map(OnlineEntry::id))
        assertEquals(listOf("song_0"), catalog.page(page = 1, pageSize = 1).map(OnlineEntry::id))
        assertEquals(listOf("song_0", "song_a"), catalog.search("", sort = OnlineSort.DIFFICULTY).map(OnlineEntry::id))
        assertTrue(catalog.search("没有").isEmpty())
        assertEquals(catalog, repository().catalog())
        assertEquals(1, indexCalls.get())
    }

    @Test
    fun refreshChecksIndexAndReusesUnchangedShardsThenFallsBackOffline() = runBlocking {
        val indexCalls = AtomicInteger()
        val shardCalls = AtomicInteger()
        route("index") { indexCalls.incrementAndGet(); reply(index, maxAge = 1) }
        route("catalog/0") { shardCalls.incrementAndGet(); reply(shard("0", first)) }
        route("catalog/a") { shardCalls.incrementAndGet(); reply(shard("a", second)) }
        val repo = repository()
        val catalog = repo.catalog()
        now += 1001
        assertEquals(catalog, repo.catalog())
        assertEquals(2, indexCalls.get())
        assertEquals(2, shardCalls.get())
        server.stop(0)
        assertTrue(repository().catalog(force = true).stale)
        repo.clearCache()
        assertEquals(OnlineLibraryException.Kind.NETWORK, error { repository().catalog() }.kind)
    }

    @Test
    fun detailIsFetchedOnDemandAndSameIdRequestsShareTheFlight() = runBlocking {
        val calls = AtomicInteger()
        route("score/song_a") {
            calls.incrementAndGet()
            Thread.sleep(80)
            reply(detail)
        }
        val repo = repository()
        val firstCall = async { repo.detail("song_a", force = true) }
        val secondCall = async { repo.detail("song_a", force = true) }
        assertEquals(firstCall.await(), secondCall.await())
        assertEquals("YWJj", secondCall.await().payload)
        assertEquals(1, calls.get())
        assertFalse(repository().detail("song_a").stale)
        assertEquals(1, calls.get())
        assertEquals(OnlineLibraryException.Kind.INVALID_ID, error { repo.detail("../bad") }.kind)
    }

    @Test
    fun boundedRetryTreats429And5xxAsTransientAnd404AsRemoved() = runBlocking {
        val calls = AtomicInteger()
        route("score/song_a") {
            when (calls.incrementAndGet()) {
                1 -> reply("", 429, retryAfter = "0")
                2 -> reply("", 503)
                3 -> reply(detail)
                else -> reply("", 404)
            }
        }
        val repo = repository()
        assertEquals("song_a", repo.detail("song_a").entry.id)
        assertEquals(OnlineLibraryException.Kind.NOT_FOUND, error { repo.detail("song_a", force = true) }.kind)
        assertEquals(4, calls.get())
        assertEquals(OnlineLibraryException.Kind.NOT_FOUND, error { repo.detail("song_a") }.kind)

        val failures = AtomicInteger()
        route("score/song_0") { failures.incrementAndGet(); reply("", 500) }
        assertEquals(OnlineLibraryException.Kind.SERVER, error { repo.detail("song_0") }.kind)
        assertEquals(3, failures.get())
    }

    @Test
    fun timeoutStopsAfterBoundedRetries() = runBlocking {
        val calls = AtomicInteger()
        route("score/song_a") {
            calls.incrementAndGet()
            Thread.sleep(300)
            reply(detail)
        }
        val problem = error { repository(config.copy(readTimeoutMs = 60)).detail("song_a") }
        assertEquals(OnlineLibraryException.Kind.TIMEOUT, problem.kind)
        assertEquals(3, calls.get())
    }

    @Test
    fun cancellingLastWaiterCancelsInFlightRequestAndDoesNotPublishCache() = runBlocking {
        val requested = CountDownLatch(1)
        val released = CountDownLatch(1)
        val calls = AtomicInteger()
        route("score/song_a") {
            if (calls.incrementAndGet() == 1) {
                requested.countDown()
                released.await(2, TimeUnit.SECONDS)
            }
            reply(detail)
        }
        val repo = repository()
        val work = launch { repo.detail("song_a") }
        withTimeout(3000) { while (requested.count > 0) delay(10) }
        work.cancelAndJoin()
        val result = withTimeout(3000) { repo.detail("song_a") }
        released.countDown()
        assertEquals("song_a", result.entry.id)
        assertEquals(2, calls.get())
    }

    @Test
    fun corruptCacheAndMismatchedDetailCannotBeReused() = runBlocking {
        val calls = AtomicInteger()
        route("score/song_a") { calls.incrementAndGet(); reply(detail) }
        val repo = repository()
        repo.detail("song_a")
        cache.walkTopDown().single(File::isFile).appendText("broken")
        assertEquals("song_a", repository().detail("song_a").entry.id)
        assertEquals(2, calls.get())
        route("score/song_0") {
            reply(detail.replace("score/song_a", "score/song_0"))
        }
        assertEquals(OnlineLibraryException.Kind.PROTOCOL, error { repo.detail("song_0") }.kind)
    }

    @Test
    fun noStoreResponseIsNotRetained() = runBlocking {
        val calls = AtomicInteger()
        route("score/song_a") { calls.incrementAndGet(); reply(detail, noStore = true) }
        repository().detail("song_a")
        repository().detail("song_a")
        assertEquals(2, calls.get())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    private suspend fun error(block: suspend () -> Any): OnlineLibraryException = try {
        block()
        throw AssertionError("Expected OnlineLibraryException")
    } catch (error: OnlineLibraryException) {
        error
    }

    private fun route(key: String, handler: HttpExchange.() -> Unit) {
        server.createContext("/harmonica/$key.js") { request ->
            try {
                request.handler()
            } finally {
                request.close()
            }
        }
    }

    private fun HttpExchange.reply(
        value: String,
        code: Int = 200,
        maxAge: Int = 300,
        noStore: Boolean = false,
        retryAfter: String? = null,
    ) {
        responseHeaders.set("Cache-Control", if (noStore) "private, no-store" else "public, max-age=$maxAge")
        retryAfter?.let { responseHeaders.set("Retry-After", it) }
        val bytes = value.toByteArray()
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun shard(id: String, rows: String) = """__dfh("catalog/$id",{"r":[$rows]});"""

    private val index = """__dfh("index",{"v":1,"n":2,"t":30,"s":{"0":1,"2":0,"a":1}});"""
    private val first = """["song_0","Moon","Celine","Player","",["轻音乐"],1,115,1120,12,10]"""
    private val second = """["song_a","三角洲","作曲者","上传者","",["游戏"],3,125.5,2000,60,20]"""
    private val detail = """__dfh("score/song_a",{"i":"song_a","t":"三角洲","c":"作曲者","u":"上传者","a":"","g":["游戏"],"d":3,"b":125.5,"l":2000,"n":60,"m":20,"p":"YWJj"});"""
}
