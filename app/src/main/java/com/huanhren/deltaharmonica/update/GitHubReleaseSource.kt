package com.huanhren.deltaharmonica.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

internal interface ReleaseSource {
    suspend fun latest(installed: ApkIdentity): ReleaseLookup
    suspend fun download(asset: ReleaseAsset, destination: File, onProgress: (Long) -> Unit)
}

internal class GitHubReleaseSource(
    private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : ReleaseSource {
    override suspend fun latest(installed: ApkIdentity): ReleaseLookup = networkIo {
        request(URI(LATEST_RELEASE_URL), "application/vnd.github+json") { connection ->
            when (val status = connection.responseCode) {
                404 -> ReleaseLookup.NoRelease
                200 -> {
                    val bytes = connection.inputStream.use(::readReleaseMetadata)
                    if (bytes.size > MAX_RESPONSE_BYTES) throw UpdateException(UpdateProblem.RELEASE_METADATA)
                    val text = try {
                        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                    } catch (invalid: CharacterCodingException) {
                        throw UpdateException(UpdateProblem.RELEASE_METADATA, invalid)
                    }
                    ReleaseProtocol.parse(text, installed)
                }
                else -> throw UpdateException(httpProblem(status, connection.getHeaderField("X-RateLimit-Remaining")))
            }
        }
    }

    override suspend fun download(asset: ReleaseAsset, destination: File, onProgress: (Long) -> Unit) {
        networkIo {
            val activity = coroutineContext
            val timer = TimeSource.Monotonic.markNow()
            var url = asset.url
            repeat(4) {
                activity.ensureActive()
                val redirect = request(url, "application/octet-stream") { connection ->
                    when (val status = connection.responseCode) {
                        301, 302, 303, 307, 308 -> {
                            val location = connection.getHeaderField("Location")
                                ?: throw UpdateException(UpdateProblem.NETWORK)
                            val next = runCatching { url.resolve(location) }.getOrNull()
                                ?: throw UpdateException(UpdateProblem.NETWORK)
                            if (!isOfficialAssetDelivery(next)) throw UpdateException(UpdateProblem.NETWORK)
                            next
                        }
                        200 -> {
                            val declaredLength = connection.contentLengthLong
                            if (declaredLength >= 0 && declaredLength != asset.size) {
                                throw UpdateException(UpdateProblem.APK_SIZE)
                            }
                            connection.inputStream.use { input ->
                                val output = try {
                                    destination.outputStream().buffered()
                                } catch (failure: IOException) {
                                    throw UpdateException(UpdateProblem.STORAGE, failure)
                                }
                                output.use {
                                    copyVerifiedAsset(input, it, asset) { bytes ->
                                        activity.ensureActive()
                                        if (timer.elapsedNow().inWholeSeconds > 180) throw UpdateException(UpdateProblem.NETWORK)
                                        onProgress(bytes)
                                    }
                                    try {
                                        it.flush()
                                    } catch (failure: IOException) {
                                        throw UpdateException(UpdateProblem.STORAGE, failure)
                                    }
                                }
                            }
                            null
                        }
                        else -> throw UpdateException(httpProblem(status, connection.getHeaderField("X-RateLimit-Remaining")))
                    }
                }
                if (redirect == null) return@networkIo
                url = redirect
            }
            throw UpdateException(UpdateProblem.NETWORK)
        }
    }

    private suspend fun <T> networkIo(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (failure: IOException) {
            throw UpdateException(UpdateProblem.NETWORK, failure)
        }
    }

    private inline fun <T> request(uri: URI, accept: String, handle: (HttpURLConnection) -> T): T {
        val connection = open(uri)
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "DeltaHarmonica-Android")
            handle(connection)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}

internal fun httpProblem(status: Int, rateLimitRemaining: String? = null): UpdateProblem = when {
    status == 429 || (status == 403 && rateLimitRemaining == "0") -> UpdateProblem.RATE_LIMIT
    status == 401 || status == 403 -> UpdateProblem.ACCESS_DENIED
    status == 404 || status in 500..599 -> UpdateProblem.UNAVAILABLE
    else -> UpdateProblem.NETWORK
}

internal fun copyVerifiedAsset(input: InputStream, output: OutputStream, asset: ReleaseAsset, onProgress: (Long) -> Unit) {
    if (asset.size !in 1..MAX_APK_BYTES) throw UpdateException(UpdateProblem.APK_SIZE)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        total += count
        if (total > asset.size) throw UpdateException(UpdateProblem.APK_SIZE)
        digest.update(buffer, 0, count)
        try {
            output.write(buffer, 0, count)
        } catch (failure: IOException) {
            throw UpdateException(UpdateProblem.STORAGE, failure)
        }
        onProgress(total)
    }
    if (total != asset.size) throw UpdateException(UpdateProblem.APK_SIZE)
    if (digest.digest().hex() != asset.sha256) throw UpdateException(UpdateProblem.APK_DIGEST)
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun readReleaseMetadata(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    val timer = TimeSource.Monotonic.markNow()
    while (true) {
        val count = input.read(buffer)
        if (count == -1) return output.toByteArray()
        if (count > 512 * 1024 - output.size()) throw UpdateException(UpdateProblem.RELEASE_METADATA)
        if (timer.elapsedNow().inWholeSeconds > 30) throw UpdateException(UpdateProblem.NETWORK)
        output.write(buffer, 0, count)
    }
}
