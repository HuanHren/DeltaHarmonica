package com.huanhren.deltaharmonica.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Text
import java.util.Locale

@Composable
fun CalibrationScreen(state: AppUiState, activity: Activity, onSelect: (String) -> Unit,
                      onMove: (String, NormalizedPoint) -> Unit, onReset: (LayoutOrientation?) -> Unit,
                      onSave: (ScreenSize) -> Unit, onBack: () -> Unit) {
    ImmersiveScreen(activity)
    var expanded by rememberSaveable { mutableStateOf(true) }
    var resetOpen by rememberSaveable { mutableStateOf(false) }
    val panelHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.89f).toDp() }
    ScreenCanvas(activity) { geometry ->
        val layout = state.draft
        val compatible = geometry != null && layout != null && geometry.display.supported &&
            geometry.display.size.orientation == layout.orientation
        if (geometry != null && layout != null && compatible) {
            layout.targets.forEach { target ->
                key(target.id) {
                    TargetMarker(target, geometry, target.id == state.selectedId,
                        onSelect = { onSelect(target.id) },
                        onMove = if (state.saving) null else { point -> onMove(target.id, point) })
                }
            }
        }
        Column(Modifier.safeDrawingPadding().padding(Spacing.small).widthIn(max = 328.dp)) {
            if (!expanded) {
                PrimaryButton("显示面板", { expanded = true })
            } else {
                ProductCard(Modifier.heightIn(max = panelHeight)) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = onBack, enabled = !state.saving) { Text("返回") }
                            TextButton(onClick = { expanded = false }) { Text("收起面板") }
                        }
                        SectionTitle("布局校准")
                        SupportingText("拖动 12 个标记对准游戏按键。收起面板可露出被挡住的标记。")
                        if (!compatible) {
                            SupportingText("请旋转至${if (layout?.orientation == LayoutOrientation.LANDSCAPE) "横屏" else "竖屏"}并退出分屏。", error = true)
                            if (geometry?.display?.supported == true) {
                                TextButton(onClick = { resetOpen = true }, enabled = !state.saving) { Text("使用当前方向并重排") }
                            }
                        }
                        state.selected?.let { target ->
                            val point = geometry?.let { Coordinates.toScreen(target.position, it.display.size) }
                            SupportingText("${target.label} · " + String.format(Locale.ROOT, "X %.4f  Y %.4f", target.position.x, target.position.y))
                            point?.let { SupportingText(String.format(Locale.ROOT, "屏幕像素  %.0f, %.0f", it.x, it.y)) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            PrimaryButton("保存布局", { geometry?.let { onSave(it.display.size) } },
                                Modifier.testTag("calibration/save"), enabled = compatible && !state.saving && state.loadError == null)
                            if (compatible) OutlinedButton(onClick = { resetOpen = true }, enabled = !state.saving) {
                                Text("恢复默认")
                            }
                        }
                        SupportingText(state.loadError ?: state.message.ifBlank {
                            when {
                                state.saving -> "正在保存…"
                                state.loading -> "正在读取已有布局…"
                                state.dirty -> "草稿未保存"
                                else -> "布局已保存"
                            }
                        }, error = state.loadError != null)
                    }
                }
            }
        }
        if (resetOpen) ProductDialog("恢复默认布局？", { resetOpen = false }, "恢复草稿", {
            resetOpen = false
            onReset(if (compatible) null else geometry?.display?.size?.orientation)
        }) { SupportingText("将恢复草稿中的坐标。点「保存布局」后才会替换已保存的校准。") }
    }
}
