package com.huanhren.deltaharmonica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun HomeScreen(state: AppUiState, model: AppViewModel, onCalibrate: () -> Unit, onLibrary: () -> Unit,
               onPlayer: () -> Unit, onImport: () -> Unit, onOverlay: () -> Unit) {
    val songs by model.songs.items.collectAsStateWithLifecycle()
    val loading by model.libraryLoading.collectAsStateWithLifecycle()
    val error by model.libraryError.collectAsStateWithLifecycle()
    val recent = model.preferences?.recent?.collectAsStateWithLifecycle()?.value.orEmpty()
    val history = remember(songs, recent) { songs.filter { it.id in recent }.sortedByDescending { recent[it.id] }.take(3) }
    val progress by PlaybackPreparation.progress.collectAsStateWithLifecycle()
    val workspace = model.midiWorkspace
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    Page("DeltaHarmonica") {
        ProductCard(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.card)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(if (workspace.report != null) "当前曲目" else if (history.isNotEmpty()) "最近演奏" else "游戏口琴",
                        color = accent, style = MaterialTheme.typography.labelLarge)
                    Text(workspace.name.ifBlank { history.firstOrNull()?.displayName ?: "选一首曲目" },
                        color = accent, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (progress.phase != PlaybackStatus.READY) {
                        Text(progress.state, color = accent, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(if (loading) "正在读取曲库…" else "曲库 · ${songs.size} 首", color = accent)
                    }
                }
                ProductGlyph(ProductIcon.Wave, accent, Modifier.size(54.dp))
            }
            PrimaryButton(if (workspace.report != null) "开始演奏" else "选曲演奏",
                if (workspace.report != null) onPlayer else onLibrary, Modifier.testTag("home/play"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.testTag("home/import")) { Text("导入 MIDI / JSON") }
            OutlinedButton(onClick = onLibrary) { Text("打开曲库") }
            if (model.rootUiAllowed) OutlinedButton(onClick = onOverlay) { Text("游戏悬浮窗") }
        }
        error?.let { StatusCard("曲库读取失败", it, error = true) { TextButton(onClick = model::refreshLibrary) { Text("重试") } } }
        if (history.isNotEmpty()) {
            ProductCard {
                SectionTitle("最近播放记录")
                history.forEach { item ->
                    TextButton(onClick = {
                        model.activateSong(item.id) { problem ->
                            if (problem == null) onPlayer() else workspace.message = problem
                        }
                    }) { Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (workspace.message.isNotBlank()) SupportingText(workspace.message)
            }
        }
        ProductCard {
            SectionTitle("演奏环境")
            listOf("Root", "Accessibility").forEach { backend ->
                val status = state.backends.firstOrNull { it.first == backend }?.second
                SupportingText("${if (state.selectedBackend == backend) "已选 · " else ""}$backend  ·  ${status?.message ?: "正在读取"}")
            }
            if (state.loading) SupportingText("正在读取校准布局…")
            else if (state.loadError != null) SupportingText(state.loadError, error = true)
            else SupportingText(if (state.saved != null) "已保存 ${state.saved.targets.size} 个校准点" else "尚未保存校准布局")
            TextButton(onClick = if (state.loadError == null) onCalibrate else model::load,
                enabled = !state.loading && !state.saving) { Text(if (state.loadError == null) "校准布局" else "重试读取") }
        }
    }
}
