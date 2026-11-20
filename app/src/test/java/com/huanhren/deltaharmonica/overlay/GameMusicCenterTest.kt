package com.huanhren.deltaharmonica.overlay

import com.huanhren.deltaharmonica.model.ScreenSize
import com.huanhren.deltaharmonica.model.ScreenPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameMusicCenterTest {
    @Test fun adjacentTrackUsesVisibleOrderAndNeverWraps() {
        val songs = listOf("a", "b", "c")
        assertEquals("c", adjacentTrack(songs, "b", 1))
        assertEquals("a", adjacentTrack(songs, "b", -1))
        assertEquals("a", adjacentTrack(songs, null, 1))
        assertNull(adjacentTrack(songs, "a", -1))
        assertNull(adjacentTrack(songs, "c", 1))
    }

    @Test fun historyTrimsCollapsesDuplicatesAndHasABound() {
        val old = (1..20).map { "query $it" }
        val updated = rememberMusicQuery(old, "  Query 5 ")
        assertEquals("Query 5", updated.first())
        assertEquals(12, updated.size)
        assertEquals(1, updated.count { it.equals("query 5", ignoreCase = true) })
        assertEquals(updated, rememberMusicQuery(updated, "  "))
    }

    @Test fun liveCenterNeverCoversAProtectedTouchOrTheStopBar() {
        val screen = ScreenSize(900, 500)
        val bar = OverlayRect(740, 0, 160, 50)
        val points = listOf(ScreenPoint(20f, 40f), ScreenPoint(850f, 450f))
        val origin = musicWindowOrigin(screen, 320, 240, points, bar)!!
        val window = OverlayRect(origin.x.toInt(), origin.y.toInt(), 320, 240)
        assertEquals(false, window.overlaps(bar))
        assertEquals(false, points.any(window::contains))
        assertNull(musicWindowOrigin(screen, 900, 500, points, bar))
    }
}
