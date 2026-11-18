package com.huanhren.deltaharmonica.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.huanhren.deltaharmonica.input.DisplaySnapshot
import com.huanhren.deltaharmonica.input.displaySnapshot
import com.huanhren.deltaharmonica.model.*
import kotlin.math.hypot
import com.huanhren.deltaharmonica.ui.theme.*

@Composable
fun TestScreen(state: AppUiState, activity: Activity,
               onTest: (String, DisplaySnapshot, () -> DisplaySnapshot) -> Unit,
               onHit: (String?) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    ImmersiveScreen(activity)
    DisposableEffect(Unit) { onDispose { onCancel() } }
    BackHandler { onCancel(); onBack() }
    var selectedId by rememberSaveable { mutableStateOf(state.saved?.targets?.firstOrNull()?.id) }
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    val maxPanelHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.90f).toDp() }
    ScreenCanvas(activity) { geometry ->
        val layout = state.saved
        val compatible = layout != null && geometry != null && geometry.display.supported &&
            layout.orientation == geometry.display.size.orientation
        if (geometry != null && layout != null && compatible) {
            Box(Modifier.fillMaxSize().pointerInput(state.testing, geometry, layout) {
                if (state.testing) detectTapGestures { local ->
                    val nearest = layout.targets.minByOrNull { target ->
                        val center = Coordinates.toLocal(target.position, geometry.origin, geometry.display.size)
                        hypot(local.x - center.x, local.y - center.y)
                    }
                    val hit = nearest?.takeIf {
                        val center = Coordinates.toLocal(it.position, geometry.origin, geometry.display.size)
                        hypot(local.x - center.x, local.y - center.y) <= radius
                    }
                    onHit(hit?.label)
                }
            }) {
                layout.targets.forEach { target ->
                    key(target.id) { TargetMarker(target, geometry, target.id == selectedId) }
                }
            }
        }
        if (state.testing) {
            Text(state.testMessage, color = Color.White,
                modifier = Modifier.safeDrawingPadding().padding(16.dp))
        } else {
            Surface(Modifier.safeDrawingPadding().padding(8.dp).widthIn(max = 380.dp).heightIn(max = maxPanelHeight),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onCancel(); onBack() }) { Text("返回") }
                    Text("测试已保存布局", style = MaterialTheme.typography.titleLarge)
                    if (!compatible) Text("请使用布局对应方向的全屏主显示屏。", color = MaterialTheme.colorScheme.error)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        layout?.targets?.forEach { target ->
                            FilterChip(selected = target.id == selectedId, onClick = { selectedId = target.id }, label = { Text(target.label) })
                        }
                    }
                    Button(enabled = compatible && state.selectedBackendStatus?.available == true,
                        onClick = { if (geometry != null) selectedId?.let { onTest(it, geometry.display) { displaySnapshot(activity) } } }) {
                        Text("3 秒后测试一次触摸")
                    }
                    Text(state.testMessage)
                    Text(state.hitMessage, style = MaterialTheme.typography.titleSmall)
                    Text("${state.selectedBackend}：${state.selectedBackendStatus?.message.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    Text("测试期间请勿手动触屏。面板会暂时隐藏；返回或切换应用会取消待执行测试。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
