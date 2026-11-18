package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.midi.PerformanceScript
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.InstrumentRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow

data class PreparedPlayback(val script: PerformanceScript, val plan: PlaybackPlan, val region: Region,
    val semitone: Boolean, val start: Int, val count: Int, val speed: Double, val warningsConfirmed: Boolean)

/** Only held in process memory; a process restart requires a new Dry-run. */
enum class PlaybackStatus { READY, CHECKING, CONFIRMING, WAITING_FOR_GAME, PLAYING, STOPPING, COMPLETED, FAILED }
data class PlaybackProgress(val total: Int = 0, val completed: Int = 0, val current: String? = null, val state: String = "等待播放", val phase: PlaybackStatus = PlaybackStatus.READY) {
    companion object {
        fun waitingForGame(plan: PlaybackPlan): PlaybackProgress {
            require(plan.events.isNotEmpty() && plan.fatalErrors.isEmpty())
            return PlaybackProgress(plan.events.size,state="等待游戏获得焦点：请收起应用小窗并返回口琴界面；可随时停止",phase=PlaybackStatus.WAITING_FOR_GAME)
        }
    }
}
/** Monotonic dispatch and completion timestamps. Neither backend measures game audio. */
data class ActualTiming(val dispatchMs: Long, val callbackMs: Long?, val noteOffsetMs: Long, val plannedDurationMs: Long, val result: String = "未记录") {
    val callbackLatencyMs: Long? get() = callbackMs?.minus(dispatchMs)
}
object PlaybackPreparation {
    var pending: PreparedPlayback? = null
    var activeRunner: PlaybackRunner? = null
    var cancelPendingStart: (() -> Unit)? = null
    var controlsConfirmed = false
    val progress = MutableStateFlow(PlaybackProgress())
    val runtimeState = MutableStateFlow(InstrumentRuntimeState())
    val actualTimings = mutableMapOf<Int, ActualTiming>()
    fun stopPlayback(): Boolean {
        val runner = activeRunner
        val cancel = cancelPendingStart
        if (runner == null && cancel == null) return false
        progress.value = progress.value.copy(state = "正在停止，等待释放", phase = PlaybackStatus.STOPPING)
        if (runner != null) runner.requestStop() else cancel?.invoke()
        return true
    }
    /** A different score or plan must never inherit another session's measurements. */
    fun clearCompletedHistory() {
        check(activeRunner == null && cancelPendingStart == null) { "播放会话仍在执行" }
        actualTimings.clear()
        progress.value=PlaybackProgress()
    }
}
