package com.huanhren.deltaharmonica.ui.theme

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.huanhren.deltaharmonica.ui.SurfaceEffects
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.glass.GlassFloatingToolbarDefaults
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassColorBlendMode
import top.yukonga.miuix.kmp.material.GlassColorLayer
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.GlassShape

internal val LocalStudioBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
internal fun effectiveSurfaceEffects(): SurfaceEffects {
    val context = LocalContext.current
    val lowRam = remember(context) { context.getSystemService(ActivityManager::class.java).isLowRamDevice }
    return LocalAppearance.current.effects(Build.VERSION.SDK_INT, lowRam)
}

@Composable
internal fun Modifier.studioSurface(shape: RoundedCornerShape, backdrop: Backdrop? = LocalStudioBackdrop.current): Modifier {
    val effects = effectiveSurfaceEffects()
    val palette = StudioTheme.colorScheme
    val fill = palette.surface
    val dark = palette.background.luminance() < .2f
    val solid = Modifier.background(fill, shape)
        .border(1.dp, palette.onSurface.copy(alpha = .12f), shape)
    if (backdrop == null) return then(solid)

    if (effects.glass) {
        val glassShape = remember(shape) {
            GlassShape(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart)
        }
        val tint = fill.copy(alpha = if (dark) .86f else .81f)
        val blurRadius = if (effects.blur) 16.dp else 0.dp
        val material = remember(blurRadius, tint) {
            GlassMaterial(blurRadius, GlassColorLayer(tint, GlassColorBlendMode.SrcOver))
        }
        return glassPanel(
            backdrop = backdrop,
            shape = glassShape,
            style = GlassDefaults.style(dark),
            material = material,
            stroke = GlassFloatingToolbarDefaults.stroke(),
            shadow = null,
            shading = false,
            fallback = solid,
        )
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { blur(16.dp.toPx()) },
        onDrawSurface = { drawRect(fill.copy(alpha = .89f)) },
    ).border(1.dp, palette.onSurface.copy(alpha = .12f), shape)
}
