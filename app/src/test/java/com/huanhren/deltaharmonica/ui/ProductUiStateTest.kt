package com.huanhren.deltaharmonica.ui

import com.huanhren.deltaharmonica.data.ImportPhase
import com.huanhren.deltaharmonica.data.ImportUiState
import com.huanhren.deltaharmonica.data.SongLibraryItem
import com.huanhren.deltaharmonica.data.SongSourceType
import com.huanhren.deltaharmonica.playback.PlaybackProgress
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import org.junit.Assert.*
import org.junit.Test

class ProductUiStateTest {
    private fun song(id: String, name: String, date: Long) = SongLibraryItem(
        id, name, name, SongSourceType.MIDI, date, date, "original", "script.json",
        12, 1300, 0, null, null, emptyList(), contentHash = "", scriptHash = "",
    )

    @Test fun filtersNamesIgnoringCaseAndSortsByRecentSession() {
        val songs = listOf(song("a", "Evening Song", 9), song("b", "SONG", 2), song("c", "Quiet", 6))
        val recent = mapOf("b" to 10L, "c" to 20L)
        assertEquals(listOf("b", "a"), visibleSongs(songs, "  sOnG ", SongSort.RECENT, recent).map { it.id })
        assertEquals(listOf("a", "c", "b"), visibleSongs(songs, "", SongSort.IMPORTED, recent).map { it.id })
    }

    @Test fun importDistinguishesPublishedFailureFromUnpublishedFailure() {
        assertEquals("已保存，加载失败", importStage(ImportUiState(phase = ImportPhase.FAILED, committed = true)))
        assertEquals("导入失败", importStage(ImportUiState(phase = ImportPhase.FAILED)))
        assertEquals("载入曲目", importStage(ImportUiState(phase = ImportPhase.COMMITTED, active = true)))
    }

    @Test fun completedPhasePreservesPartialProgressAfterStop() {
        assertEquals(.25f, playbackFraction(PlaybackProgress(16, 4, phase = PlaybackStatus.COMPLETED)), 0f)
        assertEquals(0f, playbackFraction(PlaybackProgress()), 0f)
    }
}
