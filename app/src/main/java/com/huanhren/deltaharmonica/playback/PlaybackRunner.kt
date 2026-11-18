package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.input.TapResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

enum class PlaybackPhase { READY, HIDING, RUNNING, STOP_REQUESTED, WAITING_FOR_GESTURE, GESTURE_FINISHED, TERMINATED, RESTORING, RESTORED }
data class PlaybackResult(val completed: Int, val remaining: Int, val stopped: Boolean, val failure: String?, val maxLatenessMs: Long, val elapsedMs: Long,
    val regionSwitches: Int = 0, val semitoneSwitches: Int = 0)
class PlaybackRunner {
    private val stop = AtomicBoolean(false)
    private val mutex = Mutex()
    fun requestStop() { stop.set(true) }
    internal val stopRequested: Boolean get() = stop.get()
    suspend fun run(plan: PlaybackPlan, hide: () -> Unit, awaitWindows: suspend () -> Unit, valid: () -> Boolean,
                    dispatch: suspend (String,Long) -> TapResult, restore: () -> Unit,
                    onPhase: (PlaybackPhase) -> Unit = {}, dryRunCompleted: Boolean = false, warningsConfirmed: Boolean = false,
                    onEvent: (Int, String, Boolean) -> Unit = { _,_,_ -> },
                    onSessionStarted: (Long) -> Unit = {},
                    dispatchCombined: (suspend (PlannedEvent, Long) -> TapResult)? = null): PlaybackResult {
        require(dryRunCompleted && (plan.warnings.isEmpty() || warningsConfirmed)) { "必须先完成 Dry-run 并确认警告" }
        require(plan.fatalErrors.isEmpty()) { "计划包含不可播放的错误" }
        check(mutex.tryLock()) { "已有播放会话正在执行" }
        var start=System.nanoTime()/1_000_000; var completed=0; var failure: String?=null; var maxLate=0L; var regionSwitches=0; var semitoneSwitches=0
        suspend fun waitUntil(target: Long): Boolean {
            while (true) {
                if (stop.get()) return false
                if (!valid()) { failure="显示环境或无障碍服务已变化"; return false }
                val remaining = target - (System.nanoTime()/1_000_000-start)
                if (remaining <= 0) return true
                delay(minOf(remaining,50))
            }
        }
        suspend fun perform(target: String, duration: Long): Boolean {
            if (stop.get()) return false
            if (!valid()) { failure="显示环境或无障碍服务已变化"; return false }
            onPhase(PlaybackPhase.WAITING_FOR_GESTURE)
            val result=dispatch(target,duration)
            onPhase(PlaybackPhase.GESTURE_FINISHED)
            if (result == TapResult.Cancelled && stop.get()) return false
            if (result != TapResult.Completed) { failure="手势未完成：$result；释放状态需检查"; return false }
            if (!valid()) { failure="手势期间显示环境或无障碍服务已变化"; return false }
            return true
        }
        try {
            onPhase(PlaybackPhase.HIDING); hide(); withTimeout(3000) { awaitWindows() }
            if (!valid()) failure="显示环境或无障碍服务已变化"
            else {
                start=System.nanoTime()/1_000_000
                onSessionStarted(start)
                onPhase(PlaybackPhase.RUNNING)
                for (item in plan.events) {
                    onEvent(completed,item.event.noteKeyId,false)
                    if (plan.combined) {
                        val lead=if(item.regionSwitch || item.semitoneSwitch) plan.combinedLeadMs else 0L
                        if (!waitUntil((item.predictedStartMs-lead).coerceAtLeast(0))) break
                        val now=System.nanoTime()/1_000_000-start
                        val late=(now+lead-item.predictedStartMs).coerceAtLeast(0)
                        maxLate=maxOf(maxLate,late)
                        if (late > 2000) { failure="事件延迟超过 2000 ms，已停止后续事件"; break }
                        if (stop.get() || !valid()) { failure="停止或环境已变化"; break }
                        onPhase(PlaybackPhase.WAITING_FOR_GESTURE)
                        val result = requireNotNull(dispatchCombined) { "组合手势不可用" }(item,item.predictedEndMs-item.predictedStartMs)
                        onPhase(PlaybackPhase.GESTURE_FINISHED)
                        if (result == TapResult.Cancelled && stop.get()) break
                        if (result != TapResult.Completed) { failure="组合手势未完成：$result；请检查状态与音键释放"; break }
                        if(item.regionSwitch) regionSwitches++
                        if(item.semitoneSwitch) semitoneSwitches++
                        if (!valid()) { failure="组合手势期间环境已变化"; break }
                        completed++; onEvent(completed,item.event.noteKeyId,true)
                        continue
                    }
                    val setup = (if(item.regionSwitch) plan.controlGestureMs+plan.settlingMs+plan.gestureOverheadMs else 0L) + (if(item.semitoneSwitch) plan.controlGestureMs+plan.settlingMs+plan.gestureOverheadMs else 0L)
                    if (!waitUntil((item.predictedStartMs-setup).coerceAtLeast(0))) break
                    if (item.regionSwitch) {
                        if (!perform("REGION_${item.event.pitchRegion.name}",plan.controlGestureMs)) break
                        regionSwitches++
                        if (!waitUntil(System.nanoTime()/1_000_000-start+plan.settlingMs)) break
                    }
                    if (item.semitoneSwitch) {
                        if (!perform("SEMITONE_TOGGLE",plan.controlGestureMs)) break
                        semitoneSwitches++
                        if (!waitUntil(System.nanoTime()/1_000_000-start+plan.settlingMs)) break
                    }
                    if (!waitUntil(item.predictedStartMs)) break
                    val late=(System.nanoTime()/1_000_000-start-item.predictedStartMs).coerceAtLeast(0)
                    maxLate=maxOf(maxLate,late)
                    if (late > 2000) { failure="事件延迟超过 2000 ms，已停止后续事件"; break }
                    if (!perform(item.event.noteKeyId,item.predictedEndMs-item.predictedStartMs)) break
                    completed++
                    onEvent(completed,item.event.noteKeyId,true)
                }
            }
        } catch (e: TimeoutCancellationException) { failure="等待悬浮窗口隐藏超时" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure="播放失败：${e.localizedMessage}" }
        finally {
            try {
                try { onPhase(PlaybackPhase.RESTORING) } finally { restore() }
                onPhase(PlaybackPhase.RESTORED)
            } finally { mutex.unlock() }
        }
        onPhase(PlaybackPhase.TERMINATED)
        return PlaybackResult(completed,plan.events.size-completed,stop.get(),failure,maxLate,System.nanoTime()/1_000_000-start,regionSwitches,semitoneSwitches)
    }
}
