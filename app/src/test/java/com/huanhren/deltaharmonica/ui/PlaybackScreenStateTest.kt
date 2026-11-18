package com.huanhren.deltaharmonica.ui

import com.huanhren.deltaharmonica.playback.PlaybackProgress
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import org.junit.Assert.*
import org.junit.Test

class PlaybackScreenStateTest {
    @Test fun savedLayoutInSmallWindowDefersDisplayCheckUntilReturningToTheGame() {
        assertEquals("已加载，切回游戏后核对屏幕尺寸", playbackLayoutStatus(true, true, false))
        assertEquals("请先保存校准布局", playbackLayoutStatus(false, true, false))
        assertEquals("屏幕不匹配，请在游戏画面核对布局", playbackLayoutStatus(true, false, false))
    }
    @Test fun newerActivationInvalidatesOlderRequest() {
        val workspace = MidiWorkspace()
        val first = workspace.beginActivation()
        val second = workspace.beginActivation()
        assertFalse(workspace.isCurrentActivation(first))
        assertTrue(workspace.isCurrentActivation(second))
    }
    @Test fun staleActivationCannotApplyWorkspaceChanges() {
        val workspace = MidiWorkspace()
        val old = workspace.beginActivation()
        val newer = workspace.beginActivation()
        workspace.commitActivation(newer) { workspace.name = "B" }
        assertThrows(IllegalStateException::class.java) { workspace.commitActivation(old) { workspace.name = "A" } }
        assertEquals("B", workspace.name)
    }
    @Test fun rangesUseActualEventCount() {
        assertEquals(8, PlaybackRange.FIRST_8.count(100))
        assertEquals(16, PlaybackRange.FIRST_16.count(100))
        assertEquals(100, PlaybackRange.ALL.count(100))
        assertEquals(5, PlaybackRange.FIRST_16.count(5))
    }
    @Test fun controlsFollowActualSessionAndStoppingCannotRestart() {
        assertTrue(playbackControls(PlaybackProgress(),false,false).canStart)
        assertFalse(playbackControls(PlaybackProgress(8,1,phase=PlaybackStatus.PLAYING),true,false).canStart)
        assertTrue(playbackControls(PlaybackProgress(8,1,phase=PlaybackStatus.PLAYING),true,false).canStop)
        assertFalse(playbackControls(PlaybackProgress(8,1,phase=PlaybackStatus.STOPPING),true,false).canStart)
        assertFalse(playbackControls(PlaybackProgress(8,1,phase=PlaybackStatus.STOPPING),true,false).canStop)
        assertTrue(playbackControls(PlaybackProgress(8,8,phase=PlaybackStatus.COMPLETED),false,false).canStart)
    }
    @Test fun failedProgressRemainsFailureAndShowsNextUnfinishedEvent() {
        val state=playbackControls(PlaybackProgress(16,3,state="手势失败",phase=PlaybackStatus.FAILED),false,false)
        assertEquals("手势失败",state.status)
        assertEquals(4,state.nextUnfinished)
    }
    @Test fun workspaceKeepsSelectionAndOptionsAcrossScreenRecreation() {
        val workspace=MidiWorkspace()
        workspace.name="演奏曲目.json"
        workspace.sourceFormat="JSON"
        workspace.range=PlaybackRange.FIRST_16
        workspace.speed=2.0
        workspace.confirmPlayback=true
        val reopened=workspace
        assertEquals("演奏曲目.json",reopened.name)
        assertEquals("JSON",reopened.sourceFormat)
        assertEquals(PlaybackRange.FIRST_16,reopened.range)
        assertEquals(2.0,reopened.speed,0.0)
        assertTrue(reopened.confirmPlayback)
    }
}
