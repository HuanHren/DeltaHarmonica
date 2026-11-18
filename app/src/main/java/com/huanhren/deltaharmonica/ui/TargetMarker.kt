package com.huanhren.deltaharmonica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanhren.deltaharmonica.model.*
import kotlin.math.roundToInt

@Composable
fun BoxScope.TargetMarker(target: TouchTarget, geometry: CanvasGeometry, isSelected: Boolean,
                 onSelect: (() -> Unit)? = null, onMove: ((NormalizedPoint) -> Unit)? = null) {
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    val latestTarget by rememberUpdatedState(target)
    val latestMove by rememberUpdatedState(onMove)
    val latestSelect by rememberUpdatedState(onSelect)
    var modifier = Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
        val point = Coordinates.toLocal(target.position, geometry.origin, geometry.display.size)
        IntOffset((point.x - radius).roundToInt(), (point.y - radius).roundToInt())
    }.requiredSize(56.dp).semantics {
        contentDescription = target.label
        selected = isSelected
    }
    if (onSelect != null) modifier = modifier.clickable(onClick = onSelect)
    if (onMove != null) modifier = modifier.pointerInput(target.id, geometry) {
        var dragPoint: ScreenPoint? = null
        detectDragGestures(onDragStart = {
            latestSelect?.invoke()
            dragPoint = Coordinates.toLocal(latestTarget.position, geometry.origin, geometry.display.size)
        }, onDragEnd = { dragPoint = null }, onDragCancel = { dragPoint = null }) { change, amount ->
            change.consume()
            val local = dragPoint ?: Coordinates.toLocal(latestTarget.position, geometry.origin, geometry.display.size)
            // Keep the whole touch handle reachable. This constraint belongs to the editor, not the model.
            val x = (local.x + amount.x).coerceIn(radius, (geometry.size.width - radius).coerceAtLeast(radius))
            val y = (local.y + amount.y).coerceIn(radius, (geometry.size.height - radius).coerceAtLeast(radius))
            dragPoint = ScreenPoint(x, y)
            latestMove?.invoke(Coordinates.fromLocal(ScreenPoint(x, y), geometry.origin, geometry.display.size))
        }
    }
    val colors = MaterialTheme.colorScheme
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(if (isSelected) 52.dp else 46.dp)
            .background(if (isSelected) colors.primary else Color(0xDD273754), CircleShape)
            .border(if (isSelected) 2.dp else 1.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center) {
            Text(target.label, color = if (isSelected) colors.onPrimary else Color.White, fontSize = 11.sp, maxLines = 1)
        }
    }
}
