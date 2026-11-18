package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

internal val inputDispatchMutex = Mutex()

enum class NoteActionType { TAP, HOLD }
enum class NoteActionState { READY, DISPATCHING, COMPLETED, CANCELLED, FAILED }
/** Android GestureDescription's documented overall maximum; offsets count toward this limit. */
const val MAX_GESTURE_DURATION_MS = 60_000L
data class NoteAction(val targetId: String, val point: ScreenPoint, val type: NoteActionType, val durationMs: Long,
                      val startTimeMs: Long? = null, val state: NoteActionState = NoteActionState.READY, val failureReason: String? = null) {
    init { require(targetId.isNotBlank()); require(durationMs in 1L..MAX_GESTURE_DURATION_MS); require(startTimeMs == null || startTimeMs >= 0) }
}
class NoteActionGate {
    private val mutex = inputDispatchMutex
    suspend fun run(block: suspend () -> TapResult): TapResult {
        if (!mutex.tryLock()) return TapResult.Busy
        try { return block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return TapResult.Unavailable("手势失败：${e.localizedMessage}") }
        finally { mutex.unlock() }
    }
}
