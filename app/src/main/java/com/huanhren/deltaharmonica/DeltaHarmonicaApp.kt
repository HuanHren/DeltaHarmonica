package com.huanhren.deltaharmonica

import android.app.Application
import android.os.Build
import android.os.Process
import com.huanhren.deltaharmonica.data.LayoutRepository
import com.huanhren.deltaharmonica.data.layoutDataStore
import com.huanhren.deltaharmonica.data.SongLibraryRepository
import com.huanhren.deltaharmonica.input.AccessibilityBackend
import com.huanhren.deltaharmonica.input.RootBackend
import com.huanhren.deltaharmonica.input.RootCommandRunner
import com.huanhren.deltaharmonica.input.ShizukuBackend
import com.huanhren.deltaharmonica.input.SuCommandRunner
import com.huanhren.deltaharmonica.input.displaySnapshot

import com.huanhren.deltaharmonica.ui.AppViewModel
import com.huanhren.deltaharmonica.ui.PlayerPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore

open class DeltaHarmonicaApp : Application() {
    protected open fun createLayouts() = LayoutRepository(layoutDataStore)
    protected open fun createSongs() = SongLibraryRepository(filesDir.resolve("song_library"))
    protected open fun createRootRunner(): RootCommandRunner = SuCommandRunner()

    open val rootUiAllowed = true
    val preferences by lazy { PlayerPreferences(this) }
    private val appModels = ViewModelStore()
    val model: AppViewModel by lazy {
        ViewModelProvider.create(appModels, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(layouts, rootBackend, accessibilityBackend, backends, songs, contentResolver,
                    preferences, rootUiAllowed) as T
        })[AppViewModel::class]
    }

    val layouts by lazy { createLayouts() }
    val songs by lazy { createSongs() }
    val accessibilityBackend by lazy { AccessibilityBackend(this) }
    val rootBackend by lazy {
        val apk = applicationInfo.sourceDir.takeIf { Build.VERSION.SDK_INT >= 34 }
        RootBackend(createRootRunner(), apk, Process.myUid()) { displaySnapshot(this).size }
    }
    val backends by lazy { listOf(rootBackend, accessibilityBackend, ShizukuBackend()) }
}
