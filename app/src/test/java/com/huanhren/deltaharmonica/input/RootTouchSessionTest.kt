package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint
import org.junit.Assert.*
import org.junit.Test

class RootTouchSessionTest {
    private fun action(ms: Long = 300) = NoteAction("key-1", ScreenPoint(12f, 20f), NoteActionType.HOLD, ms)

    @Test fun commandCompletionAloneDoesNotConfirmRelease() {
        val session = RootTouchSession()
        assertTrue(session.begin(action()))
        session.commandFinished(session.actionId)
        assertEquals(RootTouchState.UNKNOWN, session.state)
        assertFalse(session.begin(action()))
    }

    @Test fun failedOrStoppedSessionCannotRestartEvenAfterRepeatedCleanup() {
        val session = RootTouchSession()
        assertTrue(session.begin(action(1000)))
        session.requestStop()
        session.cleanupUnconfirmed()
        session.cleanupUnconfirmed()
        assertEquals(RootTouchState.UNKNOWN, session.state)
        assertFalse(session.begin(action()))
    }

    @Test fun confirmedReleaseAllowsNextActionButStopStillBlocksNewActions() {
        val session = RootTouchSession()
        assertTrue(session.begin(action(120)))
        session.holding()
        session.requestRelease()
        session.releaseObserved(session.actionId)
        assertEquals(RootTouchState.RELEASED, session.state)
        assertTrue(session.begin(action(600)))
        session.requestStop()
        session.releaseObserved(session.actionId)
        assertEquals(RootTouchState.RELEASED, session.state)
        assertFalse(session.begin(action()))
    }

    @Test fun duplicateOrLateReleaseObservationCannotReleaseAnotherAction() {
        val session = RootTouchSession()
        assertTrue(session.begin(action()))
        val first = session.actionId
        session.requestRelease()
        assertTrue(session.releaseObserved(first))
        assertTrue(session.begin(action()))
        assertFalse(session.releaseObserved(first))
        session.commandFinished(first)
        assertEquals(RootTouchState.PRESSED, session.state)
    }
}
