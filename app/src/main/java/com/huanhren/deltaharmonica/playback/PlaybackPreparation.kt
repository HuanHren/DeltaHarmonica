package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.midi.PerformanceScript
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.InstrumentRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow

data class PreparedPlayback(val script: PerformanceScript, val plan: PlaybackPlan, val region: Region,
    val semitone: Boolean, val start: Int, val count: Int, val speed: Double, val warningsConfirmed: Boolean)

/** Only held in process memory; a process restart requires a new Dry-run. */
enum class PlaybackStatus { READY, CHECKING, CONFIRMING, WAITING_FOR_GAME, PLAYING, PAUSING, PAUSED, STOPPING, COMPLETED, FAILED }
data class PlaybackProgress(val total: Int = 0, val completed: Int = 0, val current: String? = null, val state: String = "等待播放", val phase: PlaybackStatus = PlaybackStatus.READY,
    val elapsedMs: Long = 0L, val durationMs: Long = 0L, val stopped: Boolean = false) {
    companion object {
        fun waitingForGame(plan: PlaybackPlan): PlaybackProgress {
            require(plan.events.isNotEmpty() && plan.fatalErrors.isEmpty())
            return PlaybackProgress(plan.events.size,state="等待游戏获得焦点：请收起应用小窗并返回口琴界面；可随时停止",phase=PlaybackStatus.WAITING_FOR_GAME, durationMs = plan.estimatedTotalMs)
        }
    }
}
/** Monotonic dispatch and completion timestamps. Neither backend measures game audio. */
data class ActualTiming(val dispatchMs: Long, val callbackMs: Long?, val noteOffsetMs: Long, val plannedDurationMs: Long, val result: String = "未记录") {
    val callbackLatencyMs: Long? get() = callbackMs?.minus(dispatchMs)
}
object PlaybackPreparation {
    /** Ownership of the arming coroutine and, after handoff, its playback runner. Main thread only. */
    internal class Session internal constructor(val cancelStart: () -> Unit) {
        var runner: PlaybackRunner? = null
            internal set
        var pendingStopOrigin: PlaybackStopOrigin? = null
            internal set
    }

    var pending: PreparedPlayback? = null
    private var session: Session? = null
    val activeRunner: PlaybackRunner? get() = session?.runner
    val cancelPendingStart: (() -> Unit)? get() = session?.takeIf { it.runner == null }?.cancelStart
    internal var activeInput: PlaybackInput? = null
    internal var confirmResume: (() -> Boolean)? = null
    var controlsConfirmed = false
    val progress = MutableStateFlow(PlaybackProgress())
    val runtimeState = MutableStateFlow(InstrumentRuntimeState())
    val actualTimings = mutableMapOf<Int, ActualTiming>()

    internal fun beginSession(cancelStart: () -> Unit): Session? {
        if (session != null) return null
        return Session(cancelStart).also { session = it }
    }

    internal fun owns(owner: Session): Boolean = session === owner

    internal fun attachRunner(owner: Session, runner: PlaybackRunner): Boolean {
        if (!owns(owner) || owner.runner != null || progress.value.phase == PlaybackStatus.STOPPING) return false
        owner.runner = runner
        return true
    }

    internal fun finishSession(owner: Session, terminal: PlaybackProgress): Boolean {
        if (!owns(owner)) return false
        session = null
        activeInput = null
        confirmResume = null
        progress.value = terminal
        return true
    }

    internal fun stopPlayback(owner: Session, origin: PlaybackStopOrigin = PlaybackStopOrigin.USER_STOP): Boolean =
        owns(owner) && stopPlayback(origin)

    fun togglePause(): Boolean {
        val runner = activeRunner ?: return false
        val current = progress.value
        when (current.phase) {
            PlaybackStatus.PLAYING -> {
                runner.requestPause()
                progress.value = current.copy(state = "等待当前音符释放", phase = PlaybackStatus.PAUSING)
            }
            PlaybackStatus.PAUSED -> {
                if (!runner.pauseRequested) return false
                if (confirmResume?.invoke() == false) return false
                runner.requestResume()
            }
            else -> return false
        }
        return true
    }

    internal fun recordTiming(index: Int, timing: ActualTiming) {
        if (actualTimings.size >= 1_024 && index !in actualTimings) {
            actualTimings.remove(actualTimings.keys.first())
        }
        actualTimings[index] = timing
    }

    fun stopPlayback(): Boolean = stopPlayback(PlaybackStopOrigin.USER_STOP)

    internal fun stopPlayback(origin: PlaybackStopOrigin): Boolean {
        val current = progress.value
        val owner = session
        if (owner == null) {
            if (current.phase != PlaybackStatus.WAITING_FOR_GAME || pending == null) return false
            pending = null
            progress.value = current.copy(state = "已取消等待", phase = PlaybackStatus.COMPLETED, stopped = true)
            return true
        }
        if (current.phase == PlaybackStatus.STOPPING) return true
        progress.value = current.copy(state = "正在停止，等待释放", phase = PlaybackStatus.STOPPING)
        val runner = owner.runner
        if (runner != null) {
            runner.requestStop(origin)
        } else {
            owner.pendingStopOrigin = origin
            owner.cancelStart()
        }
        return true
    }
    /** A different score or plan must never inherit another session's measurements. */
    fun clearCompletedHistory() {
        check(activeRunner == null && cancelPendingStart == null) { "播放会话仍在执行" }
        actualTimings.clear()
        progress.value=PlaybackProgress()
    }
}
