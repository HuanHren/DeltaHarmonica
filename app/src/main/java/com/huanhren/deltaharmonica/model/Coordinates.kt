package com.huanhren.deltaharmonica.model

import kotlinx.serialization.Serializable

@Serializable
data class NormalizedPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) }
}

data class ScreenPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite()) }
}

data class ScreenSize(val width: Int, val height: Int) {
    init { require(width > 1 && height > 1) }
    val orientation: LayoutOrientation
        get() = if (width >= height) LayoutOrientation.LANDSCAPE else LayoutOrientation.PORTRAIT
}

object Coordinates {
    fun toScreen(point: NormalizedPoint, size: ScreenSize): ScreenPoint =
        ScreenPoint(point.x * (size.width - 1), point.y * (size.height - 1))

    fun toLocal(point: NormalizedPoint, origin: ScreenPoint, size: ScreenSize): ScreenPoint {
        val screen = toScreen(point, size)
        return ScreenPoint(screen.x - origin.x, screen.y - origin.y)
    }

    fun fromLocal(point: ScreenPoint, origin: ScreenPoint, size: ScreenSize): NormalizedPoint =
        NormalizedPoint(
            ((point.x + origin.x) / (size.width - 1)).coerceIn(0f, 1f),
            ((point.y + origin.y) / (size.height - 1)).coerceIn(0f, 1f),
        )
}
