package com.huanhren.deltaharmonica.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Surface

@Composable
internal fun TextAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                        content: @Composable RowScope.() -> Unit) {
    val colors = StudioTheme.colorScheme
    Button(onClick, modifier, enabled, minHeight = Spacing.touchTarget,
        insideMargin = PaddingValues(horizontal = Spacing.item, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(Color.Transparent, Color.Transparent)) {
        androidx.compose.runtime.CompositionLocalProvider(
            top.yukonga.miuix.kmp.theme.LocalContentColor provides (if (enabled) colors.primary else colors.disabledOnSurface),
        ) { content() }
    }
}

@Composable
internal fun SecondaryAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                             content: @Composable RowScope.() -> Unit) {
    val colors = StudioTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = ShapeTokens.pill,
        color = colors.surface, contentColor = if (enabled) colors.onSurface else colors.disabledOnSurface,
        border = BorderStroke(1.dp, colors.onSurface.copy(alpha = .16f))) {
        Row(Modifier.heightIn(min = Spacing.touchTarget).padding(horizontal = Spacing.card),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
    }
}
