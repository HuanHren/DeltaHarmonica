package com.huanhren.deltaharmonica.overlay

import com.huanhren.deltaharmonica.model.*

object OverlayGeometry {
    fun clampPanelOrigin(origin: ScreenPoint, screen: ScreenSize, width: Int, height: Int): ScreenPoint =
        ScreenPoint(
            origin.x.coerceIn(0f, (screen.width - width).coerceAtLeast(0).toFloat()),
            origin.y.coerceIn(0f, (screen.height - height).coerceAtLeast(0).toFloat()),
        )

    fun windowOrigin(point: NormalizedPoint, size: ScreenSize, markerSize: Int): ScreenPoint {
        val center = Coordinates.toScreen(point, size)
        return ScreenPoint(center.x - markerSize / 2f, center.y - markerSize / 2f)
    }
    fun pointFromOrigin(origin: ScreenPoint, size: ScreenSize, markerSize: Int): NormalizedPoint =
        Coordinates.fromLocal(ScreenPoint(origin.x + markerSize / 2f, origin.y + markerSize / 2f), ScreenPoint(0f, 0f), size)
    fun compatible(layout: InstrumentLayout, size: ScreenSize): Boolean =
        layout.orientation == size.orientation && (layout.referenceDisplay == null ||
            (layout.referenceDisplay.width == size.width && layout.referenceDisplay.height == size.height))
}
