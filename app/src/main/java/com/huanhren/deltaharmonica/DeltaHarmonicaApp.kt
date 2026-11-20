package com.huanhren.deltaharmonica

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PatternMatcher
import android.os.Process
import androidx.core.content.ContextCompat
import com.huanhren.deltaharmonica.data.LayoutRepository
import com.huanhren.deltaharmonica.data.layoutDataStore
import com.huanhren.deltaharmonica.data.SongLibraryRepository
import com.huanhren.deltaharmonica.input.AccessibilityBackend
import com.huanhren.deltaharmonica.input.AndroidRootAuthorization
import com.huanhren.deltaharmonica.input.RootBackend
import com.huanhren.deltaharmonica.input.RootCommandRunner
import com.huanhren.deltaharmonica.input.RootManagerKind
import com.huanhren.deltaharmonica.input.RootPermissionManager
import com.huanhren.deltaharmonica.input.ShizukuBackend
import com.huanhren.deltaharmonica.input.SuCommandRunner
import com.huanhren.deltaharmonica.input.displaySnapshot
import com.huanhren.deltaharmonica.online.OnlineLibraryRepository

import com.huanhren.deltaharmonica.ui.AppViewModel
import com.huanhren.deltaharmonica.ui.PlayerPreferences
import com.huanhren.deltaharmonica.update.releaseUpdates
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class DeltaHarmonicaApp : Application() {
    protected open fun createLayouts() = LayoutRepository(layoutDataStore)
    protected open fun createSongs() = SongLibraryRepository(filesDir.resolve("song_library"))
    protected open fun createOnlineLibrary() = OnlineLibraryRepository(cacheDir.resolve("online_library"))
    protected open fun createRootRunner(): RootCommandRunner = SuCommandRunner()

    open val rootUiAllowed = true
    val preferences by lazy { PlayerPreferences(this) }
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (rootUiAllowed) {
            processScope.launch { rootBackend.authorization.initialize() }
            observeRootManagers()
        }
        releaseUpdates.startOnce()
    }

    private fun observeRootManagers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
            RootManagerKind.entries.forEach { addDataSchemeSpecificPart(it.packageName, PatternMatcher.PATTERN_LITERAL) }
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                processScope.launch { rootBackend.authorization.refreshEnvironment() }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val appModels = ViewModelStore()
    val model: AppViewModel by lazy {
        ViewModelProvider.create(appModels, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(layouts, rootBackend, accessibilityBackend, backends, songs, contentResolver,
                    preferences, rootUiAllowed, onlineLibrary) as T
        })[AppViewModel::class]
    }

    val layouts by lazy { createLayouts() }
    val songs by lazy { createSongs() }
    val onlineLibrary by lazy { createOnlineLibrary() }
    val accessibilityBackend by lazy { AccessibilityBackend(this) }
    val rootBackend by lazy {
        val apk = applicationInfo.sourceDir.takeIf { Build.VERSION.SDK_INT >= 34 }
        val runner = createRootRunner()
        val persistence = AndroidRootAuthorization(this)
        val authorization = RootPermissionManager(runner, persistence::detect, persistence, allowed = rootUiAllowed)
        RootBackend(runner, apk, Process.myUid(), authorization) { displaySnapshot(this).size }
    }
    val backends by lazy { listOf(rootBackend, accessibilityBackend, ShizukuBackend()) }
}
