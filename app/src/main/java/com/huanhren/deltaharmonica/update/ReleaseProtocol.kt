package com.huanhren.deltaharmonica.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.time.Instant

internal const val OFFICIAL_PACKAGE = "com.huanhren.deltaharmonica"
internal const val LATEST_RELEASE_URL = "https://api.github.com/repos/HuanHren/DeltaHarmonica/releases/latest"
internal const val MAX_APK_BYTES = 128L * 1024 * 1024

internal enum class UpdateProblem {
    NETWORK, ACCESS_DENIED, RATE_LIMIT, UNAVAILABLE, RELEASE_METADATA, APK_SIZE,
    APK_DIGEST, APK_INVALID, APK_PACKAGE, APK_SIGNATURE, APK_VERSION, STORAGE,
}

internal class UpdateException(val problem: UpdateProblem, cause: Throwable? = null) :
    Exception(problem.name, cause)

internal data class StableVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int = compareValuesBy(this, other,
        StableVersion::major, StableVersion::minor, StableVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("""v?(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})""")

        fun parse(value: String): StableVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val (major, minor, patch) = match.destructured
            return StableVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

internal data class ReleaseAsset(val url: URI, val size: Long, val sha256: String)

internal data class StableRelease(
    val tag: String,
    val version: StableVersion,
    val declaredCode: Long?,
    val published: Instant,
    val notes: String,
    val asset: ReleaseAsset,
)

internal sealed interface ReleaseLookup {
    data object Current : ReleaseLookup
    data object NoRelease : ReleaseLookup
    data class Available(val release: StableRelease) : ReleaseLookup
}

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signers: Set<String>,
)

internal object ReleaseProtocol {
    private val digest = Regex("sha256:([0-9a-fA-F]{64})")
    private val codeLine = Regex("""(?i)^[ \t]*versionCode[ \t]*[:=][ \t]*(\d+)[ \t]*$""")
    private val codePrefix = Regex("""(?i)^[ \t]*versionCode[ \t]*[:=]""")

    fun parse(raw: String, installed: ApkIdentity): ReleaseLookup {
        val response = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (invalid: IllegalArgumentException) {
            throw UpdateException(UpdateProblem.RELEASE_METADATA, invalid)
        } ?: invalid()
        val draft = response.boolean("draft") ?: invalid()
        val prerelease = response.boolean("prerelease") ?: invalid()
        if (draft || prerelease) return ReleaseLookup.Current

        val tag = response.string("tag_name") ?: invalid()
        val version = StableVersion.parse(tag) ?: return ReleaseLookup.Current
        val publishedAt = response.string("published_at") ?: return ReleaseLookup.NoRelease
        val published = runCatching { Instant.parse(publishedAt) }.getOrNull() ?: invalid()
        val notes = response.string("body").orEmpty()
        val declarations = notes.lineSequence().filter { codePrefix.containsMatchIn(it) }.take(2).toList()
        val code = if (declarations.isEmpty()) null else {
            val line = declarations.singleOrNull() ?: invalid()
            codeLine.matchEntire(line)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 } ?: invalid()
        }
        if (code != null) {
            if (code <= installed.versionCode) return ReleaseLookup.Current
        } else if (StableVersion.parse(installed.versionName)?.let { version <= it } == true) {
            return ReleaseLookup.Current
        }

        val name = "DeltaHarmonica-v$version-release.apk"
        val expectedUrl = "https://github.com/HuanHren/DeltaHarmonica/releases/download/$tag/$name"
        val assets = response["assets"] as? JsonArray ?: invalid()
        val asset = assets.filterIsInstance<JsonObject>()
            .singleOrNull { it.string("name") == name } ?: invalid()
        if (asset.string("state") != "uploaded" || asset.string("browser_download_url") != expectedUrl) invalid()
        val sha256 = digest.matchEntire(asset.string("digest").orEmpty())?.groupValues?.get(1)?.lowercase() ?: invalid()
        val size = (asset["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?.takeIf { it in 1..MAX_APK_BYTES } ?: invalid()
        val release = StableRelease(tag, version, code, published, notes.trim().take(12_000),
            ReleaseAsset(URI(expectedUrl), size, sha256))
        return ReleaseLookup.Available(release)
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun invalid(): Nothing = throw UpdateException(UpdateProblem.RELEASE_METADATA)
}

internal fun validateApkIdentity(installed: ApkIdentity, candidate: ApkIdentity, release: StableRelease): UpdateProblem? =
    when {
        installed.packageName != OFFICIAL_PACKAGE || candidate.packageName != installed.packageName -> UpdateProblem.APK_PACKAGE
        installed.signers.isEmpty() || candidate.signers != installed.signers -> UpdateProblem.APK_SIGNATURE
        candidate.versionCode <= installed.versionCode ||
            release.declaredCode?.let { candidate.versionCode != it } == true ||
            StableVersion.parse(candidate.versionName) != release.version -> UpdateProblem.APK_VERSION
        else -> null
    }

internal fun isOfficialAssetDelivery(uri: URI): Boolean =
    uri.scheme == "https" && uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com") &&
        uri.userInfo == null && uri.port == -1 && uri.fragment == null
