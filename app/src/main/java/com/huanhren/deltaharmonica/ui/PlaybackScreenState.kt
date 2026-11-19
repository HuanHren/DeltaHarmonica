package com.huanhren.deltaharmonica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.playback.*
import com.huanhren.deltaharmonica.data.SongLibraryRepository

enum class PlaybackRange { FIRST_8, FIRST_16, ALL;
    fun count(total: Int): Int = when(this) { FIRST_8 -> minOf(8,total); FIRST_16 -> minOf(16,total); ALL -> total }
}

internal fun currentPlaybackControls() = playbackControls(PlaybackPreparation.progress.value,
    PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null)

data class PlaybackControls(val canStart: Boolean, val canStop: Boolean, val status: String, val nextUnfinished: Int?)
internal fun playbackLayoutStatus(saved: Boolean, inSmallWindow: Boolean, matchesDisplay: Boolean): String = when {
    !saved -> "请先保存校准布局"
    inSmallWindow -> "已加载，切回游戏后核对屏幕尺寸"
    matchesDisplay -> "屏幕尺寸匹配，请确认目标已对准"
    else -> "屏幕不匹配，请在游戏画面核对布局"
}

fun playbackControls(progress: PlaybackProgress, running: Boolean, waiting: Boolean): PlaybackControls {
    val busy = running || waiting || progress.phase in setOf(PlaybackStatus.CHECKING,PlaybackStatus.CONFIRMING,PlaybackStatus.WAITING_FOR_GAME,PlaybackStatus.PLAYING,PlaybackStatus.PAUSING,PlaybackStatus.PAUSED,PlaybackStatus.STOPPING)
    val canStop = (running || waiting || progress.phase == PlaybackStatus.WAITING_FOR_GAME) && progress.phase != PlaybackStatus.STOPPING
    return PlaybackControls(!busy, canStop, progress.state,
        if(progress.phase == PlaybackStatus.FAILED && progress.completed < progress.total) progress.completed+1 else null)
}

/** ViewModel-owned screen data; playback work remains in the existing service. */
class MidiWorkspace {
    private val activationSequence = java.util.concurrent.atomic.AtomicLong()
    @Synchronized fun beginActivation(): Long = activationSequence.incrementAndGet()
    @Synchronized fun isCurrentActivation(request: Long): Boolean = activationSequence.get() == request
    @Synchronized fun commitActivation(request: Long, action: () -> Unit) {
        check(isCurrentActivation(request)) { "选曲请求已过期" }
        action()
    }
    var songId by mutableStateOf<String?>(null)
    var candidateName by mutableStateOf("")
    var candidateToken by mutableStateOf<String?>(null)
    var candidateTrack by mutableStateOf(0)
    var candidatePolicy by mutableStateOf(MonophonyPolicy.HIGHEST_NOTE)
    var candidateTranspose by mutableStateOf(0)
    var parsed by mutableStateOf<ParsedMidi?>(null)
    var name by mutableStateOf("")
    var sourceFormat by mutableStateOf("")
    var selectedTrack by mutableStateOf(0)
    var policy by mutableStateOf(MonophonyPolicy.HIGHEST_NOTE)
    var transpose by mutableStateOf(0)
    var report by mutableStateOf<ConversionReport?>(null)
    var message by mutableStateOf("")
    var dryRun by mutableStateOf<PlaybackPlan?>(null)
    var speed by mutableStateOf(1.0)
    var minimumTouchMs by mutableStateOf(1L)
    var range by mutableStateOf(PlaybackRange.FIRST_8)
    var combinedMode by mutableStateOf(false)
    var combinedLeadMs by mutableStateOf(40L)
    var confirmPlayback by mutableStateOf(false)
    var stateDialog by mutableStateOf(false)
    var startAfterSync by mutableStateOf(false)
}

/** Validate all persisted artifacts before replacing the existing workspace or its statistics. */
private fun ensureLibraryIdle(workspace: MidiWorkspace) {
    check(playbackControls(PlaybackPreparation.progress.value, PlaybackPreparation.activeRunner != null,
        PlaybackPreparation.cancelPendingStart != null).canStart && !workspace.stateDialog && !workspace.confirmPlayback) { "播放会话尚未结束" }
}

suspend fun loadLibrarySong(repository: SongLibraryRepository, id: String, workspace: MidiWorkspace) {
    val request = workspace.beginActivation()
    ensureLibraryIdle(workspace)
    val item = requireNotNull(repository.get(id)) { "曲目不存在" }
    repository.readOriginal(id)
    val script = repository.readScript(id)
    val changes = script.events.zipWithNext().sumOf { (a,b) -> (if (a.pitchRegion != b.pitchRegion) 1 else 0) + (if (a.semitoneEnabled != b.semitoneEnabled) 1 else 0) }
    workspace.commitActivation(request) {
        ensureLibraryIdle(workspace)
        PlaybackPreparation.clearCompletedHistory()
        PlaybackPreparation.pending = null
        workspace.songId = id
        workspace.name = item.displayName
        workspace.sourceFormat = item.sourceType.name
        workspace.report = ConversionReport(script, item.warnings, changes)
        workspace.dryRun = null
        workspace.parsed = null
        workspace.selectedTrack = script.sourceTrack
        workspace.policy = script.monophonyPolicy
        workspace.transpose = script.transpose
    }
}

suspend fun removeLibrarySong(repository: SongLibraryRepository, id: String, workspace: MidiWorkspace) {
    ensureLibraryIdle(workspace)
    workspace.beginActivation()
    check(repository.delete(id)) { "曲目不存在" }
    if (workspace.songId == id) {
        ensureLibraryIdle(workspace)
        PlaybackPreparation.clearCompletedHistory()
        PlaybackPreparation.pending = null
        workspace.songId = null
        workspace.report = null
        workspace.dryRun = null
        workspace.name = ""
        workspace.sourceFormat = ""
    }
}
