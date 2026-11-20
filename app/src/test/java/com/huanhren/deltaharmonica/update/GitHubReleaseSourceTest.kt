package com.huanhren.deltaharmonica.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

class GitHubReleaseSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val installed = ApkIdentity(OFFICIAL_PACKAGE, 5, "2.3.0", setOf("test"))

    @Test fun missingReleaseAndGitHubErrorsAreHandledByLatest() = runBlocking {
        val missing = GitHubReleaseSource { url ->
            assertEquals(URI(LATEST_RELEASE_URL), url)
            Reply(url.toURL(), 404)
        }
        assertEquals(ReleaseLookup.NoRelease, missing.latest(installed))
        val failures = listOf(
            Triple(403, null, UpdateProblem.ACCESS_DENIED),
            Triple(403, "0", UpdateProblem.RATE_LIMIT),
            Triple(429, null, UpdateProblem.RATE_LIMIT),
            Triple(502, null, UpdateProblem.UNAVAILABLE),
        )
        for ((status, remaining, expected) in failures) {
            val source = GitHubReleaseSource { url ->
                Reply(url.toURL(), status, headers = remaining?.let { mapOf("X-RateLimit-Remaining" to it) }.orEmpty())
            }
            val actual = runCatching { source.latest(installed) }.exceptionOrNull() as UpdateException
            assertEquals(expected, actual.problem)
        }
    }

    @Test fun downloadedAssetAllowsOfficialDeliveryButRejectsCrossDomainRedirects() = runBlocking {
        val body = byteArrayOf(1, 2, 3)
        val official = URI("https://github.com/HuanHren/DeltaHarmonica/releases/download/v2.4.0/DeltaHarmonica-v2.4.0-release.apk")
        val delivery = URI("https://release-assets.githubusercontent.com/object")
        val asset = ReleaseAsset(official, body.size.toLong(), MessageDigest.getInstance("SHA-256").digest(body).hex())
        val requested = mutableListOf<URI>()
        val source = GitHubReleaseSource { url ->
            requested.add(url)
            if (url == delivery) Reply(url.toURL(), 200, body)
            else Reply(url.toURL(), 302, headers = mapOf("Location" to delivery.toString()))
        }
        val output = temporary.newFile()
        source.download(asset, output) {}
        assertEquals(listOf(official, delivery), requested)
        assertArrayEquals(body, output.readBytes())

        val blocked = GitHubReleaseSource { url ->
            requested.add(url)
            Reply(url.toURL(), 302, headers = mapOf("Location" to "https://external.invalid/apk"))
        }
        requested.clear()
        val unsafe = runCatching { blocked.download(asset, temporary.newFile()) {} }.exceptionOrNull() as UpdateException
        assertEquals(UpdateProblem.NETWORK, unsafe.problem)
        assertEquals(listOf(official), requested)
    }

    private class Reply(
        url: URL,
        private val status: Int,
        private val body: ByteArray = byteArrayOf(),
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(url) {
        override fun getResponseCode() = status
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String) = headers[name]
        override fun disconnect() = Unit
        override fun connect() = Unit
        override fun usingProxy() = false
    }
}
