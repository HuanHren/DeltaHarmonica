package com.huanhren.deltaharmonica.input

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCleanupTest {
    @Test fun androidIdleStateCanConfirmCleanupAfterTheWorkerRejectsReleaseAndExits() = runTest {
        var snapshots = 0
        val probe = RootRecoveryProbe { command, _ ->
            RootCommandResult.Success(if (command.contains("/proc/77/stat")) "DELTA_GONE 77" else {
                if (++snapshots == 1) "TouchStatesByDisplay:" else RootRecoveryProbeTest.idle
            })
        }
        val record = RootReleaseRecord("session", RootHelperIdentity(77, 1234))
        assertEquals(RootRecoveryResult.DISPATCHER_UNCONFIRMED, probe.verify(record))
        assertEquals(RootRecoveryResult.CONFIRMED, probe.verify(record))
        assertEquals(3, snapshots)
    }

    @Test fun liveOrSilentWorkerCannotBeClearedByAnIdleSnapshot() = runTest {
        val probe = RootRecoveryProbe { _, _ -> error("Worker termination is unknown") }
        assertEquals(RootRecoveryResult.HELPER_UNKNOWN, probe.verify(RootReleaseRecord("session", null)))
    }

    @Test fun activeOrUnreportedDispatcherStateKeepsTheReleaseBlocked() {
        assertFalse(dispatcherHasNoTouches(listOf(
            "CursorStatesByDisplay: <no displays touched by cursor>", "TouchStatesByDisplay:",
        )))
    }

    @Test fun hyperOsEmptyDisplayBucketAlsoConfirmsNoActiveTouchWindow() {
        assertTrue(dispatcherHasNoTouches(listOf(
            "TouchStatesByDisplay:", "0 :     Windows: <none>", "CursorStatesByDisplay: <no displays touched by cursor>",
        )))
    }

    @Test fun emptyBucketDoesNotConcealATouchOnAnotherDisplay() {
        assertFalse(dispatcherHasNoTouches(listOf(
            "TouchStatesByDisplay:", "0 :     Windows: <none>", "1 :     Windows:",
            "0: name='other app', pointerIds=0", "CursorStatesByDisplay: <no displays touched by cursor>",
        )))
    }

    @Test fun successfulHelperReleaseNeedsNoExtraCommand() = runTest {
        val runner = RootCommandRunner { _, _ -> error("Already released") }
        val backend = RootBackend(runner) { com.huanhren.deltaharmonica.model.ScreenSize(100, 100) }
        assertFalse(backend.releaseUncertain)
        assertTrue(RootCleanup.ACKNOWLEDGED.confirmed)
    }
}
