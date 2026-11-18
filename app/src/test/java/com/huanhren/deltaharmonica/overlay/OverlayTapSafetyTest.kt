package com.huanhren.deltaharmonica.overlay

import com.huanhren.deltaharmonica.input.TapResult
import com.huanhren.deltaharmonica.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OverlayTapSafetyTest {
    @Test fun hideFailureRestoresAndDoesNotDispatch() = runBlocking {
        val layout = DefaultLayouts.create(1).copy(referenceDisplay = ReferenceDisplay(2670,1200))
        val original = layout.copy()
        var restores = 0; var dispatches = 0
        val result = OverlayTapCoordinator.execute(layout, layout.targets.first().id, ScreenSize(2670,1200), true,
            { throw IllegalStateException("window") }, {}, { true }, { dispatches++; TapResult.Completed }, { restores++ })
        assertTrue(result is TapResult.Unavailable); assertEquals(1, restores); assertEquals(0, dispatches); assertEquals(original, layout)
    }
    @Test fun missingReferenceOrDisabledServiceDoesNotTouchWindows() = runBlocking {
        val layout = DefaultLayouts.create(1)
        var calls = 0
        val result = OverlayTapCoordinator.execute(layout, layout.targets.first().id, ScreenSize(2670,1200), false,
            { calls++ }, {}, { true }, { calls++; TapResult.Completed }, { calls++ })
        assertTrue(result is TapResult.Unavailable); assertEquals(0, calls)
    }
}
