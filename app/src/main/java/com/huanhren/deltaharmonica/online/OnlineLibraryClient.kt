package com.huanhren.deltaharmonica.online

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class OnlineLibraryConfig(
    val baseUrl: String = PUBLIC_STORAGE_BASE,
    val connectTimeoutMs: Int = 8_000,
    val readTimeoutMs: Int = 12_000,
    val retryDelayMs: Long = 400,
    val catalogTtlMs: Long = 60_000,
    val detailTtlMs: Long = 300_000,
) {
    init {
        val uri = URI(baseUrl)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host == "127.0.0.1"))
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && retryDelayMs >= 0)
        require(catalogTtlMs > 0 && detailTtlMs > 0)
    }

    companion object {
        const val PUBLIC_STORAGE_BASE = "https://656e-endfield-d3gdy9wg4afba9d16-1474357318.tcb.qcloud.la/harmonica"
        const val INDEX_KEY = "index"
        const val MAX_INDEX_BYTES = 8 * 1024
        const val MAX_SHARD_BYTES = 2 * 1024 * 1024
        const val MAX_DETAIL_BYTES = 320 * 1024
        const val MAX_CONCURRENT_REQUESTS = 4

        fun shardKey(id: String) = "catalog/$id"
        fun scoreKey(id: String) = "score/$id"
    }
}

internal class OnlineLibraryClient(
    private val config: OnlineLibraryConfig,
    private val now: () -> Long,
) {
    data class Response(val text: String, val ttlMs: Long?)

    private val requests = Semaphore(OnlineLibraryConfig.MAX_CONCURRENT_REQUESTS)

    suspend fun read(
        key: String,
        maxBytes: Int,
        appTtlMs: Long,
        force: Boolean,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Response {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return requests.withPermit {
                    withTimeoutOrNull(
                        (config.connectTimeoutMs.toLong() + config.readTimeoutMs) * 2,
                    ) { request(key, maxBytes, appTtlMs, force, onProgress) }
                        ?: throw OnlineLibraryException(OnlineLibraryException.Kind.TIMEOUT, "曲库连接超时，请重试")
                }
            } catch (error: HttpFailure) {
                val retry = error.kind == OnlineLibraryException.Kind.SERVER ||
                    error.kind == OnlineLibraryException.Kind.RATE_LIMITED
                val wait = error.retryAfterMs ?: retryDelay(attempt)
                if (!retry || attempt == MAX_ATTEMPTS - 1 || wait > MAX_RETRY_AFTER_MS) {
                    throw OnlineLibraryException(error.kind, error.message)
                }
                delay(wait)
            } catch (error: SocketTimeoutException) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw OnlineLibraryException(OnlineLibraryException.Kind.TIMEOUT, "曲库连接超时，请重试", error)
                }
                delay(retryDelay(attempt))
            } catch (error: IOException) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw OnlineLibraryException(OnlineLibraryException.Kind.NETWORK, "无法连接曲库，请检查网络后重试", error)
                }
                delay(retryDelay(attempt))
            } catch (error: OnlineLibraryException) {
                if (error.kind != OnlineLibraryException.Kind.TIMEOUT || attempt == MAX_ATTEMPTS - 1) throw error
                delay(retryDelay(attempt))
            }
        }
        error("Retry limit")
    }

    private fun retryDelay(attempt: Int) = config.retryDelayMs * (1L shl attempt)

    private suspend fun request(
        key: String,
        limit: Int,
        appTtlMs: Long,
        force: Boolean,
        onProgress: ((Long, Long?) -> Unit)?,
    ): Response = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val url = URI("${config.baseUrl.trimEnd('/')}/$key.js").toURL()
            val connection = url.openConnection() as HttpURLConnection
            continuation.invokeOnCancellation {
                // Some URLConnection implementations lock while reading. Disconnect on
                // I/O so a blocked socket can't hold up cancellation on the UI thread.
                Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
                    runCatching { connection.disconnect() }
                })
            }
            try {
                continuation.context.ensureActive()
                connection.connectTimeout = config.connectTimeoutMs
                connection.readTimeout = config.readTimeoutMs
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Accept", "application/javascript, text/javascript, */*")
                if (force) connection.setRequestProperty("Cache-Control", "no-cache")

                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw statusFailure(key, status, connection)
                val advertisedLength = connection.contentLengthLong.takeIf { it >= 0 }
                if (advertisedLength != null && advertisedLength > limit) throw oversizedBody(key)
                val bytes = connection.inputStream.use { input ->
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    onProgress?.invoke(0, advertisedLength)
                    while (true) {
                        continuation.context.ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count > limit - result.size()) throw oversizedBody(key)
                        result.write(buffer, 0, count)
                        onProgress?.invoke(result.size().toLong(), advertisedLength)
                    }
                    if (advertisedLength != null && result.size().toLong() != advertisedLength) {
                        throw IOException("Incomplete HTTP response")
                    }
                    result.toByteArray()
                }
                val text = try {
                    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                } catch (error: CharacterCodingException) {
                    throw OnlineLibraryException(OnlineLibraryException.Kind.PROTOCOL, "曲库返回的文字编码有误", error)
                }
                continuation.resume(Response(text, cacheTtl(connection, appTtlMs)))
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun oversizedBody(key: String) = OnlineLibraryException(
        OnlineLibraryException.Kind.PROTOCOL,
        if (key.startsWith("score/")) "曲谱文件过大" else "曲库目录过大",
    )

    private fun statusFailure(key: String, status: Int, connection: HttpURLConnection): HttpFailure {
        val retryAfter = retryAfter(connection.getHeaderField("Retry-After"))
        return when (status) {
            404, 410 -> HttpFailure(
                OnlineLibraryException.Kind.NOT_FOUND,
                if (key.startsWith("score/")) "曲谱不存在或已下架" else "曲库目录暂时不可用",
                null,
            )
            429 -> HttpFailure(OnlineLibraryException.Kind.RATE_LIMITED, "曲库请求过于频繁，请稍后重试", retryAfter)
            in 500..599 -> HttpFailure(OnlineLibraryException.Kind.SERVER, "曲库服务暂时不可用（$status）", retryAfter)
            else -> HttpFailure(OnlineLibraryException.Kind.PROTOCOL, "曲库请求失败（$status）", null)
        }
    }

    private fun retryAfter(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        text.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
            return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
        }
        return runCatching {
            val then = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (then - now()).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun cacheTtl(connection: HttpURLConnection, appTtlMs: Long): Long? {
        val directives = connection.getHeaderField("Cache-Control").orEmpty().split(',')
            .associate { directive ->
                directive.substringBefore('=').trim().lowercase() to
                    directive.substringAfter('=', "").trim().trim('"')
            }
        if ("no-store" in directives || "private" in directives || "no-cache" in directives ||
            "must-revalidate" in directives
        ) return null
        val maxAge = directives["max-age"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return appTtlMs
        val age = connection.getHeaderField("Age")?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: 0
        val remaining = (maxAge - age).coerceIn(0, Long.MAX_VALUE / 1000)
        return minOf(appTtlMs, remaining * 1000)
    }

    private class HttpFailure(
        val kind: OnlineLibraryException.Kind,
        override val message: String,
        val retryAfterMs: Long?,
    ) : Exception(message)

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_RETRY_AFTER_MS = 30_000L
    }
}
