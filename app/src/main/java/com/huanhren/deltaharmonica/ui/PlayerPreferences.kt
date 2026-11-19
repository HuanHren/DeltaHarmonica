package com.huanhren.deltaharmonica.ui

import android.content.Context
import com.huanhren.deltaharmonica.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerPreferences(context: Context) {
    private val storage = context.getSharedPreferences("player_ui", Context.MODE_PRIVATE)
    private val mutableAppearance = MutableStateFlow(
        StudioAppearance(
            theme = storedEnum("theme", ThemeMode.SYSTEM),
            seed = storage.getString("seed", null)?.let { value -> StudioSeed.entries.firstOrNull { it.name == value } },
            layout = storedEnum("home_layout", HomeLayout.WORKBENCH),
            navigation = StudioTab.entries.filter {
                it.name in storage.getStringSet("studio_tabs", StudioTab.primary.map(StudioTab::name).toSet()).orEmpty()
            }.toSet(),
            showLabels = storage.getBoolean("navigation_labels", true),
            hideOnScroll = storage.getBoolean("hide_navigation_on_scroll", true),
            blur = storage.getBoolean("studio_blur", true),
            glass = storage.getBoolean("studio_glass", true),
            reducedEffects = storage.getBoolean("studio_reduce_effects", false),
            predictiveBack = storage.getBoolean("predictive_back", true),
            language = storedEnum("language", StudioLanguage.SYSTEM),
            launcher = storedEnum("launcher", LauncherStyle.WAVE),
        ).resize(storage.getFloat("studio_scale", 1f)),
    )
    val appearance = mutableAppearance.asStateFlow()

    private inline fun <reified T : Enum<T>> storedEnum(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == storage.getString(key, null) } ?: default
    private val mutableRange = MutableStateFlow(storedEnum("default_range", PlaybackRange.FIRST_8))
    val range = mutableRange.asStateFlow()
    private val mutableSpeed = MutableStateFlow(if (storage.getBoolean("fast", false)) 2.0 else 1.0)
    val speed = mutableSpeed.asStateFlow()
    private val mutableVolumeStop = MutableStateFlow(storage.getBoolean("volume_stop", true))
    val volumeStop = mutableVolumeStop.asStateFlow()
    private val mutableVolumePause = MutableStateFlow(storage.getBoolean("accessibility_volume_pause", false))
    val volumePause = mutableVolumePause.asStateFlow()
    private val mutableRecent = MutableStateFlow(storage.all.mapNotNull { (key, value) ->
        if (key.startsWith(RECENT_PREFIX) && value is Long) key.removePrefix(RECENT_PREFIX) to value else null
    }.toMap())
    val recent = mutableRecent.asStateFlow()

    fun setTheme(mode: ThemeMode) = updateAppearance { copy(theme = mode) }

    fun updateAppearance(change: StudioAppearance.() -> StudioAppearance) {
        val previous = mutableAppearance.value
        val next = previous.change()
        if (previous == next) return
        storage.edit()
            .putString("theme", next.theme.name)
            .putString("seed", next.seed?.name)
            .putString("home_layout", next.layout.name)
            .putStringSet("studio_tabs", next.tabs.map(StudioTab::name).toSet())
            .putBoolean("navigation_labels", next.showLabels)
            .putBoolean("hide_navigation_on_scroll", next.hideOnScroll)
            .putBoolean("studio_blur", next.blur)
            .putBoolean("studio_glass", next.glass)
            .putBoolean("studio_reduce_effects", next.reducedEffects)
            .putFloat("studio_scale", next.scale)
            .putBoolean("predictive_back", next.predictiveBack)
            .putString("language", next.language.name)
            .putString("launcher", next.launcher.name)
            .apply()
        mutableAppearance.value = next
    }
    fun setRange(range: PlaybackRange) {
        storage.edit().putString("default_range", range.name).apply()
        mutableRange.value = range
    }

    fun setSpeed(speed: Double) {
        require(speed == 1.0 || speed == 2.0)
        storage.edit().putBoolean("fast", speed == 2.0).apply()
        mutableSpeed.value = speed
    }

    fun setVolumeStop(enabled: Boolean) {
        storage.edit().putBoolean("volume_stop", enabled).apply()
        mutableVolumeStop.value = enabled
    }

    fun setVolumePause(enabled: Boolean) {
        storage.edit().putBoolean("accessibility_volume_pause", enabled).apply()
        mutableVolumePause.value = enabled
    }

    fun recordSession(songId: String, now: Long = System.currentTimeMillis()) {
        val previous = mutableRecent.value
        val updated = (previous + (songId to now)).entries.sortedByDescending { it.value }
            .take(50).associate { it.key to it.value }
        storage.edit().apply {
            (previous.keys - updated.keys).forEach { remove(RECENT_PREFIX + it) }
            putLong(RECENT_PREFIX + songId, now)
        }.apply()
        mutableRecent.value = updated
    }

    fun removeHistory(songId: String) {
        storage.edit().remove(RECENT_PREFIX + songId).apply()
        mutableRecent.value = mutableRecent.value - songId
    }

    companion object { private const val RECENT_PREFIX = "played_" }
}
