package com.huanhren.deltaharmonica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class ProductIcon {
    Home, Library, Play, Settings, Wave, Search, Add, More, Close, Down, Stop, Pause, Filter,
    Overlay, Calibration, Shield, Info, Back,
}

@Composable
internal fun ProductGlyph(icon: ProductIcon, tint: Color, modifier: Modifier = Modifier.size(21.dp)) {
    Canvas(modifier) {
        val scale = size.minDimension / 24f
        val lineWidth = 1.8f * scale
        fun pt(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun segment(x: Float, y: Float, ex: Float, ey: Float) {
            drawLine(tint, pt(x, y), pt(ex, ey), lineWidth, StrokeCap.Round)
        }
        when (icon) {
            ProductIcon.Home -> {
                val outline = Path().apply {
                    moveTo(3 * scale, 11 * scale); lineTo(12 * scale, 4 * scale); lineTo(21 * scale, 11 * scale)
                    lineTo(19 * scale, 11 * scale); lineTo(19 * scale, 20 * scale); lineTo(5 * scale, 20 * scale)
                    lineTo(5 * scale, 11 * scale); close()
                }
                drawPath(outline, tint, style = Stroke(lineWidth, join = StrokeJoin.Round))
                segment(12f, 15f, 12f, 20f)
            }
            ProductIcon.Library -> {
                segment(5f, 5f, 5f, 19f)
                segment(10f, 5f, 10f, 19f)
                segment(15f, 5f, 19f, 19f)
            }
            ProductIcon.Play -> {
                val triangle = Path().apply {
                    moveTo(8 * scale, 5 * scale); lineTo(20 * scale, 12 * scale)
                    lineTo(8 * scale, 19 * scale); close()
                }
                drawPath(triangle, tint, style = Stroke(lineWidth, join = StrokeJoin.Round))
            }
            ProductIcon.Settings -> {
                segment(4f, 7f, 20f, 7f)
                segment(4f, 17f, 20f, 17f)
                drawCircle(tint, 2.8f * scale, pt(9f, 7f))
                drawCircle(tint, 2.8f * scale, pt(15f, 17f))
            }
            ProductIcon.Search -> {
                drawCircle(tint, 5.8f * scale, pt(10f, 10f), style = Stroke(lineWidth))
                segment(14.5f, 14.5f, 20f, 20f)
            }
            ProductIcon.Add -> { segment(5f, 12f, 19f, 12f); segment(12f, 5f, 12f, 19f) }
            ProductIcon.More -> listOf(5f, 12f, 19f).forEach { drawCircle(tint, 1.65f * scale, pt(it, 12f)) }
            ProductIcon.Close -> { segment(6f, 6f, 18f, 18f); segment(18f, 6f, 6f, 18f) }
            ProductIcon.Down -> { segment(6f, 9f, 12f, 15f); segment(12f, 15f, 18f, 9f) }
            ProductIcon.Stop -> drawRoundRect(tint, pt(7f, 7f), androidx.compose.ui.geometry.Size(10 * scale, 10 * scale),
                androidx.compose.ui.geometry.CornerRadius(1.6f * scale))
            ProductIcon.Pause -> { segment(9f, 5f, 9f, 19f); segment(15f, 5f, 15f, 19f) }
            ProductIcon.Filter -> {
                segment(4f, 6f, 20f, 6f); segment(7f, 12f, 17f, 12f); segment(10f, 18f, 14f, 18f)
            }
            ProductIcon.Wave -> listOf(5f, 11f, 17f, 11f, 5f).forEachIndexed { index, height ->
                val x = 4f + index * 4
                segment(x, 12f - height / 2, x, 12f + height / 2)
            }
            ProductIcon.Overlay -> {
                drawRoundRect(tint, pt(3f, 5f), androidx.compose.ui.geometry.Size(18 * scale, 14 * scale),
                    androidx.compose.ui.geometry.CornerRadius(2.5f * scale), style = Stroke(lineWidth))
                drawRoundRect(tint, pt(12f, 11f), androidx.compose.ui.geometry.Size(6 * scale, 5 * scale),
                    androidx.compose.ui.geometry.CornerRadius(scale))
            }
            ProductIcon.Calibration -> {
                drawCircle(tint, 6.5f * scale, pt(12f, 12f), style = Stroke(lineWidth))
                drawCircle(tint, 1.4f * scale, pt(12f, 12f))
                segment(12f, 3f, 12f, 6f)
                segment(12f, 18f, 12f, 21f)
                segment(3f, 12f, 6f, 12f)
                segment(18f, 12f, 21f, 12f)
            }
            ProductIcon.Shield -> {
                val outline = Path().apply {
                    moveTo(12 * scale, 3 * scale)
                    lineTo(20 * scale, 6 * scale)
                    lineTo(20 * scale, 11 * scale)
                    quadraticTo(20 * scale, 17 * scale, 12 * scale, 21 * scale)
                    quadraticTo(4 * scale, 17 * scale, 4 * scale, 11 * scale)
                    lineTo(4 * scale, 6 * scale)
                    close()
                }
                drawPath(outline, tint, style = Stroke(lineWidth, join = StrokeJoin.Round))
                segment(9f, 12f, 11f, 14f)
                segment(11f, 14f, 15f, 10f)
            }
            ProductIcon.Info -> {
                drawCircle(tint, 8f * scale, pt(12f, 12f), style = Stroke(lineWidth))
                drawCircle(tint, 1f * scale, pt(12f, 8.5f))
                segment(12f, 12f, 12f, 16f)
            }
            ProductIcon.Back -> {
                segment(16f, 5f, 9f, 12f)
                segment(9f, 12f, 16f, 19f)
            }
        }
    }
}
