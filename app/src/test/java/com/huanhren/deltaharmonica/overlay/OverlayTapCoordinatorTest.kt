package com.huanhren.deltaharmonica.overlay

import com.huanhren.deltaharmonica.input.TapResult
import com.huanhren.deltaharmonica.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OverlayTapCoordinatorTest {
    private val screen = ScreenSize(2670,1200)
    private val layout = DefaultLayouts.create(1).copy(referenceDisplay = ReferenceDisplay(2670,1200))
    @Test fun selectsExactTargetAndRestoresAfterSuccess() = runBlocking {
        val target = layout.targets[3]; var result: ScreenPoint? = null; var restored = 0
        val outcome = OverlayTapCoordinator.execute(layout, target.id, screen, true, {}, {}, { true }, { result = it; TapResult.Completed }, { restored++ })
        assertEquals(TapResult.Completed, outcome)
        assertEquals(Coordinates.toScreen(target.position, screen), result)
        assertEquals(1, restored)
    }
    @Test fun restoresOnCancellationFailureAndTimeout() = runBlocking {
        for (mode in 0..2) {
            var restored = 0
            val outcome = OverlayTapCoordinator.execute(layout, layout.targets[0].id, screen, true, {}, {}, { true }, {
                when (mode) { 0 -> TapResult.Cancelled; 1 -> throw IllegalStateException("failed"); else -> TapResult.TimedOut }
            }, { restored++ })
            assertEquals(1, restored)
            assertTrue(outcome == TapResult.Cancelled || outcome == TapResult.TimedOut || outcome is TapResult.Unavailable)
        }
    }
    @Test fun incompatibleOrMissingTargetDoesNotHideOrDispatch() = runBlocking {
        var operations = 0
        val outcome = OverlayTapCoordinator.execute(layout, "missing", screen, true, { operations++ }, {}, { true }, { operations++; TapResult.Completed }, { operations++ })
        assertTrue(outcome is TapResult.Unavailable)
        assertEquals(0, operations)
        val wrongSize = OverlayTapCoordinator.execute(layout, layout.targets[0].id, ScreenSize(2400,1080), true, { operations++ }, {}, { true }, { operations++; TapResult.Completed }, { operations++ })
        assertTrue(wrongSize is TapResult.Unavailable)
        assertEquals(0, operations)
    }
    @Test fun environmentChangeAndCoroutineCancellationRestoreOnce() = runBlocking {
        var restores = 0; var dispatched = 0
        val result = OverlayTapCoordinator.execute(layout, layout.targets[0].id, screen, true, {}, {}, { false }, { dispatched++; TapResult.Completed }, { restores++ })
        assertTrue(result is TapResult.Unavailable); assertEquals(1, restores); assertEquals(0, dispatched)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            OverlayTapCoordinator.execute(layout, layout.targets[0].id, screen, true, {}, { awaitCancellation() }, { true }, { TapResult.Completed }, { restores++ })
        }
        job.cancelAndJoin(); assertEquals(2, restores)
    }
}
