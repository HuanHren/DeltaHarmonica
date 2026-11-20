package com.huanhren.deltaharmonica.update

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal data class ReleaseUpdateSettings(val automatic: Boolean, val lastCheckedAtMs: Long? = null)

internal interface ReleaseSettings {
    val data: Flow<ReleaseUpdateSettings?>
    suspend fun setAutomatic(enabled: Boolean)
    suspend fun recordCheck(atMs: Long)
}

private val Context.releasePreferences by preferencesDataStore("release_updates")

private class DataStoreReleaseSettings(context: Context) : ReleaseSettings {
    private val store = context.releasePreferences
    private val autoKey = booleanPreferencesKey("automatic")
    private val checkKey = longPreferencesKey("last_checked")

    override val data = store.data
        .map<Preferences, ReleaseUpdateSettings?> { ReleaseUpdateSettings(it[autoKey] ?: true, it[checkKey]) }
        .catch { failure -> if (failure is IOException) emit(null) else throw failure }

    override suspend fun setAutomatic(enabled: Boolean) {
        store.edit { it[autoKey] = enabled }
    }

    override suspend fun recordCheck(atMs: Long) {
        store.edit { it[checkKey] = atMs }
    }
}

internal sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data object Current : UpdateCheck
    data object NoRelease : UpdateCheck
    data class Failed(val reason: UpdateProblem) : UpdateCheck
    data class Available(val release: StableRelease) : UpdateCheck
}

internal sealed interface UpdateDownload {
    data object Idle : UpdateDownload
    data class InProgress(val bytes: Long) : UpdateDownload
    data class Ready(val file: File) : UpdateDownload
    data class Failed(val reason: UpdateProblem) : UpdateDownload
}

internal class ReleaseUpdateController(
    private val scope: CoroutineScope,
    private val store: ReleaseSettings,
    private val source: ReleaseSource,
    private val apk: ApkStore,
    val enabled: Boolean = true,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val settings = store.data.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
    private val started = AtomicBoolean()
    private val operation = Mutex()

    private val mutableCheck = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    val check = mutableCheck.asStateFlow()
    private val mutableDownload = MutableStateFlow<UpdateDownload>(UpdateDownload.Idle)
    val download = mutableDownload.asStateFlow()
    private val mutableOffer = MutableStateFlow<StableRelease?>(null)
    val offer = mutableOffer.asStateFlow()
    private val mutableSettingsError = MutableStateFlow(false)
    val settingsError = mutableSettingsError.asStateFlow()

    fun startOnce() {
        if (!enabled || !started.compareAndSet(false, true)) return
        scope.launch {
            if (store.data.first()?.automatic == true) check(false)
        }
    }

    fun checkManually() {
        if (enabled) scope.launch { check(true) }
    }

    fun dismissOffer() {
        mutableOffer.value = null
    }

    fun setAutomatic(value: Boolean) {
        if (!enabled) return
        scope.launch {
            mutableSettingsError.value = false
            try {
                store.setAutomatic(value)
                if (!value) dismissOffer()
            } catch (_: IOException) {
                mutableSettingsError.value = true
            }
        }
    }

    fun download() {
        if (!enabled) return
        scope.launch {
            if (!operation.tryLock()) return@launch
            try {
                val release = (check.value as? UpdateCheck.Available)?.release ?: return@launch
                val previous = download.value
                if (previous is UpdateDownload.Ready && previous.file.isFile) return@launch
                mutableDownload.value = UpdateDownload.InProgress(0)
                try {
                    val file = apk.downloadAndVerify(release, source) { bytes ->
                        mutableDownload.value = UpdateDownload.InProgress(bytes)
                    }
                    mutableDownload.value = UpdateDownload.Ready(file)
                } catch (failure: Exception) {
                    mutableDownload.value = UpdateDownload.Failed(failure.problem(UpdateProblem.APK_INVALID))
                }
            } finally {
                operation.unlock()
            }
        }
    }

    private suspend fun check(manual: Boolean) {
        if (!operation.tryLock()) return
        try {
            val previous = check.value as? UpdateCheck.Available
            mutableCheck.value = UpdateCheck.Checking
            val result = try {
                when (val lookup = source.latest(apk.installed())) {
                    is ReleaseLookup.Available -> UpdateCheck.Available(lookup.release)
                    ReleaseLookup.Current -> UpdateCheck.Current
                    ReleaseLookup.NoRelease -> UpdateCheck.NoRelease
                }
            } catch (failure: Exception) {
                UpdateCheck.Failed(failure.problem(UpdateProblem.NETWORK))
            }
            if (previous == null || previous != result) mutableDownload.value = UpdateDownload.Idle
            mutableCheck.value = result
            try {
                store.recordCheck(now())
            } catch (_: IOException) {
                mutableSettingsError.value = true
            }
            if (!manual && result is UpdateCheck.Available && store.data.first()?.automatic == true) {
                mutableOffer.value = result.release
            }
        } finally {
            operation.unlock()
        }
    }

    private fun Exception.problem(fallback: UpdateProblem): UpdateProblem {
        if (this is CancellationException) throw this
        return (this as? UpdateException)?.problem ?: fallback
    }
}

private object ProcessUpdates {
    private var controller: ReleaseUpdateController? = null

    @Synchronized
    fun controller(context: Context): ReleaseUpdateController {
        val app = context.applicationContext
        return controller ?: ReleaseUpdateController(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            DataStoreReleaseSettings(app), GitHubReleaseSource(), AndroidApkStore(app),
            enabled = app.packageName == OFFICIAL_PACKAGE &&
                (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0,
        ).also { controller = it }
    }
}

internal val Context.releaseUpdates: ReleaseUpdateController
    get() = ProcessUpdates.controller(this)
