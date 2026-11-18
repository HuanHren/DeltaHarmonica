package com.huanhren.deltaharmonica.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDark
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLight

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

object ColorTokens {
    val LightCanvas = Color(0xFFF2F4F8)
    val DarkCanvas = Color(0xFF101217)
    val LightCard = Color(0xFFFFFFFF)
    val DarkCard = Color(0xFF1B1F29)
    val LightAccent = Color(0xFF285AC7)
    val DarkAccent = Color(0xFFAFC5FF)
    val LightInk = Color(0xFF161B29)
    val DarkInk = Color(0xFFE9EDF8)
    val LightMuted = Color(0xFF535E73)
    val DarkMuted = Color(0xFFBAC4D8)
    val LightTint = Color(0xFFE3EBFF)
    val DarkTint = Color(0xFF28395E)
    val OnLightTint = Color(0xFF193A84)
    val OnDarkTint = Color(0xFFDAE4FF)
    val OverlayPanel = Color(0xF51B1F29)
}

object Spacing {
    val small = 8.dp
    val item = 12.dp
    val card = 16.dp
    val page = 20.dp
    val section = 24.dp
    val contentWidth = 720.dp
    val touchTarget = 48.dp
}

object ShapeTokens {
    val controlRadius = 16.dp
    val cardRadius = 24.dp
    val pill = RoundedCornerShape(50)
    val card = RoundedCornerShape(cardRadius)
}

private val productType = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
)

@Composable
fun DeltaTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val t = ColorTokens
    val colors = if (dark) darkColorScheme(
        background = t.DarkCanvas, surface = t.DarkCard, onBackground = t.DarkInk, onSurface = t.DarkInk,
        onSurfaceVariant = t.DarkMuted, primary = t.DarkAccent, onPrimary = t.DarkCanvas,
        primaryContainer = t.DarkTint, onPrimaryContainer = t.OnDarkTint, surfaceContainer = t.DarkCard,
    ) else lightColorScheme(
        background = t.LightCanvas, surface = t.LightCard, onBackground = t.LightInk, onSurface = t.LightInk,
        onSurfaceVariant = t.LightMuted, primary = t.LightAccent, onPrimary = Color.White,
        primaryContainer = t.LightTint, onPrimaryContainer = t.OnLightTint, surfaceContainer = t.LightCard,
    )
    val miuix = if (dark) miuixDark(primary = colors.primary, onPrimary = colors.onPrimary,
        background = colors.background, onBackground = colors.onBackground, surface = colors.surface, onSurface = colors.onSurface)
    else miuixLight(primary = colors.primary, onPrimary = colors.onPrimary,
        background = colors.background, onBackground = colors.onBackground, surface = colors.surface, onSurface = colors.onSurface)
    MiuixTheme(colors = miuix) {
        MaterialTheme(colorScheme = colors, typography = productType, shapes = Shapes(
            small = RoundedCornerShape(ShapeTokens.controlRadius), medium = ShapeTokens.card, large = ShapeTokens.card,
        ), content = content)
    }
}
