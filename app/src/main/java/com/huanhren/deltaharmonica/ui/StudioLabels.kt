package com.huanhren.deltaharmonica.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.ui.theme.ThemeMode

@StringRes internal fun StudioTab.labelRes() = when (this) {
    StudioTab.HOME -> R.string.nav_home
    StudioTab.LIBRARY -> R.string.nav_library
    StudioTab.PLAYER -> R.string.nav_player
    StudioTab.SETTINGS -> R.string.nav_settings
}

@StringRes internal fun ThemeMode.labelRes() = when (this) {
    ThemeMode.SYSTEM -> R.string.standard_system
    ThemeMode.LIGHT -> R.string.standard_light
    ThemeMode.DARK -> R.string.standard_dark
    ThemeMode.MONET_SYSTEM -> R.string.monet_system
    ThemeMode.MONET_LIGHT -> R.string.monet_light
    ThemeMode.MONET_DARK -> R.string.monet_dark
}

@StringRes internal fun HomeLayout.labelRes() = when (this) {
    HomeLayout.WORKBENCH -> R.string.workbench
    HomeLayout.LIST -> R.string.layout_list
    HomeLayout.GRID -> R.string.layout_grid
}

@StringRes internal fun StudioLanguage.labelRes() = when (this) {
    StudioLanguage.SYSTEM -> R.string.system_language
    StudioLanguage.CHINESE -> R.string.chinese
    StudioLanguage.ENGLISH -> R.string.english
}

@StringRes internal fun LauncherStyle.labelRes() = when (this) {
    LauncherStyle.WAVE -> R.string.wave_icon
    LauncherStyle.COMPASS -> R.string.compass_icon
}
