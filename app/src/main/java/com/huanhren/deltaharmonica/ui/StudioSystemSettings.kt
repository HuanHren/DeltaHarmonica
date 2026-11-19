package com.huanhren.deltaharmonica.ui

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

internal fun Context.withStudioLocale(): Context {
    val saved = getSharedPreferences("player_ui", Context.MODE_PRIVATE).getString("language", null)
    val language = StudioLanguage.entries.firstOrNull { it.name == saved } ?: StudioLanguage.SYSTEM
    if (language == StudioLanguage.SYSTEM) return this
    val current = resources.configuration.locales
    val requested = LocaleList.forLanguageTags(language.tag)
    if (current == requested) return this
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocales(requested) })
}

internal fun Context.setStudioLocale(language: StudioLanguage) {
    if (Build.VERSION.SDK_INT >= 33) {
        getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
    }
}

internal fun Context.setLauncherStyle(style: LauncherStyle) {
    val classPrefix = "com.huanhren.deltaharmonica"
    val target = ComponentName(packageName, when (style) {
        LauncherStyle.WAVE -> "$classPrefix.WaveLauncher"
        LauncherStyle.COMPASS -> "$classPrefix.CompassLauncher"
    })
    val other = ComponentName(packageName, when (style) {
        LauncherStyle.WAVE -> "$classPrefix.CompassLauncher"
        LauncherStyle.COMPASS -> "$classPrefix.WaveLauncher"
    })
    val keepRunning = PackageManager.DONT_KILL_APP
    if (Build.VERSION.SDK_INT >= 33) {
        packageManager.setComponentEnabledSettings(listOf(
            PackageManager.ComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, keepRunning),
            PackageManager.ComponentEnabledSetting(other, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, keepRunning),
        ))
    } else {
        packageManager.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, keepRunning)
        packageManager.setComponentEnabledSetting(other, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, keepRunning)
    }
}
