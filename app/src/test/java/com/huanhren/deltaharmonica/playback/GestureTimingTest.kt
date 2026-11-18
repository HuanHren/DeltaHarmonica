package com.huanhren.deltaharmonica.playback

import org.junit.Assert.*
import org.junit.Test

class GestureTimingTest {
    @Test fun reportsDispatchAndCallbackSeparatelyFromPlannedDuration() {
        val timing=ActualTiming(dispatchMs=120,callbackMs=455,noteOffsetMs=40,plannedDurationMs=300,result="系统报告完成")
        assertEquals(335L,timing.callbackLatencyMs)
        assertEquals(300L,timing.plannedDurationMs)
        assertEquals(40L,timing.noteOffsetMs)
        assertEquals("系统报告完成",timing.result)
        assertNull(ActualTiming(dispatchMs=120,callbackMs=null,noteOffsetMs=0,plannedDurationMs=300).callbackLatencyMs)
    }
    @Test fun loadingAnotherScoreClearsPreviousTimingAndProgress() {
        PlaybackPreparation.actualTimings[0]=ActualTiming(12,150,0,120)
        PlaybackPreparation.progress.value=PlaybackProgress(8,1,"key-1","完成")
        PlaybackPreparation.clearCompletedHistory()
        assertTrue(PlaybackPreparation.actualTimings.isEmpty())
        assertEquals(0,PlaybackPreparation.progress.value.total)
        assertEquals(PlaybackStatus.READY,PlaybackPreparation.progress.value.phase)
    }
}
