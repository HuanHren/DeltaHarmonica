package com.huanhren.deltaharmonica.overlay

import com.huanhren.deltaharmonica.model.*
import org.junit.Assert.*
import org.junit.Test

class OverlayGeometryTest {
    @Test fun calibrationPanelCanMoveOffTheLeftKeysAndStaysReachableOnRotation() {
        val landscape = ScreenSize(2670, 1200)
        val moved = OverlayGeometry.clampPanelOrigin(ScreenPoint(1700f, 27f), landscape, 906, 1064)
        assertEquals(ScreenPoint(1700f, 27f), moved)
        assertEquals(ScreenPoint(294f, 27f), OverlayGeometry.clampPanelOrigin(moved, ScreenSize(1200, 2670), 906, 1064))
        assertEquals(ScreenPoint(0f, 0f), OverlayGeometry.clampPanelOrigin(ScreenPoint(-100f, -30f), landscape, 906, 1064))
    }

    @Test fun markerCenterRoundTripsAndClampsAtScreenEdges() {
        val size = ScreenSize(2670, 1200)
        val origin = OverlayGeometry.windowOrigin(NormalizedPoint(0.5f, 0.5f), size, 80)
        assertEquals(NormalizedPoint(.5f, .5f).x, OverlayGeometry.pointFromOrigin(origin, size, 80).x, .001f)
        assertEquals(NormalizedPoint(0f, 1f), OverlayGeometry.pointFromOrigin(ScreenPoint(-500f, 3000f), size, 80))
    }
    @Test fun changedDisplayRequiresRecalibration() {
        val layout = DefaultLayouts.create(1).copy(referenceDisplay = ReferenceDisplay(2670, 1200))
        assertTrue(OverlayGeometry.compatible(layout, ScreenSize(2670,1200)))
        assertFalse(OverlayGeometry.compatible(layout, ScreenSize(2400,1080)))
        assertFalse(OverlayGeometry.compatible(layout, ScreenSize(1200,2670)))
        assertTrue(OverlayGeometry.compatible(layout.copy(referenceDisplay = null), ScreenSize(2670,1200)))
    }
}
