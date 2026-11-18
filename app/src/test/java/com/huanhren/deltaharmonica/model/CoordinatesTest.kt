package com.huanhren.deltaharmonica.model

import org.junit.Assert.*
import org.junit.Test

class CoordinatesTest {
    private val screen = ScreenSize(2400, 1080)

    @Test fun endpointsStayInsideDisplay() {
        assertEquals(ScreenPoint(0f, 0f), Coordinates.toScreen(NormalizedPoint(0f, 0f), screen))
        assertEquals(ScreenPoint(2399f, 1079f), Coordinates.toScreen(NormalizedPoint(1f, 1f), screen))
    }

    @Test fun composeOriginIsAddedBeforeNormalizing() {
        val result = Coordinates.fromLocal(ScreenPoint(400f, 200f), ScreenPoint(100f, 50f), screen)
        assertEquals(500f / 2399f, result.x, 0.00001f)
        assertEquals(250f / 1079f, result.y, 0.00001f)
    }

    @Test fun drawingSubtractsComposeOrigin() {
        val result = Coordinates.toLocal(NormalizedPoint(0.5f, 0.5f), ScreenPoint(100f, 50f), screen)
        assertEquals(1099.5f, result.x, 0.001f)
        assertEquals(489.5f, result.y, 0.001f)
    }

    @Test fun dragBeyondDisplayClampsToEdges() {
        assertEquals(NormalizedPoint(0f, 1f), Coordinates.fromLocal(
            ScreenPoint(-300f, 3000f), ScreenPoint(100f, 50f), screen))
    }

    @Test fun normalizedLayoutScalesAcrossResolutions() {
        val point = NormalizedPoint(0.25f, 0.75f)
        listOf(screen, ScreenSize(1920, 1080), ScreenSize(1080, 2400)).forEach { size ->
            val pixels = Coordinates.toScreen(point, size)
            val roundTrip = Coordinates.fromLocal(pixels, ScreenPoint(0f, 0f), size)
            assertEquals(point.x, roundTrip.x, 0.00001f)
            assertEquals(point.y, roundTrip.y, 0.00001f)
        }
    }

    @Test fun invalidDimensionsAndNonFinitePointsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScreenSize(0, 1080) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedPoint(Float.NaN, 0f) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedPoint(0f, 1.01f) }
        assertThrows(IllegalArgumentException::class.java) { ScreenPoint(Float.POSITIVE_INFINITY, 0f) }
    }

    @Test fun orientationUsesDisplayDimensions() {
        assertEquals(LayoutOrientation.LANDSCAPE, screen.orientation)
        assertEquals(LayoutOrientation.PORTRAIT, ScreenSize(1080, 2400).orientation)
    }
}
