package com.huanhren.deltaharmonica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.data.SongLibraryItem
import com.huanhren.deltaharmonica.data.SongSourceType
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun LibraryScreen(model: AppViewModel, onImport: () -> Unit, onPlayer: (String) -> Unit, onMenu: () -> Unit) {
    val songs by model.songs.items.collectAsStateWithLifecycle()
    val problems by model.songs.problems.collectAsStateWithLifecycle()
    val loading by model.libraryLoading.collectAsStateWithLifecycle()
    val readError by model.libraryError.collectAsStateWithLifecycle()
    val busy by model.libraryBusy.collectAsStateWithLifecycle()
    val importState by model.imports.state.collectAsStateWithLifecycle()
    val progress by rememberControlsProgress()
    val recent = model.preferences?.recent?.collectAsStateWithLifecycle()?.value.orEmpty()
    val workspace = model.midiWorkspace
    val canEdit = !busy && !importState.active &&
        playbackControls(progress, PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null).canStart
    var searching by rememberSaveable { mutableStateOf(false) }
    var focusSearch by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SongSort.IMPORTED) }
    var source by rememberSaveable { mutableStateOf<SongSourceType?>(null) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var actionsId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableIntStateOf(0) }
    val visible = remember(songs, query, sort, recent, source) {
        visibleSongs(songs.filter { source == null || it.sourceType == source }, query, sort, recent)
    }
    val byId = remember(songs) { songs.associateBy(SongLibraryItem::id) }
    val history = remember(byId, recent) { recent.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }.take(8) }
    val orphanProblems = remember(problems, byId) { problems.filterKeys { it !in byId }.values.toList() }
    val listState = rememberLazyListState()
    val compact by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 26 } }
    val headerProgress by animateFloatAsState(if (compact) 1f else 0f,
        tween(if (LocalAppearance.current.reducedEffects) 0 else MusicMotion.feedback), label = "library title")
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun dismissSearch() {
        query = ""
        searching = false
        keyboard?.hide()
    }
    fun select(item: SongLibraryItem, origin: String) {
        keyboard?.hide()
        error = null
        if (workspace.songId == item.id && workspace.report != null) onPlayer(origin)
        else model.activateSong(item.id) { failure ->
            error = failure
            if (failure == null) onPlayer(origin)
        }
    }
    BackHandler(searching) { dismissSearch() }
    LaunchedEffect(query) { if (searching) listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize().background(StudioTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = Spacing.contentWidth).fillMaxWidth().padding(horizontal = Spacing.page)
            .padding(top = 4.dp, bottom = 6.dp).heightIn(min = if (searching) 64.dp else (64f - headerProgress * 12f).dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                TextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus).testTag("library/search"),
                    singleLine = true, label = stringResource(R.string.library_search))
                MusicIconButton(ProductIcon.Close, stringResource(if (query.isEmpty()) R.string.action_close else R.string.library_clear_query),
                    { if (query.isEmpty()) dismissSearch() else query = "" }, Modifier.testTag("library/clear-search"))
                LaunchedEffect(focusSearch) {
                    if (focusSearch) {
                        focus.requestFocus()
                        keyboard?.show()
                        focusSearch = false
                    }
                }
            } else {
                Text(stringResource(R.string.nav_library), Modifier.weight(1f), style = StudioTypography.headlineLarge.copy(fontSize = (32f - headerProgress * 9f).sp))
                MusicIconButton(ProductIcon.Search, stringResource(R.string.library_search), { focusSearch = true; searching = true }, Modifier.testTag("library/open-search"))
                MusicIconButton(ProductIcon.Add, stringResource(R.string.library_import_track), onImport,
                    Modifier.testTag("library/import"), background = StudioTheme.colorScheme.primaryContainer,
                    foreground = StudioTheme.colorScheme.onPrimaryContainer)
                MusicIconButton(ProductIcon.More, stringResource(R.string.studio_routes), onMenu, Modifier.testTag("studio/menu"))
            }
        }
        LazyColumn(
            Modifier.widthIn(max = Spacing.contentWidth).fillMaxSize().testTag("library/list"),
            state = listState,
            contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = 5.dp, bottom = 16.dp),
        ) {
            item(key = "start") { Spacer(Modifier.height(1.dp)) }
            if (loading || busy) item(key = "loading") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            readError?.let {
                item(key = "read-error") {
                    StatusCard(stringResource(R.string.studio_error_library), stringResource(R.string.library_read_retry), error = true) {
                        TextAction(onClick = model::refreshLibrary) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            error?.let {
                item(key = "action-error") {
                    StatusCard(stringResource(R.string.library_operation_failed), musicDiagnostic(it), error = true)
                }
            }
            if (notice != 0) item(key = "notice") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SupportingText(stringResource(notice), Modifier.weight(1f))
                    MusicIconButton(ProductIcon.Close, stringResource(R.string.action_close), { notice = 0 })
                }
            }
            items(orphanProblems) {
                StatusCard(stringResource(R.string.library_record_error), stringResource(R.string.library_read_retry), error = true)
            }
            if (!searching && history.isNotEmpty()) item(key = "history") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 20.dp, top = 8.dp)) {
                    SectionTitle(stringResource(R.string.library_sort_recent))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(history, key = SongLibraryItem::id) { item ->
                            Column(Modifier.width(120.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = (canEdit || workspace.songId == item.id) && item.id !in problems,
                                    role = Role.Button) { select(item, "recent") },
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                MusicArtwork(item.id, Modifier.fillMaxWidth().aspectRatio(1f), 14.dp, "recent")
                                Text(item.displayName, Modifier.coverTransition(item.id, "recent", "title"),
                                    style = StudioTypography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SupportingText(stringResource(sourceLabel(item.sourceType)), maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (songs.isNotEmpty()) item(key = "all") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(if (searching) R.string.library_search_results else R.string.library_all_tracks))
                    Spacer(Modifier.weight(1f))
                    SupportingText(pluralStringResource(R.plurals.library_track_count, visible.size, visible.size))
                    MusicIconButton(ProductIcon.Filter, stringResource(R.string.library_filters), { filters = true },
                        Modifier.testTag("library/filters"))
                }
            }
            if (visible.isEmpty() && !loading && readError == null) item(key = "empty") {
                if (songs.isNotEmpty() && (query.isNotBlank() || source != null)) {
                    Column(Modifier.padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.library_no_matches))
                        SupportingText(stringResource(R.string.library_no_matches_hint))
                        TextAction(onClick = { query = ""; source = null }) { Text(stringResource(R.string.library_clear_filters)) }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MusicArtwork("DeltaHarmonica", Modifier.size(108.dp), 20.dp)
                        SectionTitle(stringResource(R.string.library_empty))
                        SupportingText(stringResource(R.string.library_empty_hint))
                        PrimaryButton(stringResource(R.string.library_import_track), onImport)
                    }
                }
            }
            items(visible, key = SongLibraryItem::id) { item ->
                LibraryRow(item, workspace.songId == item.id, item.id in problems, canEdit,
                    onSelect = { select(item, "list") }, onActions = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        actionsId = item.id
                    })
            }
        }
    }
    if (filters) ProductSheet(stringResource(R.string.library_filters), { filters = false }) {
        val sources = listOf(null, SongSourceType.MIDI, SongSourceType.JSON)
        val sourceNames = sources.map { stringResource(it?.let(::sourceLabel) ?: R.string.library_source_all) }
        Choices(sources, source, { sourceNames[sources.indexOf(it)] }, { source = it })
        val sortNames = SongSort.entries.map {
            stringResource(when (it) {
                SongSort.IMPORTED -> R.string.library_sort_imported
                SongSort.NAME -> R.string.library_sort_name
                SongSort.RECENT -> R.string.library_sort_recent
            })
        }
        Choices(SongSort.entries, sort, { sortNames[it.ordinal] }, { sort = it })
    }
    byId[actionsId]?.let { item ->
        ProductSheet(item.displayName, { actionsId = null }) {
            ArrowPreference(title = stringResource(R.string.nav_player), modifier = Modifier.testTag("library/action-open"), enabled = item.id !in problems && (canEdit || workspace.songId == item.id),
                onClick = { actionsId = null; select(item, "list") })
            ArrowPreference(title = stringResource(R.string.library_rename), enabled = canEdit,
                onClick = { actionsId = null; renameId = item.id; name = item.displayName })
            ArrowPreference(title = stringResource(R.string.library_delete_track), enabled = canEdit,
                onClick = { actionsId = null; deleteId = item.id })
        }
    }
    byId[renameId]?.let { item ->
        ProductDialog(stringResource(R.string.library_rename_track), { renameId = null }, stringResource(R.string.action_save), {
            renameId = null
            model.renameSong(item.id, name) { failure ->
                error = failure
                notice = if (failure == null) R.string.library_renamed else 0
            }
        }, enabled = canEdit && name.isNotBlank()) {
            TextField(name, { name = it }, modifier = Modifier.fillMaxWidth().testTag("library/rename-input"), singleLine = true, label = stringResource(R.string.library_sort_name))
        }
    }
    byId[deleteId]?.let { item ->
        ProductDialog(stringResource(R.string.library_delete_question), { deleteId = null }, stringResource(R.string.library_delete), {
            deleteId = null
            model.deleteSong(item.id) { failure ->
                error = failure
                notice = if (failure == null) R.string.library_deleted else 0
            }
        }, enabled = canEdit) { SupportingText(stringResource(R.string.library_delete_detail, item.displayName)) }
    }
}

