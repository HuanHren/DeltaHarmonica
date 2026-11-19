package com.huanhren.deltaharmonica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Text

private val StudioTab.icon: ProductIcon
    get() = when (this) {
        StudioTab.HOME -> ProductIcon.Home
        StudioTab.LIBRARY -> ProductIcon.Library
        StudioTab.PLAYER -> ProductIcon.Play
        StudioTab.SETTINGS -> ProductIcon.Settings
    }

@Composable
internal fun FloatingStudioBar(tabs: List<StudioTab>, selected: StudioTab?, showLabels: Boolean,
                               modifier: Modifier = Modifier, onSelect: (StudioTab) -> Unit) {
    val palette = StudioTheme.colorScheme
    val animate = !LocalAppearance.current.reducedEffects
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val select by rememberUpdatedState(onSelect)
    val activeIndex = tabs.indexOf(selected)
    var dragSlot by remember(tabs) { mutableIntStateOf(-1) }
    val indicator = if (dragSlot >= 0) dragSlot else activeIndex
    val position by animateFloatAsState(indicator.coerceAtLeast(0).toFloat(), if (animate) spring(.83f, 345f) else snap(), label = "studio-tab")
    val scale by animateFloatAsState(if (dragSlot >= 0) .975f else 1f, if (animate) spring(.76f, 450f) else snap(), label = "studio-drag")
    val pill = ShapeTokens.pill
    BoxWithConstraints(modifier.testTag("studio/capsule")
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(ShapeTokens.raised, pill)
        .studioSurface(pill).clip(pill)
        .pointerInput(tabs, rtl) {
            if (tabs.size > 1) detectHorizontalDragGestures(
                onDragStart = { dragSlot = studioTabAt(it.x, size.width.toFloat(), tabs.size, rtl) },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    dragSlot = studioTabAt(change.position.x, size.width.toFloat(), tabs.size, rtl)
                },
                onDragEnd = {
                    tabs.getOrNull(dragSlot)?.let(select)
                    dragSlot = -1
                },
                onDragCancel = { dragSlot = -1 },
            )
        }.selectableGroup().padding(4.dp)) {
        val slotWidth = maxWidth / tabs.size
        val height = Spacing.touchTarget + (if (showLabels) 12.dp else 0.dp)
        if (indicator >= 0) {
            Box(Modifier.offset(x = slotWidth * position).width(slotWidth).height(height).padding(2.dp)
                .background(palette.primaryContainer.copy(alpha = .90f), pill))
        }
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, tab ->
                val label = stringResource(tab.labelRes())
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val pressScale by animateFloatAsState(if (pressed) .90f else 1f, if (animate) spring(.74f, 500f) else snap(), label = "studio-press")
                val tint = if (index == indicator) palette.onPrimaryContainer else palette.onSurfaceSecondary
                Column(Modifier.weight(1f).height(height).testTag("nav/${tab.route}").clip(pill)
                    .selectable(tab == selected, interactionSource = interaction, indication = null,
                        role = Role.Tab, onClick = { select(tab) })
                    .semantics { if (!showLabels) contentDescription = label }
                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    ProductGlyph(tab.icon, tint)
                    if (showLabels) Text(label, color = tint, style = StudioTypography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
