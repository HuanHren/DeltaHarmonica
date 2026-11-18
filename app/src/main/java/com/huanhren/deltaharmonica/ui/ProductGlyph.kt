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

internal enum class ProductIcon { Home, Library, Play, Settings, Wave }

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
            ProductIcon.Wave -> listOf(5f, 11f, 17f, 11f, 5f).forEachIndexed { index, height ->
                val x = 4f + index * 4
                segment(x, 12f - height / 2, x, 12f + height / 2)
            }
        }
    }
}