private fun sourceLabel(type: SongSourceType) =
    if (type == SongSourceType.MIDI) R.string.library_source_midi else R.string.library_source_json

@Composable
private fun LibraryRow(item: SongLibraryItem, current: Boolean, unavailable: Boolean, editable: Boolean,
                       onSelect: () -> Unit, onActions: () -> Unit) {
    val colors = StudioTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = (editable || current) && !unavailable, role = Role.Button, onClick = onSelect)
            .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MusicArtwork(item.id, Modifier.size(49.dp), origin = "list")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.displayName, Modifier.coverTransition(item.id, "list", "title"),
                    style = StudioTypography.titleMedium, color = if (current) colors.primary else colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val type = stringResource(sourceLabel(item.sourceType))
                SupportingText(if (unavailable) stringResource(R.string.library_record_error)
                    else stringResource(R.string.library_track_meta, type, musicDuration(item.durationMs)), maxLines = 1, error = unavailable)
            }
        }
        MusicIconButton(ProductIcon.More, stringResource(R.string.library_track_actions, item.displayName), onActions,
            Modifier.testTag("library/actions/${item.id}"), foreground = colors.onSurfaceVariant)
    }
    Spacer(Modifier.fillMaxWidth().padding(start = 61.dp).height(1.dp).background(colors.onSurface.copy(alpha = .072f)))
}
