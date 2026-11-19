package com.huanhren.deltaharmonica.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

internal object MusicMotion {
    const val feedback = 150
    const val page = 280
    const val cover = 380
    const val dock = 260
}

private data class CoverPalette(val shadow: Color, val field: Color, val ink: Color)

private val palettes = listOf(
    CoverPalette(Color(0xFF122549), Color(0xFF316A9E), Color(0xFFB1DDFF)),
    CoverPalette(Color(0xFF332139), Color(0xFF974E6F), Color(0xFFFFC5C7)),
    CoverPalette(Color(0xFF143737), Color(0xFF2B7471), Color(0xFFBBF1D5)),
    CoverPalette(Color(0xFF422841), Color(0xFF775091), Color(0xFFF2C9F9)),
    CoverPalette(Color(0xFF5A321B), Color(0xFFAD6F3B), Color(0xFFFBE2B3)),
    CoverPalette(Color(0xFF183444), Color(0xFF497C89), Color(0xFFC9E6E7)),
)

private fun coverIndex(id: String) = id.hashCode().ushr(1) % palettes.size
internal fun coverAmbient(id: String): Color = palettes[coverIndex(id)].field

@OptIn(ExperimentalSharedTransitionApi::class)
internal class CoverTransition(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val destinationOrigin: String,
)

internal val LocalCoverTransition = staticCompositionLocalOf<CoverTransition?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.coverTransition(id: String, origin: String, part: String = "cover"): Modifier {
    val transition = LocalCoverTransition.current ?: return this
    val source = if (origin == "player") transition.destinationOrigin else origin
    return with(transition.shared) {
        val state = rememberSharedContentState(key = Triple(id, source, part))
        if (part == "cover") {
            sharedElement(state, transition.visibility, boundsTransform = { _, _ -> tween(MusicMotion.cover) })
        } else {
            sharedBounds(state, transition.visibility, boundsTransform = { _, _ -> tween(MusicMotion.cover) })
        }
    }
}

@Composable
internal fun MusicArtwork(id: String, modifier: Modifier, corner: Dp = 10.dp, origin: String? = null) {
    val palette = palettes[coverIndex(id)]
    val pattern = id.hashCode().ushr(5) % 4
    val texture = remember(id) {
        val random = Random(id.hashCode())
        List(200) { Offset(random.nextFloat(), random.nextFloat()) }
    }
    val artwork = if (origin == null) modifier else modifier.coverTransition(id, origin)
    Box(artwork.clip(RoundedCornerShape(corner)).drawWithCache {
        val side = size.minDimension
        val base = Brush.linearGradient(listOf(palette.shadow, palette.field), Offset.Zero, Offset(size.width, size.height))
        val glow = Brush.radialGradient(
            listOf(palette.ink.copy(alpha = .27f), Color.Transparent),
            Offset(size.width * .78f, size.height * .22f), side * .84f,
        )
        onDrawBehind {
            drawRect(base)
            drawRect(glow)
            val pen = palette.ink
            when (pattern) {
                0 -> {
                    val spindle = Offset(side * .64f, side * .57f)
                    for (ring in 1..12) {
                        drawCircle(pen.copy(alpha = if (ring % 4 == 0) .72f else .25f),
                            radius = side * (.16f + .035f * ring), center = spindle, style = Stroke(side * .005f))
                    }
                    drawCircle(pen.copy(alpha = .83f), side * .105f, spindle)
                    drawCircle(palette.shadow, side * .014f, spindle)
                }
                1 -> {
                    for (line in 0..15) {
                        val y = side * (.045f * line + .19f)
                        val curve = Path().apply {
                            moveTo(-side * .1f, y + side * .25f)
                            cubicTo(side * .32f, y - side * .43f, side * .64f, y + side * .30f, side * 1.1f, y - side * .16f)
                        }
                        drawPath(curve, pen.copy(alpha = if (line % 5 == 0) .87f else .38f),
                            style = Stroke(side * if (line % 5 == 0) .012f else .004f, cap = StrokeCap.Round))
                    }
                }
                2 -> {
                    rotate(-32f) {
                        for (band in 0..8) {
                            val height = side * (.31f + (band % 4) * .105f)
                            val width = side * .055f
                            drawRoundRect(pen.copy(alpha = .28f + band * .06f),
                                Offset(side * (.15f + band * .085f), (side - height) / 2f),
                                Size(width, height), CornerRadius(width / 2))
                        }
                    }
                }
                else -> {
                    val orbit = Offset(side * .32f, side * .67f)
                    for (ring in 0..8) {
                        val radius = side * (.11f + ring * .061f)
                        drawArc(pen.copy(alpha = .80f - ring * .06f), -130f + ring * 7f, 245f, false,
                            Offset(orbit.x - radius, orbit.y - radius), Size(radius * 2, radius * 2),
                            style = Stroke(side * .012f, cap = StrokeCap.Round))
                    }
                }
            }
            if (side >= 96.dp.toPx()) texture.forEach { dot ->
                drawCircle(pen.copy(alpha = .16f), side * .0016f, Offset(dot.x * side, dot.y * side))
            }
        }
    })
}
