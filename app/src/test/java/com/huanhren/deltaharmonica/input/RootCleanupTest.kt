package com.huanhren.deltaharmonica.input

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RootCleanupTest {
    @Test fun androidIdleStateCanConfirmCleanupAfterTheWorkerRejectsReleaseAndExits() = runTest {
        var attempts = 0
        val result = confirmRootCleanup(RootCleanup.REJECTED) {
            attempts++
            RootCommandResult.Success(if (attempts == 1) "TouchStatesByDisplay:" else "  TouchStatesByDisplay: <no displays touched>")
        }
        assertEquals(RootCleanup.OBSERVED_IDLE, result)
        assertEquals(2, attempts)
    }

    @Test fun liveOrSilentWorkerCannotBeClearedByAnIdleSnapshot() = runTest {
        assertEquals(RootCleanup.NO_ACK, confirmRootCleanup(RootCleanup.NO_ACK) { error("Worker termination is unknown") })
    }

    @Test fun activeOrUnreportedDispatcherStateKeepsTheReleaseBlocked() = runTest {
        assertEquals(RootCleanup.REJECTED, confirmRootCleanup(RootCleanup.REJECTED) {
            RootCommandResult.Success("CursorStatesByDisplay: <no displays touched by cursor>\nTouchStatesByDisplay:")
        })
    }

    @Test fun hyperOsEmptyDisplayBucketAlsoConfirmsNoActiveTouchWindow() = runTest {
        val snapshot = """
              TouchStatesByDisplay:
                0 :     Windows: <none>
              CursorStatesByDisplay: <no displays touched by cursor>
        """.trimIndent()
        assertEquals(RootCleanup.OBSERVED_IDLE, confirmRootCleanup(RootCleanup.REJECTED) { RootCommandResult.Success(snapshot) })
    }

    @Test fun emptyBucketDoesNotConcealATouchOnAnotherDisplay() = runTest {
        val snapshot = """
              TouchStatesByDisplay:
                0 :     Windows: <none>
                1 :     Windows:
                  0: name='other app', pointerIds=0
              CursorStatesByDisplay: <no displays touched by cursor>
        """.trimIndent()
        assertEquals(RootCleanup.REJECTED, confirmRootCleanup(RootCleanup.REJECTED) { RootCommandResult.Success(snapshot) })
    }

    @Test fun successfulHelperReleaseNeedsNoExtraCommand() = runTest {
        assertEquals(RootCleanup.ACKNOWLEDGED, confirmRootCleanup(RootCleanup.ACKNOWLEDGED) { error("Already released") })
    }
}
