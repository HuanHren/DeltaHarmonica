package com.huanhren.deltaharmonica.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ReleaseProtocolTest {
    private val installed = ApkIdentity("com.huanhren.deltaharmonica", 5, "2.3.0", setOf("original"))
    private val version = "v2.4.0"
    private val fileName = "DeltaHarmonica-v2.4.0-release.apk"
    private val goodUrl = "https://github.com/HuanHren/DeltaHarmonica/releases/download/$version/$fileName"
    private val hash = "a".repeat(64)

    @Test fun parsesOfficialStableAsset() {
        val available = ReleaseProtocol.parse(response(), installed) as ReleaseLookup.Available
        assertEquals("2.4.0", available.release.version.toString())
        assertEquals(101L, available.release.asset.size)
        assertEquals(hash, available.release.asset.sha256)
        assertEquals(URI(goodUrl), available.release.asset.url)
        assertNull(available.release.declaredCode)
        assertEquals("Bug fixes", available.release.notes)
    }

    @Test fun sameOrOlderTagWithoutCodeDoesNotFetchOrAnnounceAnApk() {
        for (tag in listOf("v2.3.0", "v2.2.999")) {
            assertEquals(ReleaseLookup.Current, ReleaseProtocol.parse(response(tag = tag), installed))
        }
    }

    @Test fun prefersDeclaredVersionCodeAndComparesNumericVersionComponents() {
        assertEquals(
            ReleaseLookup.Current,
            ReleaseProtocol.parse(response(notes = "Bug fixes\\nversionCode: 5"), installed),
        )
        val lowerName = installed.copy(versionName = "2.5.0")
        val newerCode = ReleaseProtocol.parse(response(notes = "versionCode: 6"), lowerName) as ReleaseLookup.Available
        assertEquals(6L, newerCode.release.declaredCode)
        assertTrue(requireNotNull(StableVersion.parse("v2.10.0")) > requireNotNull(StableVersion.parse("v2.9.9")))
    }

    @Test fun discardsUnpublishedPrereleasesAndUnverifiableAssets() {
        assertEquals(ReleaseLookup.Current, ReleaseProtocol.parse(response(prerelease = true), installed))
        assertEquals(ReleaseLookup.Current, ReleaseProtocol.parse(response(draft = true), installed))
        assertEquals(ReleaseLookup.Current, ReleaseProtocol.parse(response(tag = "v2.4.0-beta.1"), installed))
        for (badUrl in listOf(goodUrl.replace("https", "http"), "$goodUrl?extra=x",
            goodUrl.replace("github.com", "github.com.example.org"), goodUrl.replace("HuanHren", "Other"),
            goodUrl.replace("github.com", "someone@github.com"))) {
            assertThrows(UpdateException::class.java) { ReleaseProtocol.parse(response(url = badUrl), installed) }
        }
        assertThrows(UpdateException::class.java) { ReleaseProtocol.parse(response(digest = "sha256:bad"), installed) }
        assertThrows(UpdateException::class.java) { ReleaseProtocol.parse(response(notes = "versionCode: 6\\nversionCode: 7"), installed) }
    }

    @Test fun apkMustMatchPackageSignerAndReleaseAndHaveHigherCode() {
        val available = ReleaseProtocol.parse(response(notes = "versionCode: 6"), installed) as ReleaseLookup.Available
        val candidate = installed.copy(versionCode = 6, versionName = "2.4.0")
        assertNull(validateApkIdentity(installed, candidate, available.release))
        assertEquals(UpdateProblem.APK_PACKAGE, validateApkIdentity(installed, candidate.copy(packageName = "elsewhere"), available.release))
        assertEquals(UpdateProblem.APK_SIGNATURE, validateApkIdentity(installed, candidate.copy(signers = setOf("another")), available.release))
        assertEquals(UpdateProblem.APK_VERSION, validateApkIdentity(installed, candidate.copy(versionCode = 5), available.release))
        assertEquals(UpdateProblem.APK_VERSION, validateApkIdentity(installed, candidate.copy(versionCode = 7), available.release))
        assertEquals(UpdateProblem.APK_VERSION, validateApkIdentity(installed, candidate.copy(versionName = "2.4.1"), available.release))
        assertNotNull(validateApkIdentity(installed.copy(signers = emptySet()), candidate.copy(signers = emptySet()), available.release))
    }

    @Test fun redirectRequiresHttpsAndExactGitHubDeliveryHost() {
        assertTrue(isOfficialAssetDelivery(URI("https://release-assets.githubusercontent.com/example?signature=ignored")))
        assertTrue(isOfficialAssetDelivery(URI("https://objects.githubusercontent.com/example")))
        assertFalse(isOfficialAssetDelivery(URI("http://release-assets.githubusercontent.com/example")))
        assertFalse(isOfficialAssetDelivery(URI("https://attacker@release-assets.githubusercontent.com/example")))
        assertFalse(isOfficialAssetDelivery(URI("https://release-assets.githubusercontent.com.example.org/example")))
        assertFalse(isOfficialAssetDelivery(URI("https://release-assets.githubusercontent.com:8080/example")))
        assertFalse(isOfficialAssetDelivery(URI("https://github.com/HuanHren/other.apk")))
    }

    private fun response(
        tag: String = version,
        draft: Boolean = false,
        prerelease: Boolean = false,
        notes: String = "Bug fixes",
        url: String = goodUrl,
        digest: String = "sha256:$hash",
    ) = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "published_at": "2026-11-19T10:00:00Z",
          "body": "$notes",
          "assets": [
            {"name":"$fileName","browser_download_url":"$url","size":101,"state":"uploaded","digest":"$digest"}
          ]
        }
    """.trimIndent()
}
