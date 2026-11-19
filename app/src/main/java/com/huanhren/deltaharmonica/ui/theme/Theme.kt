package com.huanhren.deltaharmonica.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanhren.deltaharmonica.ui.StudioAppearance
import com.huanhren.deltaharmonica.ui.StudioSeed
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

enum class ThemeMode(val mode: ColorSchemeMode, val monet: Boolean, val forcedDark: Boolean?) {
    SYSTEM(ColorSchemeMode.System, false, null),
    LIGHT(ColorSchemeMode.Light, false, false),
    DARK(ColorSchemeMode.Dark, false, true),
    MONET_SYSTEM(ColorSchemeMode.MonetSystem, true, null),
    MONET_LIGHT(ColorSchemeMode.MonetLight, true, false),
    MONET_DARK(ColorSchemeMode.MonetDark, true, true),
}

object ColorTokens {
    val LightCanvas = Color(0xFFF1F4F7)
    val DarkCanvas = Color(0xFF10161E)
    val LightCard = Color(0xFFFFFFFF)
    val DarkCard = Color(0xFF1B242E)
    val LightAccent = Color(0xFF16698A)
    val DarkAccent = Color(0xFF83D0E6)
    val LightInk = Color(0xFF202A34)
    val DarkInk = Color(0xFFF1F4F8)
    val LightMuted = Color(0xFF50606E)
    val DarkMuted = Color(0xFFABB9C7)
    val LightTint = Color(0xFFDCEFF6)
    val DarkTint = Color(0xFF213D4B)
    val OnLightTint = Color(0xFF164455)
    val OnDarkTint = Color(0xFFCCEAF4)
    val OverlayPanel = Color(0xF51B242E)
}

object Spacing {
    val small = 8.dp
    val item = 12.dp
    val card = 16.dp
    val page = 20.dp
    val section = 24.dp
    val contentWidth = 760.dp
    val touchTarget
        @Composable @ReadOnlyComposable get() = 48.dp / LocalAppearance.current.scale.coerceAtMost(1f)
}

object ShapeTokens {
    val controlRadius = 16.dp
    val cardRadius = 20.dp
    val pill = RoundedCornerShape(50)
    val card = RoundedCornerShape(cardRadius)
    val raised = 4.dp
}

object StudioTypography {
    val headlineLarge = TextStyle(fontSize = 33.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp)
    val headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
    val titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
    val titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    val bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp)
    val bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    val labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
}

val LocalAppearance = staticCompositionLocalOf { StudioAppearance() }
val LocalDeviceDensity = staticCompositionLocalOf { Density(1f) }

object StudioTheme {
    val colorScheme: Colors @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme
    val typography = StudioTypography
}
internal val Colors.onSurfaceVariant get() = onSurfaceSecondary

@Composable
fun DeltaTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) =
    DeltaTheme(StudioAppearance(theme = mode), content)

@Composable
fun DeltaTheme(appearance: StudioAppearance, content: @Composable () -> Unit) {
    val dark = appearance.theme.forcedDark ?: isSystemInDarkTheme()
    val platformDensity = LocalDensity.current
    val scaled = remember(platformDensity, appearance.scale) {
        Density(platformDensity.density * appearance.scale, platformDensity.fontScale)
    }
    val seed = appearance.seed?.let { Color(it.hex) }
    val dynamicSeed = seed ?: if (Build.VERSION.SDK_INT < 31) Color(StudioSeed.TEAL.hex) else null
    val lightColors = remember(seed) { standardPalette(dark = false, seed = seed) }
    val darkColors = remember(seed) { standardPalette(dark = true, seed = seed) }
    val controller = remember(appearance.theme, dynamicSeed, dark, lightColors, darkColors) {
        ThemeController(
            colorSchemeMode = appearance.theme.mode,
            lightColors = lightColors,
            darkColors = darkColors,
            keyColor = dynamicSeed,
            isDark = dark,
        )
    }
    CompositionLocalProvider(LocalAppearance provides appearance, LocalDensity provides scaled, LocalDeviceDensity provides platformDensity) {
        // Keep the navigation and its scroll state in the same composition on palette changes.
        MiuixTheme(controller = controller, content = content)
    }
}

private fun standardPalette(dark: Boolean, seed: Color?): Colors {
    val t = ColorTokens
    val accent = seed?.let { lerp(it, if (dark) Color.White else Color.Black, if (dark) .49f else .20f) }
    val tint = seed?.let { lerp(it, if (dark) t.DarkCanvas else Color.White, .78f) }
    return if (dark) darkColorScheme(
        primary = accent ?: t.DarkAccent, onPrimary = t.DarkCanvas,
        background = t.DarkCanvas, onBackground = t.DarkInk, surface = t.DarkCard,
        onSurface = t.DarkInk, onSurfaceSecondary = t.DarkMuted,
        primaryContainer = tint ?: t.DarkTint, onPrimaryContainer = t.OnDarkTint,
    ) else lightColorScheme(
        primary = accent ?: t.LightAccent, onPrimary = Color.White,
        background = t.LightCanvas, onBackground = t.LightInk, surface = t.LightCard,
        onSurface = t.LightInk, onSurfaceSecondary = t.LightMuted,
        primaryContainer = tint ?: t.LightTint, onPrimaryContainer = t.OnLightTint,
    )
}
