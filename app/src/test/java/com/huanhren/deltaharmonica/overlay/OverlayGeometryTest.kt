package com.huanhren.deltaharmonica.overlay

import com.huanhren.deltaharmonica.model.*
import org.junit.Assert.*
import org.junit.Test

class OverlayGeometryTest {
    @Test fun dragUsesTheOriginalScreenCoordinatesThroughRepeatedAndFinalMoves() {
        val footprint = OverlayFootprint(ScreenSize(1000, 600), 180, 80, 8)
        val drag = OverlayDrag(ScreenPoint(62f, 105f), ScreenPoint(45f, 85f), 8)
        assertNull(drag.position(ScreenPoint(65f, 109f), footprint))
        assertEquals(ScreenPoint(85f, 111f), drag.position(ScreenPoint(102f, 131f), footprint))
        assertEquals(ScreenPoint(85f, 111f), drag.position(ScreenPoint(102f, 131f), footprint))
        assertEquals(ScreenPoint(115f, 131f), drag.position(ScreenPoint(132f, 151f), footprint))
        assertEquals(ScreenPoint(812f, 512f), drag.position(ScreenPoint(1500f, 1200f), footprint))
        assertEquals(ScreenPoint(165f, 125f), drag.position(ScreenPoint(182f, 145f), footprint))
    }

    @Test fun windowAnchorKeepsTheCapsulePositionWhenExpandedContentIsTemporarilyClamped() {
        val display = ScreenSize(1000, 600)
        val capsule = OverlayFootprint(display, 180, 80, 8)
        val expanded = OverlayFootprint(display, 440, 400, 8)
        val anchor = capsule.anchorAt(ScreenPoint(360f, 440f), expanded)
        assertEquals(ScreenPoint(552f, 192f), expanded.originOf(anchor, capsule))
        assertEquals(ScreenPoint(812f, 440f), capsule.originOf(anchor))
        val rotatedCapsule = OverlayFootprint(ScreenSize(600, 1000), 180, 80, 8)
        assertEquals(ScreenPoint(412f, 783f), rotatedCapsule.originOf(anchor).let {
            ScreenPoint(it.x, kotlin.math.round(it.y))
        })
    }

    @Test fun legacyPlayerPositionUsesThePreviousVerticalRangeOnce() {
        val capsule = OverlayFootprint(ScreenSize(1000, 600), 180, 80, 8)
        val upgraded = capsule.legacyAnchor(0f, .24f)
        val origin = capsule.originOf(upgraded)
        assertEquals(8f, origin.x, .001f)
        assertEquals(124.8f, origin.y, .001f)
        assertEquals(origin.y, capsule.originOf(capsule.legacyAnchor(Float.NaN, Float.NaN)).y, .001f)
    }

    @Test fun calibrationAnchorsPreserveBothAxesOfAnOffCenterRelease() {
        val display = ScreenSize(1000, 600)
        val capsule = OverlayFootprint(display, 180, 80, 8)
        val expanded = OverlayFootprint(display, 440, 400, 8)
        val release = ScreenPoint(283f, 173f)
        val anchor = capsule.freeAnchorAt(release, expanded, NormalizedPoint(1f, .24f))
        val restored = expanded.originOf(anchor, capsule)
        assertEquals(release.x, restored.x, .001f)
        assertEquals(release.y, restored.y, .001f)
    }

    @Test fun horizontalCalibrationDragKeepsVerticalAnchorWhenTheExpandedWindowIsClamped() {
        val display = ScreenSize(1000, 600)
        val capsule = OverlayFootprint(display, 180, 80, 8)
        val expanded = OverlayFootprint(display, 440, 400, 8)
        val previous = NormalizedPoint(1f, .8f)
        val release = expanded.originOf(previous, capsule).copy(x = 100f)
        val anchor = capsule.freeAnchorAt(release, expanded, previous)
        assertEquals(previous.y, anchor.y, 0f)
        assertEquals(release.x, expanded.originOf(anchor, capsule).x, .001f)
    }

    @Test fun targetNudgeUsesDisplayPixelsAndClampsToLastUsablePixel() {
        val display = ScreenSize(1001, 601)
        val start = NormalizedPoint(.5f, .5f)
        assertEquals(NormalizedPoint(.75f, .5f), OverlayGeometry.nudgeTarget(start, display, 250, 0))
        assertEquals(NormalizedPoint(.5f, 0f), OverlayGeometry.nudgeTarget(start, display, 0, -500))
        assertEquals(NormalizedPoint(1f, 1f), OverlayGeometry.nudgeTarget(start, display, 1000, 500))
    }

    @Test fun calibrationPanelCanMoveOffTheLeftKeysAndStaysReachableOnRotation() {
        val landscape = ScreenSize(2670, 1200)
        val moved = OverlayGeometry.clampPanelOrigin(ScreenPoint(1700f, 27f), landscape, 906, 1064)
        assertEquals(ScreenPoint(1700f, 27f), moved)
        assertEquals(ScreenPoint(294f, 27f), OverlayGeometry.clampPanelOrigin(moved, ScreenSize(1200, 2670), 906, 1064))
        assertEquals(ScreenPoint(0f, 0f), OverlayGeometry.clampPanelOrigin(ScreenPoint(-100f, -30f), landscape, 906, 1064))
    }

    @Test fun snapUsesTheClosestSideAndClampsTheRestoredVerticalPosition() {
        val size = ScreenSize(2670, 1200)
        assertEquals(ScreenPoint(24f, 14f), OverlayGeometry.snappedPlayerOrigin(ScreenPoint(50f, 14f), size, 204, 144, 24))
        assertEquals(ScreenPoint(1662f, 820f), OverlayGeometry.snappedPlayerOrigin(ScreenPoint(2180f, 1100f), size, 984, 380, 24))
        assertEquals(ScreenPoint(0f, 0f), OverlayGeometry.snappedPlayerOrigin(ScreenPoint(12f, 14f), ScreenSize(90, 60), 90, 60, 24))
    }

    @Test fun gameControlsChooseAnotherCornerWhenTheTopRightOverlaps() {
        val screen = ScreenSize(1200, 600)
        assertEquals(ScreenPoint(0f, 0f), OverlayGeometry.controlBarOrigin(screen, 450, 140, listOf(ScreenPoint(1199f, 10f))))
        val corners = listOf(ScreenPoint(1f, 10f), ScreenPoint(1199f, 10f), ScreenPoint(1f, 599f), ScreenPoint(1199f, 599f))
        assertEquals(ScreenPoint(749f, 0f), OverlayGeometry.controlBarOrigin(screen, 450, 140, corners))
        assertNull(OverlayGeometry.controlBarOrigin(screen, screen.width, screen.height, corners))
    }

    @Test fun gameControlsProtectAccessibilityPixelsAtBothWindowEdges() {
        val screen = ScreenSize(96, 240)
        val nearLeftEdge = ScreenPoint(47.9f, 10f)
        val nearTopEdge = ScreenPoint(10f, 191.9f)
        val hitPoints = OverlayGeometry.protectedTouchPoints(listOf(nearLeftEdge, nearTopEdge))
        assertTrue(ScreenPoint(48f, 10f) in hitPoints)
        assertTrue(ScreenPoint(10f, 192f) in hitPoints)
        assertEquals(ScreenPoint(48f, 192f), OverlayGeometry.controlBarOrigin(screen, 48, 48, hitPoints))
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
