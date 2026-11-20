package com.huanhren.deltaharmonica.overlay

import android.content.Context
import androidx.core.content.edit
import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.model.ScreenSize
import com.huanhren.deltaharmonica.online.OnlineEntry
import kotlinx.serialization.json.Json
import kotlin.math.floor

internal fun adjacentTrack(ids: List<String>, current: String?, step: Int): String? {
    require(step == -1 || step == 1)
    val index = ids.indexOf(current)
    return ids.getOrNull(index + step)
}

internal fun rememberMusicQuery(history: List<String>, query: String): List<String> {
    val value = query.trim().take(128)
    if (value.isEmpty()) return history
    return (listOf(value) + history.filterNot { it.equals(value, ignoreCase = true) }).take(12)
}

internal class MusicCenterPreferences(context: Context) {
    private val storage = context.getSharedPreferences("overlay_music", Context.MODE_PRIVATE)

    var searches = read<List<String>>("searches").orEmpty()
        private set
    var favorites = read<List<OnlineEntry>>("favorites").orEmpty().associateBy(OnlineEntry::id)
        private set

    fun remember(query: String) {
        val next = rememberMusicQuery(searches, query)
        if (next != searches) {
            searches = next
            storage.edit { putString("searches", Json.encodeToString(next)) }
        }
    }

    fun clearSearches() {
        searches = emptyList()
        storage.edit { remove("searches") }
    }

    fun toggleFavorite(entry: OnlineEntry) {
        favorites = if (entry.id in favorites) favorites - entry.id else favorites + (entry.id to entry)
        storage.edit { putString("favorites", Json.encodeToString(favorites.values.toList())) }
    }

    private inline fun <reified T> read(key: String): T? = runCatching {
        storage.getString(key, null)?.let { Json.decodeFromString<T>(it) }
    }.getOrNull()
}

internal data class OverlayRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height

    fun contains(point: ScreenPoint) = point.x >= x && point.x < right && point.y >= y && point.y < bottom
    fun overlaps(other: OverlayRect) = x < other.right && other.x < right && y < other.bottom && other.y < bottom
}

/** Screen-space candidates where a window becomes flush with a protected point or the control bar. */
internal fun musicWindowOrigin(
    screen: ScreenSize,
    width: Int,
    height: Int,
    protected: List<ScreenPoint>,
    bar: OverlayRect,
): ScreenPoint? {
    if (width <= 0 || height <= 0 || width > screen.width || height > screen.height) return null
    val right = screen.width - width
    val bottom = screen.height - height
    val xs = (listOf(right, 0, bar.right, bar.x - width) + protected.flatMap {
        val x = floor(it.x).toInt()
        listOf(x + 1, x - width)
    }).filter { it in 0..right }.distinct()
    val ys = (listOf(0, bottom, bar.bottom, bar.y - height) + protected.flatMap {
        val y = floor(it.y).toInt()
        listOf(y + 1, y - height)
    }).filter { it in 0..bottom }.distinct()
    for (x in xs) for (y in ys) {
        val window = OverlayRect(x, y, width, height)
        if (!window.overlaps(bar) && protected.none(window::contains)) return ScreenPoint(x.toFloat(), y.toFloat())
    }
    return null
}
