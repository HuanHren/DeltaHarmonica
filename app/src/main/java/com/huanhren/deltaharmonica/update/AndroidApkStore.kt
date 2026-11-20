package com.huanhren.deltaharmonica.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal interface ApkStore {
    suspend fun installed(): ApkIdentity
    suspend fun downloadAndVerify(release: StableRelease, source: ReleaseSource, onProgress: (Long) -> Unit): File
}

internal class AndroidApkStore(private val context: Context) : ApkStore {
    private val packageManager = context.packageManager

    override suspend fun installed(): ApkIdentity = withContext(Dispatchers.IO) { installedIdentity() }

    override suspend fun downloadAndVerify(
        release: StableRelease,
        source: ReleaseSource,
        onProgress: (Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = context.cacheDir.resolve("release_updates")
        var verified = false
        // The staged file is outside the path shared with the installer.
        val staged = tempFile(context.cacheDir)
        var finalFile: File? = null
        try {
            source.download(release.asset, staged, onProgress)
            val installed = installedIdentity()
            val candidate = packageManager.getPackageArchiveInfo(staged.path, signingFlags())?.identity()
                ?: throw UpdateException(UpdateProblem.APK_INVALID)
            validateApkIdentity(installed, candidate, release)?.let { throw UpdateException(it) }
            if (!directory.mkdirs() && !directory.isDirectory) throw UpdateException(UpdateProblem.STORAGE)
            finalFile = tempFile(directory)
            if (!staged.renameTo(finalFile)) throw UpdateException(UpdateProblem.STORAGE)
            verified = true
            finalFile
        } finally {
            staged.delete()
            if (!verified) finalFile?.delete()
        }
    }

    private fun installedIdentity(): ApkIdentity {
        val info = try {
            packageManager.getPackageInfo(context.packageName, signingFlags())
        } catch (missing: PackageManager.NameNotFoundException) {
            throw UpdateException(UpdateProblem.APK_PACKAGE, missing)
        }
        return info.identity().also {
            if (it.packageName != OFFICIAL_PACKAGE) throw UpdateException(UpdateProblem.APK_PACKAGE)
        }
    }

    private fun tempFile(directory: File): File = try {
        File.createTempFile("release-", ".apk", directory)
    } catch (failure: IOException) {
        throw UpdateException(UpdateProblem.STORAGE, failure)
    }

    @Suppress("DEPRECATION")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun PackageInfo.identity(): ApkIdentity {
        val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
        val hasher = MessageDigest.getInstance("SHA-256")
        return ApkIdentity(packageName,
            if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(),
            versionName.orEmpty(),
            certificates.orEmpty().mapTo(mutableSetOf()) { hasher.digest(it.toByteArray()).hex() })
    }
}

class UpdateFileProvider : FileProvider()

internal enum class InstallerLaunch { OPENED, PERMISSION_SETTINGS, FAILED }

internal fun openVerifiedInstaller(activity: Activity, file: File): InstallerLaunch {
    if (!file.isFile) return InstallerLaunch.FAILED
    return try {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            val request = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, ("package:" + activity.packageName).toUri())
            if (!activity.startSystemActivity(request)) return InstallerLaunch.FAILED
            return InstallerLaunch.PERMISSION_SETTINGS
        }
        val content = FileProvider.getUriForFile(activity, activity.packageName + ".release-apk", file)
        val request = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(content, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (activity.startSystemActivity(request)) InstallerLaunch.OPENED else InstallerLaunch.FAILED
    } catch (_: ActivityNotFoundException) {
        InstallerLaunch.FAILED
    } catch (_: SecurityException) {
        InstallerLaunch.FAILED
    } catch (_: IllegalArgumentException) {
        InstallerLaunch.FAILED
    }
}

@Suppress("DEPRECATION")
private fun Activity.startSystemActivity(intent: Intent): Boolean {
    val target = packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)
        .firstOrNull()?.activityInfo ?: return false
    startActivity(intent.setClassName(target.packageName, target.name))
    return true
}
