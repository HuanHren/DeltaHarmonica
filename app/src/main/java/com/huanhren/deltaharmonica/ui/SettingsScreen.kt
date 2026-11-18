package com.huanhren.deltaharmonica.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.input.BackendAvailability
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun SettingsScreen(state: AppUiState, model: AppViewModel, overlayGranted: Boolean, onOverlayPermission: () -> Unit,
                   onCalibrate: () -> Unit, onCalibrationOverlay: () -> Unit, onTest: () -> Unit) {
    val context = LocalContext.current
    val preferences = model.preferences
    val theme = preferences?.theme?.collectAsStateWithLifecycle()?.value ?: ThemeMode.SYSTEM
    val speed = preferences?.speed?.collectAsStateWithLifecycle()?.value ?: 1.0
    val volumeStop = preferences?.volumeStop?.collectAsStateWithLifecycle()?.value ?: true
    val progress by PlaybackPreparation.progress.collectAsStateWithLifecycle()
    val idle = playbackControls(progress, PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null).canStart
    var settingsError by remember { mutableStateOf<String?>(null) }
    Page("设置") {
        ProductCard {
            SectionTitle("外观")
            Choices(ThemeMode.entries, theme, { it.label }, { preferences?.setTheme(it) })
        }
        ProductCard {
            SectionTitle("演奏后端")
            Column(Modifier.selectableGroup()) {
                state.backends.forEach { (name, status) ->
                    val enabled = idle && status.availability != BackendAvailability.NOT_IMPLEMENTED &&
                        (name != "Root" || model.rootUiAllowed)
                    Row(Modifier.fillMaxWidth().selectable(state.selectedBackend == name, enabled = enabled,
                        role = Role.RadioButton, onClick = { model.selectBackend(name) })
                        .heightIn(min = Spacing.touchTarget).padding(vertical = Spacing.small),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                        RadioButton(state.selectedBackend == name, onClick = null, enabled = enabled)
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            SupportingText(status.message)
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                if (model.rootUiAllowed) TextButton(onClick = model::checkRoot, enabled = idle && !state.checkingRoot) {
                    Text(if (state.checkingRoot) "正在检查 Root…" else "检查 Root 授权")
                }
                TextButton(onClick = model::refreshBackends) { Text("刷新状态") }
            }
        }
        ProductCard {
            SectionTitle("演奏偏好")
            SupportingText("默认速度")
            Choices(listOf(1.0, 2.0), speed, { "${it.toInt()}×" }, model::setDefaultSpeed, enabled = idle)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("音量减停止", style = MaterialTheme.typography.titleMedium)
                    SupportingText("演奏中按音量减可停止后续触摸。")
                }
                Switch(checked = volumeStop, onCheckedChange = { preferences?.setVolumeStop(it) }, enabled = idle)
            }
        }
        ProductCard {
            SectionTitle("权限与悬浮窗")
            SupportingText(if (overlayGranted) "悬浮窗已授权。可从首页打开游戏内播放器。"
                else "悬浮窗未授权。请在系统设置中允许 DeltaHarmonica 显示在其他应用上层。")
            if (model.rootUiAllowed) TextButton(onClick = onOverlayPermission) { Text("管理悬浮窗权限") }
            SupportingText("使用无障碍后端时，在系统无障碍中开启“DeltaHarmonica 触摸测试”。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TextButton(onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    catch (_: ActivityNotFoundException) { settingsError = "请从系统设置打开无障碍页面。" }
                }) { Text("打开无障碍设置") }
                if (model.rootUiAllowed && state.backends.any { it.first == "Root" && it.second.available }) {
                    TextButton(onClick = model::enableAccessibilityWithRoot, enabled = idle && !state.enablingAccessibility) {
                        Text(if (state.enablingAccessibility) "正在启用无障碍…" else "用 Root 启用无障碍")
                    }
                }
            }
            settingsError?.let { SupportingText(it, error = true) }
            state.accessibilityGrantMessage.takeIf { it.isNotEmpty() }?.let { SupportingText(it) }
        }
        ProductCard {
            SectionTitle("校准管理")
            SupportingText(state.loadError ?: when {
                state.loading -> "正在读取布局…"
                state.saved != null -> "已保存布局 · ${state.saved.targets.size} 个标记"
                else -> "进入横屏口琴界面后校准 12 个触摸点。"
            }, error = state.loadError != null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TextButton(onClick = onCalibrate, enabled = idle && !state.loading && !state.saving && state.loadError == null) { Text("校准布局") }
                if (model.rootUiAllowed) TextButton(onClick = onCalibrationOverlay, enabled = idle) { Text("游戏内校准") }
                TextButton(onClick = onTest, enabled = idle && state.saved != null) { Text("测试已保存布局") }
            }
        }
        ProductCard {
            SectionTitle("关于 DeltaHarmonica")
            val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            SupportingText("版本 ${version.orEmpty()} · Android")
        }
    }
}
