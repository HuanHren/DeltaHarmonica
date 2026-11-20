package com.huanhren.deltaharmonica.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseUpdatesTest {
    private val identity = ApkIdentity(OFFICIAL_PACKAGE, 5, "2.3.0", setOf("test"))
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val asset = ReleaseAsset(URI("https://release-assets.githubusercontent.com/apk"), bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).hex())
    private val release = StableRelease("v2.4.0", StableVersion(2, 4, 0), 6L, Instant.EPOCH, "Fix", asset)
    private val apk = object : ApkStore {
        override suspend fun installed() = identity

        override suspend fun downloadAndVerify(release: StableRelease, source: ReleaseSource, onProgress: (Long) -> Unit): File {
            source.download(release.asset, File("unused-apk"), onProgress)
            onProgress(1)
            throw UpdateException(UpdateProblem.APK_SIGNATURE)
        }
    }

    @Test fun coldStartChecksOnlyOnceAndOnlyExplicitDownloadCanFetchTheApk() = runTest {
        val completion = CompletableDeferred<ReleaseLookup>()
        val source = FakeSource { completion.await() }
        val settings = FakeSettings()
        val controller = ReleaseUpdateController(backgroundScope, settings, source, apk, now = { 42 })
        repeat(3) { controller.startOnce() }
        runCurrent()
        assertEquals(1, source.checks)
        assertEquals(UpdateCheck.Checking, controller.check.value)
        controller.checkManually()
        runCurrent()
        assertEquals(1, source.checks)
        completion.complete(ReleaseLookup.Available(release))
        runCurrent()
        assertEquals(release, controller.offer.value)
        assertEquals(42L, settings.values.value?.lastCheckedAtMs)
        assertEquals(0, source.downloads)
        assertEquals(UpdateDownload.Idle, controller.download.value)
        controller.dismissOffer()
        controller.startOnce()
        runCurrent()
        assertEquals(1, source.checks)
        assertNull(controller.offer.value)
    }

    @Test fun persistedOptOutIsRespectedAndManualCheckRemainsAvailable() = runTest {
        val source = FakeSource { ReleaseLookup.Available(release) }
        val settings = FakeSettings(automatic = false)
        val controller = ReleaseUpdateController(backgroundScope, settings, source, apk)
        controller.startOnce()
        runCurrent()
        assertEquals(0, source.checks)
        controller.setAutomatic(true)
        runCurrent()
        controller.startOnce()
        runCurrent()
        assertEquals(0, source.checks)
        controller.checkManually()
        runCurrent()
        assertEquals(1, source.checks)
        assertEquals(UpdateCheck.Available(release), controller.check.value)
        assertNull(controller.offer.value)
        controller.download()
        runCurrent()
        assertEquals(UpdateDownload.Failed(UpdateProblem.APK_SIGNATURE), controller.download.value)
        assertEquals(1, source.downloads)
    }

    @Test fun optingOutDuringInFlightCheckSuppressesOfferAndPersistsForNextProcess() = runTest {
        val completion = CompletableDeferred<ReleaseLookup>()
        val source = FakeSource { completion.await() }
        val settings = FakeSettings()
        val first = ReleaseUpdateController(backgroundScope, settings, source, apk)
        first.startOnce()
        runCurrent()
        first.setAutomatic(false)
        runCurrent()
        completion.complete(ReleaseLookup.Available(release))
        runCurrent()
        assertEquals(UpdateCheck.Available(release), first.check.value)
        assertNull(first.offer.value)
        val next = ReleaseUpdateController(backgroundScope, settings, source, apk)
        next.startOnce()
        runCurrent()
        assertEquals(1, source.checks)
        assertEquals(UpdateCheck.Idle, next.check.value)
    }

    @Test fun connectivityErrorRecordsAttemptWithoutAutomaticRetry() = runTest {
        val source = FakeSource { throw UpdateException(UpdateProblem.NETWORK) }
        val settings = FakeSettings()
        val controller = ReleaseUpdateController(backgroundScope, settings, source, apk, now = { 51 })
        controller.startOnce()
        runCurrent()
        assertEquals(1, source.checks)
        assertEquals(51L, settings.values.value?.lastCheckedAtMs)
        assertEquals(UpdateCheck.Failed(UpdateProblem.NETWORK), controller.check.value)
        controller.startOnce()
        runCurrent()
        assertEquals(1, source.checks)
    }

    @Test fun verifiesStreamSizeAndDigestAndReportsOnlyBoundedProgress() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        copyVerifiedAsset(ByteArrayInputStream(bytes), output, asset) { progress.add(it) }
        assertTrue(bytes.contentEquals(output.toByteArray()))
        assertEquals(listOf(4L), progress)

        for (incorrect in listOf(bytes.copyOf(3), bytes.copyOf(5))) {
            val sizeError = assertThrows(UpdateException::class.java) {
                copyVerifiedAsset(ByteArrayInputStream(incorrect), ByteArrayOutputStream(), asset) {}
            }
            assertEquals(UpdateProblem.APK_SIZE, sizeError.problem)
        }
        val digestError = assertThrows(UpdateException::class.java) {
            copyVerifiedAsset(ByteArrayInputStream(byteArrayOf(4, 3, 2, 1)), ByteArrayOutputStream(), asset) {}
        }
        assertEquals(UpdateProblem.APK_DIGEST, digestError.problem)
    }

    @Test fun distinguishesHttpDeniedRateLimitedAndUnavailableResponses() {
        assertEquals(UpdateProblem.ACCESS_DENIED, httpProblem(403))
        assertEquals(UpdateProblem.RATE_LIMIT, httpProblem(403, "0"))
        assertEquals(UpdateProblem.RATE_LIMIT, httpProblem(429))
        assertEquals(UpdateProblem.UNAVAILABLE, httpProblem(404))
        assertEquals(UpdateProblem.UNAVAILABLE, httpProblem(503))
    }

    private class FakeSource(private val lookup: suspend () -> ReleaseLookup) : ReleaseSource {
        var checks = 0
        var downloads = 0

        override suspend fun latest(installed: ApkIdentity): ReleaseLookup {
            checks++
            return lookup()
        }

        override suspend fun download(asset: ReleaseAsset, destination: File, onProgress: (Long) -> Unit) {
            downloads++
        }
    }

    private class FakeSettings(automatic: Boolean = true) : ReleaseSettings {
        val values = MutableStateFlow<ReleaseUpdateSettings?>(ReleaseUpdateSettings(automatic))
        override val data = values

        override suspend fun setAutomatic(enabled: Boolean) {
            values.value = requireNotNull(values.value).copy(automatic = enabled)
        }

        override suspend fun recordCheck(atMs: Long) {
            values.value = requireNotNull(values.value).copy(lastCheckedAtMs = atMs)
        }
    }
}
