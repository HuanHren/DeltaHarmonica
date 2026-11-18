package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NoteActionTest {
    @Test fun durationAndTargetAreValidated() {
        val p = ScreenPoint(10f, 20f)
        listOf(0L, -1L, MAX_GESTURE_DURATION_MS+1).forEach { d -> assertThrows(IllegalArgumentException::class.java) { NoteAction("key", p, NoteActionType.HOLD, d) } }
        assertThrows(IllegalArgumentException::class.java) { NoteAction("", p, NoteActionType.TAP, 150) }
        assertEquals(150, NoteAction("key", p, NoteActionType.TAP, 150).durationMs)
        assertEquals(2000, NoteAction("key", p, NoteActionType.HOLD, 2000).durationMs)
    }
    @Test fun simultaneousActionsAreRejectedAndResultsPreserved() = runBlocking {
        val gate = NoteActionGate()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.run { started.complete(Unit); release.await(); TapResult.Completed } }
        started.await()
        assertEquals(TapResult.Busy, gate.run { TapResult.Completed })
        release.complete(Unit)
        assertEquals(TapResult.Completed, first.await())
        assertEquals(TapResult.Cancelled, gate.run { TapResult.Cancelled })
        assertEquals(TapResult.TimedOut, gate.run { TapResult.TimedOut })
        assertTrue(gate.run { throw IllegalStateException("failure") } is TapResult.Unavailable)
    }
}
