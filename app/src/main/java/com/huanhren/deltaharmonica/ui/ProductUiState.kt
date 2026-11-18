package com.huanhren.deltaharmonica.ui

import com.huanhren.deltaharmonica.data.ImportPhase
import com.huanhren.deltaharmonica.data.ImportUiState
import com.huanhren.deltaharmonica.data.SongLibraryItem
import com.huanhren.deltaharmonica.playback.PlaybackProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SongSort(val label: String) { IMPORTED("导入时间"), NAME("曲名"), RECENT("最近演奏") }

internal fun visibleSongs(songs: List<SongLibraryItem>, query: String, sort: SongSort, recent: Map<String, Long>): List<SongLibraryItem> {
    val order = when (sort) {
        SongSort.IMPORTED -> compareByDescending<SongLibraryItem> { it.createdAt }
        SongSort.NAME -> compareBy { it.displayName.lowercase(Locale.ROOT) }
        SongSort.RECENT -> compareByDescending<SongLibraryItem> { recent[it.id] ?: Long.MIN_VALUE }.thenByDescending { it.createdAt }
    }
    return songs.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
        .sortedWith(order.thenBy { it.id })
}

internal fun importStage(state: ImportUiState): String = when (state.phase) {
    ImportPhase.IDLE -> "选择文件"
    ImportPhase.READING -> "读取文件"
    ImportPhase.PREFLIGHT -> "检查文件"
    ImportPhase.PARSING -> "解析乐谱"
    ImportPhase.CONVERTING -> "转换旋律轨道"
    ImportPhase.VALIDATING -> "验证脚本"
    ImportPhase.SAVING -> "保存曲目"
    ImportPhase.COMMITTED -> if (state.active) "载入曲目" else "已加入曲库"
    ImportPhase.READY -> "选择旋律轨道"
    ImportPhase.CANCELLED -> if (state.committed) "曲目已保存" else "已取消"
    ImportPhase.FAILED -> if (state.committed) "已保存，加载失败" else "导入失败"
}

internal fun playbackFraction(progress: PlaybackProgress): Float =
    if (progress.total <= 0) 0f else (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f)

internal val PlaybackRange.label: String
    get() = when (this) {
        PlaybackRange.FIRST_8 -> "8 音"
        PlaybackRange.FIRST_16 -> "16 音"
        PlaybackRange.ALL -> "整曲"
    }

internal fun songDuration(ms: Long?): String = ms?.let {
    val seconds = (it / 1000).coerceAtLeast(0)
    String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
} ?: "时长未知"

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
internal fun songDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateFormat)
