package com.huanhren.deltaharmonica.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.input.DisplaySnapshot
import com.huanhren.deltaharmonica.input.displaySnapshot
import com.huanhren.deltaharmonica.model.Coordinates
import com.huanhren.deltaharmonica.model.TouchTarget
import com.huanhren.deltaharmonica.ui.theme.*
import kotlin.math.hypot
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun TestScreen(state: AppUiState, activity: Activity,
               onTest: (String, DisplaySnapshot, () -> DisplaySnapshot) -> Unit,
               onHit: (String?) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    ImmersiveScreen(activity)
    val currentCancel by rememberUpdatedState(onCancel)
    DisposableEffect(Unit) { onDispose { currentCancel() } }
    BackHandler { onCancel(); onBack() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    val maxPanelHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.90f).toDp() }
    ScreenCanvas(activity) { geometry ->
        val layout = state.saved
        val selected = layout?.targets?.firstOrNull { it.id == selectedId } ?: layout?.targets?.firstOrNull()
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
                    key(target.id) { TargetMarker(target.copy(label = musicTarget(target)), geometry, target.id == selected?.id) }
                }
            }
        }
        val testMessage = touchTestMessage(state.testMessage, selected)
        if (state.testing) {
            Text(testMessage, color = Color.White, modifier = Modifier.safeDrawingPadding().padding(Spacing.card))
        } else {
            Surface(Modifier.safeDrawingPadding().padding(Spacing.small).widthIn(max = 380.dp).heightIn(max = maxPanelHeight),
                shape = ShapeTokens.card, color = StudioTheme.colorScheme.surface.copy(alpha = 0.96f)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.item),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    TextAction(onClick = { onCancel(); onBack() }) { Text(stringResource(R.string.action_back)) }
                    SectionTitle(stringResource(R.string.touch_test_title))
                    if (!compatible) SupportingText(stringResource(R.string.touch_test_orientation), error = true)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        layout?.targets?.forEach { target ->
                            SecondaryAction(onClick = { selectedId = target.id }) {
                                val label = musicTarget(target)
                                Text(if (target.id == selected?.id) stringResource(R.string.music_checked, label) else label)
                            }
                        }
                    }
                    PrimaryButton(stringResource(R.string.touch_test_start), {
                        if (geometry != null && selected != null) onTest(selected.id, geometry.display) { displaySnapshot(activity) }
                    }, enabled = compatible && selected != null && state.canTestSelectedInput)
                    Text(testMessage)
                    Text(touchHitMessage(state.hitMessage, layout?.targets.orEmpty()), style = StudioTheme.typography.titleMedium)
                    SupportingText(stringResource(R.string.music_backend_status, state.selectedBackend,
                        musicBackendStatus(state.selectedBackendStatus?.message)))
                    SupportingText(stringResource(R.string.touch_test_help))
                }
            }
        }
    }
}

@Composable
private fun touchTestMessage(message: String, target: TouchTarget?): String {
    val label = when (message) {
        "尚未测试" -> R.string.touch_test_not_run
        "请使用布局对应方向的全屏主显示屏" -> R.string.touch_test_orientation
        "屏幕配置变化，测试已取消" -> R.string.touch_test_display_changed
        "Root 触摸已派发 · 请核对下方实际命中" -> R.string.touch_test_root_dispatched
        "系统手势完成 · 请核对下方实际命中" -> R.string.touch_test_system_done
        "系统取消了手势" -> R.string.touch_test_system_cancelled
        "系统拒绝了手势" -> R.string.touch_test_system_rejected
        "等待系统回调超时" -> R.string.touch_test_system_timeout
        "已有手势正在执行" -> R.string.touch_test_system_busy
        "测试已取消" -> R.string.touch_test_cancelled
        else -> null
    }
    if (label != null) return stringResource(label)
    val countdown = message.substringBefore(" 秒后测试 ").toIntOrNull()
    return if (countdown != null && target != null) stringResource(R.string.touch_test_countdown, countdown, musicTarget(target))
    else musicBackendStatus(message)
}

@Composable
private fun touchHitMessage(message: String, targets: List<TouchTarget>): String = when {
    message == "尚未收到测试触摸" -> stringResource(R.string.touch_test_no_touch)
    message == "等待测试触摸…" -> stringResource(R.string.touch_test_wait)
    message == "测试期间收到触摸，但未命中目标" -> stringResource(R.string.touch_test_missed)
    message.startsWith("测试期间收到触摸：") -> {
        val original = message.substringAfter('：')
        val label = targets.find { it.label == original }?.let { musicTarget(it) } ?: original
        stringResource(R.string.touch_test_detected, label)
    }
    else -> musicDiagnostic(message)
}
