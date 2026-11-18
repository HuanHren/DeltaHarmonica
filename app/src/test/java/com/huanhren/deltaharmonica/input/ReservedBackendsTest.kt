package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReservedBackendsTest {
    @Test fun reservedBackendsNeverAdvertiseOrReturnSuccess() = runTest {
        listOf<InputBackend>(ShizukuBackend()).forEach { backend ->
            assertEquals(BackendAvailability.NOT_IMPLEMENTED, backend.status().availability)
            assertFalse(backend.status().available)
            assertTrue(backend.tap(ScreenPoint(100f, 200f)) is TapResult.Unavailable)
        }
    }
}
