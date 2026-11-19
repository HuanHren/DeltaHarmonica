package com.huanhren.deltaharmonica.overlay

import com.huanhren.deltaharmonica.model.*
import kotlin.math.floor
import kotlin.math.roundToInt

object OverlayGeometry {
    fun clampPanelOrigin(origin: ScreenPoint, screen: ScreenSize, width: Int, height: Int): ScreenPoint =
        ScreenPoint(
            origin.x.coerceIn(0f, (screen.width - width).coerceAtLeast(0).toFloat()),
            origin.y.coerceIn(0f, (screen.height - height).coerceAtLeast(0).toFloat()),
        )

    fun snappedPlayerOrigin(origin: ScreenPoint, screen: ScreenSize, width: Int, height: Int, margin: Int): ScreenPoint {
        val available = (screen.width - width).coerceAtLeast(0)
        val inset = margin.coerceAtMost(available / 2)
        return clampPanelOrigin(
            ScreenPoint(if (origin.x <= available / 2f) inset.toFloat() else (available - inset).toFloat(), origin.y),
            screen, width, height,
        )
    }

    // Android accessibility gestures round coordinates when creating touch points.
    fun protectedTouchPoints(targets: List<ScreenPoint>): List<ScreenPoint> = targets.flatMap { point ->
        listOf(point, ScreenPoint(point.x.roundToInt().toFloat(), point.y.roundToInt().toFloat()))
    }.distinct()

    fun controlBarOrigin(screen: ScreenSize, width: Int, height: Int, targets: List<ScreenPoint>): ScreenPoint? {
        if (width > screen.width || height > screen.height) return null
        val right = (screen.width - width).toFloat()
        val bottom = (screen.height - height).toFloat()
        fun offsets(points: List<Float>, extent: Int, limit: Float): List<Float> =
            (listOf(0f, limit) + points.flatMap {
                listOf(floor(it) + 1f, floor(it - extent))
            }).filter { it in 0f..limit }.distinct()

        val xs = offsets(targets.map(ScreenPoint::x), width, right).sortedDescending()
        val ys = offsets(targets.map(ScreenPoint::y), height, bottom).sorted()
        val candidates = listOf(ScreenPoint(right, 0f), ScreenPoint(0f, 0f), ScreenPoint(right, bottom), ScreenPoint(0f, bottom)) +
            xs.flatMap { x -> listOf(ScreenPoint(x, 0f), ScreenPoint(x, bottom)) } +
            ys.flatMap { y -> listOf(ScreenPoint(right, y), ScreenPoint(0f, y)) }
        return candidates.firstOrNull { origin ->
            targets.none { it.x >= origin.x && it.x < origin.x + width && it.y >= origin.y && it.y < origin.y + height }
        }
    }

    fun windowOrigin(point: NormalizedPoint, size: ScreenSize, markerSize: Int): ScreenPoint {
        val center = Coordinates.toScreen(point, size)
        return ScreenPoint(center.x - markerSize / 2f, center.y - markerSize / 2f)
    }
    fun pointFromOrigin(origin: ScreenPoint, size: ScreenSize, markerSize: Int): NormalizedPoint =
        Coordinates.fromLocal(ScreenPoint(origin.x + markerSize / 2f, origin.y + markerSize / 2f), ScreenPoint(0f, 0f), size)

    fun nudgeTarget(point: NormalizedPoint, size: ScreenSize, dx: Int, dy: Int): NormalizedPoint =
        Coordinates.fromLocal(ScreenPoint(dx.toFloat(), dy.toFloat()), Coordinates.toScreen(point, size), size)

    fun compatible(layout: InstrumentLayout, size: ScreenSize): Boolean =
        layout.orientation == size.orientation && (layout.referenceDisplay == null ||
            (layout.referenceDisplay.width == size.width && layout.referenceDisplay.height == size.height))
}

/** The available travel of a window, after subtracting the entire window and an edge margin. */
internal data class OverlayFootprint(val display: ScreenSize, val width: Int, val height: Int, val margin: Int) {
    private val horizontalSpace = (display.width - width).coerceAtLeast(0)
    private val verticalSpace = (display.height - height).coerceAtLeast(0)
    private val left = margin.coerceAtMost(horizontalSpace / 2).toFloat()
    private val top = margin.coerceAtMost(verticalSpace / 2).toFloat()
    private val right = horizontalSpace - left
    private val bottom = verticalSpace - top

    fun clamp(point: ScreenPoint) = ScreenPoint(point.x.coerceIn(left, right), point.y.coerceIn(top, bottom))

    fun originOf(anchor: NormalizedPoint, capsule: OverlayFootprint = this): ScreenPoint =
        clamp(ScreenPoint(left + (right - left) * anchor.x, capsule.top + (capsule.bottom - capsule.top) * anchor.y))

    fun anchorAt(point: ScreenPoint, active: OverlayFootprint = this, previousSide: Float = 1f): NormalizedPoint {
        val midpoint = active.horizontalSpace / 2f
        val side = when {
            point.x < midpoint -> 0f
            point.x > midpoint -> 1f
            else -> previousSide
        }
        return NormalizedPoint(side, ((point.y - top) / (bottom - top).coerceAtLeast(1f)).coerceIn(0f, 1f))
    }

    fun freeAnchorAt(point: ScreenPoint, active: OverlayFootprint, previous: NormalizedPoint): NormalizedPoint {
        val oldOrigin = active.originOf(previous, this)
        return NormalizedPoint(
            if (point.x.roundToInt() == oldOrigin.x.roundToInt()) previous.x
            else ((point.x - active.left) / (active.right - active.left).coerceAtLeast(1f)).coerceIn(0f, 1f),
            if (point.y.roundToInt() == oldOrigin.y.roundToInt()) previous.y
            else ((point.y - top) / (bottom - top).coerceAtLeast(1f)).coerceIn(0f, 1f),
        )
    }

    fun legacyAnchor(x: Float, y: Float): NormalizedPoint {
        val oldY = (y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .24f) * verticalSpace
        return anchorAt(ScreenPoint(0f, oldY)).copy(x = if (x < .5f) 0f else 1f)
    }
}

/** A gesture keeps the raw display coordinates and the starting window dimensions. */
internal class OverlayDrag(
    private val down: ScreenPoint,
    private val origin: ScreenPoint,
    private val touchSlop: Int,
) {
    private var dragging = false

    fun position(pointer: ScreenPoint, footprint: OverlayFootprint): ScreenPoint? {
        val dx = pointer.x - down.x
        val dy = pointer.y - down.y
        if (dx * dx + dy * dy >= touchSlop * touchSlop) dragging = true
        return if (dragging) footprint.clamp(ScreenPoint(origin.x + dx, origin.y + dy)) else null
    }
}
