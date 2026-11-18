package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.model.ScreenPoint
import org.junit.Assert.*
import org.junit.Test

class CombinedGestureTest {
    @Test fun controlsOverlapExactlyOneNote() {
        val gesture=CombinedGesture.create(ScreenPoint(500f,500f), 500, listOf(ScreenPoint(200f,200f),ScreenPoint(300f,200f)))
        assertEquals(3,gesture.strokes.size)
        assertEquals(1,gesture.strokes.count { it.isNote })
        assertTrue(gesture.strokes.filter { !it.isNote }.all { it.startMs < gesture.strokes.last().startMs && it.startMs+it.durationMs > gesture.strokes.last().startMs })
    }
    @Test fun rejectsOverlappingPointsAndInvalidDuration() {
        try { CombinedGesture.create(ScreenPoint(1f,1f),0,emptyList()); fail() } catch (_: IllegalArgumentException) { }
        try { CombinedGesture.create(ScreenPoint(1f,1f),100,listOf(ScreenPoint(1f,1f))); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun configurableLeadKeepsControlAndNoteOverlapping() {
        val gesture=CombinedGesture.create(ScreenPoint(500f,500f),300,listOf(ScreenPoint(100f,100f)),leadMs=20)
        assertEquals(20L,gesture.strokes.last().startMs)
        assertTrue(gesture.strokes.first().durationMs > gesture.strokes.last().startMs)
        try { CombinedGesture.create(ScreenPoint(1f,1f),100,listOf(ScreenPoint(2f,2f)),leadMs=80); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun longNoteRetainsItsDurationWithinAndroidGestureLimit() {
        val gesture=CombinedGesture.create(ScreenPoint(500f,500f),5000,emptyList())
        assertEquals(5000L,gesture.strokes.single().durationMs)
        assertThrows(IllegalArgumentException::class.java) { CombinedGesture.create(ScreenPoint(1f,1f),60000,listOf(ScreenPoint(2f,2f)),leadMs=40) }
    }
}
