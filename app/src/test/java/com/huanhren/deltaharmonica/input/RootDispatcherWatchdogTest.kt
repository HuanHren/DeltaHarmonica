package com.huanhren.deltaharmonica.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDispatcherWatchdogTest {
    @Test fun nativeCallExpiresIndependentlyWithItsPossiblePointers() {
        var now = 100L
        val watchdog = RootDispatcherWatchdog({ now }, nativeBudgetMs = 100, stopBudgetMs = 20)
        assertTrue(watchdog.start(1))
        watchdog.beginNative(RootTouchKind.DOWN, setOf(0, 2))
        now = 199
        assertNull(watchdog.fault(workerAlive = true))
        now = 200
        assertEquals(RootDispatcherFailure(1, RootDispatcherFault.NATIVE_TIMEOUT, setOf(0, 2)),
            watchdog.fault(workerAlive = true))
        watchdog.endNative(RootTouchKind.DOWN, 2, accepted = true, denied = false)
        assertFalse(watchdog.finish(1))
        assertFalse(watchdog.start(2))
    }

    @Test fun stopDeadlineDoesNotExtendWhenTheControlReaderReceivesMoreStops() {
        var now = 0L
        val watchdog = RootDispatcherWatchdog({ now }, nativeBudgetMs = 100, stopBudgetMs = 20)
        assertTrue(watchdog.start(7))
        watchdog.beginNative(RootTouchKind.DOWN, setOf(0))
        watchdog.endNative(RootTouchKind.DOWN, 0, accepted = true, denied = false)
        watchdog.requestStop(InterruptedException())
        now = 19
        watchdog.requestStop(InterruptedException())
        assertNull(watchdog.fault(workerAlive = true))
        now = 20
        assertEquals(RootDispatcherFailure(7, RootDispatcherFault.STOP_TIMEOUT, setOf(0)),
            watchdog.fault(workerAlive = true))
        assertFalse(watchdog.finish(7))
    }

    @Test fun confirmedCancelWithinStopBudgetPermitsOnlyTheNextOperation() {
        var now = 0L
        val watchdog = RootDispatcherWatchdog({ now }, nativeBudgetMs = 100, stopBudgetMs = 20)
        assertTrue(watchdog.start(7))
        watchdog.beginNative(RootTouchKind.DOWN, setOf(0))
        watchdog.endNative(RootTouchKind.DOWN, 0, accepted = true, denied = false)
        watchdog.requestStop(InterruptedException())
        now = 10
        watchdog.beginNative(RootTouchKind.CANCEL, setOf(0))
        watchdog.endNative(RootTouchKind.CANCEL, 0, accepted = true, denied = false)
        assertTrue(watchdog.finish(7))
        assertNull(watchdog.fault(workerAlive = true))
        assertTrue(watchdog.start(8))
        assertFalse(watchdog.finish(7))
        assertEquals(RootDispatcherFailure(8, RootDispatcherFault.WORKER_FAILED, emptySet()),
            watchdog.fault(workerAlive = false))
    }

    @Test fun transportExpiryRemainsArmedEvenAfterTheWorkerReleasesAndGoesIdle() {
        var now = 0L
        val watchdog = RootDispatcherWatchdog({ now }, nativeBudgetMs = 100, stopBudgetMs = 20, transportBudgetMs = 50)
        assertTrue(watchdog.start(1))
        watchdog.endTransport()
        now = 10
        assertTrue(watchdog.finish(1))
        assertFalse(watchdog.start(2))
        now = 49
        watchdog.endTransport()
        assertFalse(watchdog.transportExpired())
        assertNull(watchdog.fault(workerAlive = true))
        now = 50
        assertTrue(watchdog.transportExpired())
    }

    @Test fun deadWorkerWithAnActiveOperationCannotConfirmReleaseDuringEof() {
        val watchdog = RootDispatcherWatchdog({ 0L })
        assertTrue(watchdog.canConfirmRelease())
        assertTrue(watchdog.start(1))
        watchdog.beginNative(RootTouchKind.DOWN, setOf(0))
        watchdog.endTransport()
        assertFalse(watchdog.canConfirmRelease())
        assertEquals(RootDispatcherFailure(1, RootDispatcherFault.WORKER_FAILED, setOf(0)),
            watchdog.fault(workerAlive = false))
        assertFalse(watchdog.finish(1))
        assertFalse(watchdog.canConfirmRelease())
    }
}
