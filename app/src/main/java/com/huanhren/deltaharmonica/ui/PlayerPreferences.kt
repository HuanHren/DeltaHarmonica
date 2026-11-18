package com.huanhren.deltaharmonica.ui

import android.content.Context
import com.huanhren.deltaharmonica.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerPreferences(context: Context) {
    private val storage = context.getSharedPreferences("player_ui", Context.MODE_PRIVATE)
    private val mutableTheme = MutableStateFlow(ThemeMode.entries.firstOrNull {
        it.name == storage.getString("theme", null)
    } ?: ThemeMode.SYSTEM)
    val theme = mutableTheme.asStateFlow()
    private val mutableSpeed = MutableStateFlow(if (storage.getBoolean("fast", false)) 2.0 else 1.0)
    val speed = mutableSpeed.asStateFlow()
    private val mutableVolumeStop = MutableStateFlow(storage.getBoolean("volume_stop", true))
    val volumeStop = mutableVolumeStop.asStateFlow()
    private val mutableRecent = MutableStateFlow(storage.all.mapNotNull { (key, value) ->
        if (key.startsWith(RECENT_PREFIX) && value is Long) key.removePrefix(RECENT_PREFIX) to value else null
    }.toMap())
    val recent = mutableRecent.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        storage.edit().putString("theme", mode.name).apply()
        mutableTheme.value = mode
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
