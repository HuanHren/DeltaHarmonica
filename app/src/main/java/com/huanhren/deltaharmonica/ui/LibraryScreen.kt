package com.huanhren.deltaharmonica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.data.SongLibraryItem
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun LibraryScreen(model: AppViewModel, onImport: () -> Unit, onPlayer: () -> Unit) {
    val songs by model.songs.items.collectAsStateWithLifecycle()
    val problems by model.songs.problems.collectAsStateWithLifecycle()
    val loading by model.libraryLoading.collectAsStateWithLifecycle()
    val readError by model.libraryError.collectAsStateWithLifecycle()
    val busy by model.libraryBusy.collectAsStateWithLifecycle()
    val importState by model.imports.state.collectAsStateWithLifecycle()
    val progress by PlaybackPreparation.progress.collectAsStateWithLifecycle()
    val recent = model.preferences?.recent?.collectAsStateWithLifecycle()?.value.orEmpty()
    val workspace = model.midiWorkspace
    val canEdit = !busy && !importState.active &&
        playbackControls(progress, PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null).canStart
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SongSort.IMPORTED) }
    var actionsId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(songs, query, sort, recent) { visibleSongs(songs, query, sort, recent) }
    val songIds = remember(songs) { songs.mapTo(HashSet()) { it.id } }
    val orphanProblems = remember(problems, songIds) { problems.filterKeys { it !in songIds }.values.toList() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = Spacing.contentWidth).fillMaxSize().testTag("library/list"),
            contentPadding = PaddingValues(horizontal = Spacing.page, vertical = Spacing.item),
            verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
            item(key = "heading") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PageTitle("曲库")
                        SupportingText("${songs.size} 首曲目")
                    }
                    PrimaryButton("导入", onImport, Modifier.testTag("library/import"))
                }
            }
            if (songs.isNotEmpty()) item(key = "filter") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("library/search"),
                        singleLine = true, label = { Text("搜索曲名") })
                    Choices(SongSort.entries, sort, { it.label }, { sort = it })
                }
            }
            if (loading || busy) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (readError != null) item(key = "read-error") {
                StatusCard("曲库读取失败", readError.orEmpty(), error = true) {
                    TextButton(onClick = model::refreshLibrary) { Text("重试") }
                }
            }
            if (error != null) item(key = "action-error") { StatusCard("操作失败", error.orEmpty(), error = true) }
            items(orphanProblems) { problem -> StatusCard("曲库记录异常", problem, error = true) }
            if (visible.isEmpty() && !loading && readError == null) item(key = "empty") {
                if (query.isNotBlank()) StatusCard("没有匹配曲目", "清除搜索或换一个曲名。") {
                    TextButton(onClick = { query = "" }) { Text("清除搜索") }
                } else StatusCard("曲库还没有曲目", "导入 MIDI 或 JSON 脚本，随后可在游戏里直接选曲。")
            }
            items(visible, key = SongLibraryItem::id) { item ->
                LibraryRow(item, workspace.songId == item.id, recent[item.id], problems[item.id], canEdit,
                    onSelect = {
                        error = null
                        model.activateSong(item.id) { failure ->
                            error = failure
                            if (failure == null) onPlayer()
                        }
                    }, onActions = { actionsId = item.id })
            }
        }
    }
    songs.firstOrNull { it.id == actionsId }?.let { item ->
        val close = { actionsId = null }
        ProductSheet(item.displayName, close) {
            TextButton(onClick = { close(); renameId = item.id; name = item.displayName }, enabled = canEdit) { Text("重命名") }
            TextButton(onClick = { close(); deleteId = item.id }, enabled = canEdit) { Text("删除曲目") }
            TextButton(onClick = close) { Text("关闭") }
        }
    }
    songs.firstOrNull { it.id == renameId }?.let { item ->
        ProductDialog("重命名曲目", { renameId = null }, "保存", {
            renameId = null
            model.renameSong(item.id, name) { error = it }
        }, enabled = canEdit && name.isNotBlank()) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("曲名") })
        }
    }
    songs.firstOrNull { it.id == deleteId }?.let { item ->
        ProductDialog("删除曲目？", { deleteId = null }, "删除", {
            deleteId = null
            model.deleteSong(item.id) { error = it }
        }, enabled = canEdit) { SupportingText("将从曲库移除《${item.displayName}》。") }
    }
}

@Composable
private fun LibraryRow(item: SongLibraryItem, current: Boolean, recent: Long?, problem: String?, enabled: Boolean,
                       onSelect: () -> Unit, onActions: () -> Unit) {
    ProductCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Column(Modifier.weight(1f).clip(ShapeTokens.card).clickable(enabled = enabled && problem == null, onClick = onSelect)
                .heightIn(min = Spacing.touchTarget), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                if (current || recent != null) {
                    Text(if (current) "当前曲目" else "最近演奏 · ${songDate(requireNotNull(recent))}",
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                Text(item.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SupportingText("${item.sourceType} · ${item.eventCount} 音 · ${songDuration(item.durationMs)}")
                SupportingText("导入于 ${songDate(item.createdAt)}")
                problem?.let { SupportingText(it, error = true) }
            }
            TextButton(onClick = onActions, enabled = enabled) { Text("管理") }
        }
    }
}
