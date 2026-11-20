package com.huanhren.deltaharmonica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.midi.SongCompatibilityReport
import com.huanhren.deltaharmonica.midi.SongCompatibility
import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.online.OnlineCatalog
import com.huanhren.deltaharmonica.online.OnlineEntry
import com.huanhren.deltaharmonica.online.OnlineLibraryException
import com.huanhren.deltaharmonica.online.OnlineScore
import com.huanhren.deltaharmonica.online.OnlineSort
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.ui.theme.Choices
import com.huanhren.deltaharmonica.ui.theme.LocalStudioGutters
import com.huanhren.deltaharmonica.ui.theme.PageTitle
import com.huanhren.deltaharmonica.ui.theme.PrimaryButton
import com.huanhren.deltaharmonica.ui.theme.ProductCard
import com.huanhren.deltaharmonica.ui.theme.ProductSheet
import com.huanhren.deltaharmonica.ui.theme.SectionTitle
import com.huanhren.deltaharmonica.ui.theme.ShapeTokens
import com.huanhren.deltaharmonica.ui.theme.Spacing
import com.huanhren.deltaharmonica.ui.theme.StatusCard
import com.huanhren.deltaharmonica.ui.theme.StudioTheme
import com.huanhren.deltaharmonica.ui.theme.SupportingText
import com.huanhren.deltaharmonica.ui.theme.TextAction
import com.huanhren.deltaharmonica.ui.theme.onSurfaceVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import java.text.NumberFormat

private const val PAGE_SIZE = 30
private typealias OnlineProgress = (Long, Long?) -> Unit
private typealias LoadOnlineDetail = suspend (String, Boolean, OnlineProgress) -> OnlineScore
private typealias InspectOnlineScore = suspend (OnlineScore, OnlineConversionOptions) -> SongCompatibilityReport
private typealias PrepareOnlineSong = suspend (
    OnlineEntry, OnlineScore?, Boolean, Boolean, (OnlineSongStage) -> Unit, OnlineProgress, OnlineConversionOptions?,
) -> Unit

internal data class OnlineDownloadStatus(val revision: Long, val options: OnlineConversionOptions)

@Composable
internal fun OnlineLibraryScreen(
    model: AppViewModel,
    layout: InstrumentLayout?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val songs by model.songs.items.collectAsStateWithLifecycle()
    val problems by model.songs.problems.collectAsStateWithLifecycle()
    val localLoading by model.libraryLoading.collectAsStateWithLifecycle()
    val busy by model.libraryBusy.collectAsStateWithLifecycle()
    val imports by model.imports.state.collectAsStateWithLifecycle()
    val progress by rememberControlsProgress()
    val canStart = playbackControls(progress, PlaybackPreparation.activeRunner != null,
        PlaybackPreparation.cancelPendingStart != null).canStart
    val workspace = model.midiWorkspace
    val defaultOptions = OnlineConversionOptions(workspace.candidatePolicy, workspace.candidateTranspose)
    val downloaded = remember(songs, problems, defaultOptions) {
        buildMap<String, OnlineDownloadStatus> {
            for (item in songs) {
                val source = item.online ?: continue
                if (item.id in problems) continue
                val policy = MonophonyPolicy.entries.firstOrNull { it.name == item.monophonyPolicy } ?: defaultOptions.policy
                val options = OnlineConversionOptions(policy, source.userTranspose)
                for (id in source.remoteIds) {
                    val revision = source.revision(id) ?: continue
                    val current = get(id)
                    if (current == null || revision > current.revision) put(id, OnlineDownloadStatus(revision, options))
                }
            }
        }
    }
    OnlineLibraryContent(
        downloaded = downloaded,
        localLoading = localLoading,
        defaultOptions = defaultOptions,
        canPrepare = canStart && !busy && !imports.active && !workspace.confirmPlayback && !workspace.stateDialog,
        loadCatalog = model.onlineLibrary::catalog,
        loadDetail = model.onlineLibrary::detail,
        clearCache = model.onlineLibrary::clearCache,
        prepare = { entry, score, play, update, stage, receive, options ->
            model.prepareOnlineSong(entry, score, layout, play, stage, receive, update, options)
        },
        inspect = { score, options -> model.inspectOnlineSong(score, layout, options) },
        onBack = onBack,
        onPlay = onPlay,
    )
}

@Composable
internal fun OnlineLibraryContent(
    downloaded: Map<String, OnlineDownloadStatus>,
    canPrepare: Boolean,
    loadCatalog: suspend (Boolean) -> OnlineCatalog,
    loadDetail: LoadOnlineDetail,
    clearCache: suspend () -> Unit,
    prepare: PrepareOnlineSong,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    localLoading: Boolean = false,
    defaultOptions: OnlineConversionOptions = OnlineConversionOptions(MonophonyPolicy.HIGHEST_NOTE, 0),
    inspect: InspectOnlineScore? = null,
) {
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<OnlineCatalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Exception?>(null) }
    var cacheCleared by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<OnlineEntry?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(OnlineSort.NEWEST) }
    var limit by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
    val listState = rememberLazyListState()
    val detail = selected ?: catalog?.entries?.firstOrNull { it.id == selectedId }
    val results by produceState<List<OnlineEntry>?>(null, catalog, query, tag, sort) {
        val currentCatalog = catalog
        val currentQuery = query
        val currentTag = tag
        val currentSort = sort
        value = withContext(Dispatchers.Default) {
            currentCatalog?.search(currentQuery, currentTag, currentSort).orEmpty()
        }
    }
    val tags by produceState(emptyList<String>(), catalog) {
        val currentCatalog = catalog
        value = withContext(Dispatchers.Default) {
            currentCatalog?.entries.orEmpty().asSequence().flatMap { it.tags }.distinct().sorted().toList()
        }
    }

    fun resetPage() {
        limit = PAGE_SIZE
        scope.launch { listState.scrollToItem(0) }
    }

    suspend fun refresh(force: Boolean) {
        loading = true
        cacheCleared = false
        error = null
        try {
            val result = loadCatalog(force)
            catalog = result
            if (tag != null && result.entries.none { tag in it.tags }) tag = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh(false) }

    val closeDetail = {
        selected = null
        selectedId = null
    }
    BackHandler(enabled = detail != null, onBack = closeDetail)

    Box(Modifier.fillMaxSize().background(StudioTheme.colorScheme.background).testTag("online/library"),
        contentAlignment = Alignment.TopCenter) {
        if (detail != null) {
            key(detail.id) {
                OnlineDetailScreen(detail, downloaded[detail.id], localLoading, canPrepare,
                    defaultOptions, loadDetail, prepare, closeDetail, onPlay, inspect)
            }
        } else {
            val keyboard = LocalSoftwareKeyboardController.current
            val focus = LocalFocusManager.current
            OnlineCatalogScreen(
                catalog = catalog, error = error, loading = loading, cacheCleared = cacheCleared,
                results = results, tags = tags,
                downloadedIds = downloaded.keys, query = query, tag = tag, sort = sort, limit = limit, listState = listState,
                onQuery = { query = it; resetPage() },
                onTag = { tag = it; resetPage() },
                onSort = { sort = it; resetPage() },
                onMore = { limit += PAGE_SIZE },
                onSelect = {
                    keyboard?.hide()
                    focus.clearFocus()
                    selected = it
                    selectedId = it.id
                },
                onRefresh = { if (!loading) scope.launch { refresh(true) } },
                onClear = {
                    if (!loading) scope.launch {
                        loading = true
                        error = null
                        try {
                            clearCache()
                            catalog = null
                            cacheCleared = true
                            limit = PAGE_SIZE
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure
                        } finally {
                            loading = false
                        }
                    }
                },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun OnlineCatalogScreen(
    catalog: OnlineCatalog?,
    error: Exception?,
    loading: Boolean,
    cacheCleared: Boolean,
    results: List<OnlineEntry>?,
    tags: List<String>,
    downloadedIds: Set<String>,
    query: String,
    tag: String?,
    sort: OnlineSort,
    limit: Int,
    listState: LazyListState,
    onQuery: (String) -> Unit,
    onTag: (String?) -> Unit,
    onSort: (OnlineSort) -> Unit,
    onMore: () -> Unit,
    onSelect: (OnlineEntry) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val rows = results.orEmpty()
    val sortLabels = mapOf(
        OnlineSort.NEWEST to stringResource(R.string.online_sort_newest),
        OnlineSort.TITLE to stringResource(R.string.online_sort_title),
        OnlineSort.DIFFICULTY to stringResource(R.string.online_sort_difficulty),
    )
    val gutters = LocalStudioGutters.current
    Column(Modifier.widthIn(max = Spacing.contentWidth).fillMaxSize()) {
        OnlineToolbar(onBack, stringResource(R.string.online_library_title)) {
            MusicIconButton(ProductIcon.More, stringResource(R.string.online_catalog_options),
                { menuOpen = true }, Modifier.testTag("online/options"))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.page), verticalAlignment = Alignment.CenterVertically) {
            TextField(query, onQuery, Modifier.weight(1f).testTag("online/search"), singleLine = true,
                label = stringResource(R.string.online_search))
            if (query.isNotEmpty()) {
                MusicIconButton(ProductIcon.Close, stringResource(R.string.library_clear_query), { onQuery("") },
                    Modifier.testTag("online/clear-search"))
            }
        }
        if (tags.isNotEmpty()) {
            LazyRow(Modifier.selectableGroup().padding(top = Spacing.small),
                contentPadding = PaddingValues(horizontal = Spacing.page),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                item(key = "all") {
                    OnlineTag(stringResource(R.string.online_all_tags), tag == null) { onTag(null) }
                }
                items(tags, key = { "tag/$it" }) { value ->
                    OnlineTag(value, tag == value) { onTag(if (tag == value) null else value) }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("online/loading"))
        LazyColumn(Modifier.fillMaxSize().testTag("online/catalog-list"), state = listState,
            contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = Spacing.small,
                bottom = Spacing.card + gutters.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            if (catalog?.stale == true) item(key = "cached") {
                Box(Modifier.testTag("online/cached")) {
                StatusCard(stringResource(R.string.online_cached),
                    catalog.staleReason ?: stringResource(R.string.online_cached_hint)) {
                        TextAction(onRefresh, enabled = !loading) { Text(stringResource(R.string.online_refresh)) }
                    }
                }
            }
            if (error != null) item(key = "catalog-error") {
                StatusCard(stringResource(R.string.online_catalog_error), onlineErrorMessage(error), error = true) {
                    TextAction(onRefresh) { Text(stringResource(R.string.retry)) }
                }
            }
            if (!loading && error == null && results?.isEmpty() == true) item(key = "empty") {
                val filtered = !query.isBlank() || tag != null
                val title = when {
                    cacheCleared -> R.string.online_cache_cleared
                    filtered -> R.string.library_no_matches
                    else -> R.string.online_catalog_empty
                }
                Box(Modifier.testTag("online/empty")) {
                    SectionTitle(stringResource(title))
                }
                if (filtered) TextAction(onClick = { onQuery(""); onTag(null) }) {
                    Text(stringResource(R.string.library_clear_filters))
                } else TextAction(onRefresh) { Text(stringResource(R.string.online_refresh)) }
            }
            items(count = minOf(rows.size, limit), key = { "song/${rows[it].id}" }) { index ->
                val entry = rows[index]
                OnlineCatalogRow(entry, entry.id in downloadedIds) { onSelect(entry) }
            }
            if (rows.size > limit) item(key = "more") {
                TextAction(onMore, Modifier.testTag("online/load-more")) { Text(stringResource(R.string.online_more)) }
            }
        }
    }
    if (menuOpen) ProductSheet(stringResource(R.string.online_catalog_options), { menuOpen = false }) {
        SectionTitle(stringResource(R.string.online_sort))
        Choices(OnlineSort.entries, sort, sortLabels::getValue, {
            onSort(it)
            menuOpen = false
        })
        TextAction(onClick = { menuOpen = false; onRefresh() }, enabled = !loading) {
            Text(stringResource(R.string.online_refresh))
        }
        TextAction(onClick = { menuOpen = false; onClear() }, enabled = !loading) {
            Text(stringResource(R.string.online_clear_cache))
        }
    }
}

@Composable
private fun OnlineToolbar(onBack: () -> Unit, title: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.page).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MusicIconButton(ProductIcon.Back, stringResource(R.string.action_back), onBack,
            Modifier.testTag("online/back"))
        Box(Modifier.weight(1f)) { title?.let { PageTitle(it) } }
        actions()
    }
}

@Composable
private fun OnlineTag(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = StudioTheme.colorScheme
    Box(Modifier.clip(ShapeTokens.pill).background(if (selected) colors.primaryContainer else colors.surface)
        .selectable(selected, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = Spacing.touchTarget).padding(horizontal = Spacing.item),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            style = StudioTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OnlineCatalogRow(entry: OnlineEntry, downloaded: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(ShapeTokens.card).clickable(role = Role.Button, onClick = onClick)
        .testTag("online/song/${entry.id}").padding(horizontal = Spacing.small, vertical = Spacing.item),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(entry.title, Modifier.weight(1f), style = StudioTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (downloaded) {
                Text(stringResource(R.string.online_downloaded), color = StudioTheme.colorScheme.primary,
                    style = StudioTheme.typography.bodySmall)
            }
        }
        SupportingText(entry.composer.ifBlank { stringResource(R.string.online_unknown_composer) }, maxLines = 1)
        SupportingText(stringResource(R.string.online_summary, musicDuration(entry.durationMs), onlineBpm(entry.bpm),
            onlineDifficulty(entry.difficulty)), maxLines = 1)
    }
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(StudioTheme.colorScheme.onSurface.copy(alpha = .072f)))
}

private data class PrepareAction(val play: Boolean, val update: Boolean = false, val refreshDetail: Boolean = false)

@Composable
private fun OnlineDetailScreen(
    entry: OnlineEntry,
    local: OnlineDownloadStatus?,
    localLoading: Boolean,
    canPrepare: Boolean,
    defaultOptions: OnlineConversionOptions,
    loadDetail: LoadOnlineDetail,
    prepare: PrepareOnlineSong,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    inspect: InspectOnlineScore?,
) {
    val scope = rememberCoroutineScope()
    val downloaded = local != null
    val baseline = local?.options ?: defaultOptions
    var customPolicy by rememberSaveable { mutableStateOf<MonophonyPolicy?>(null) }
    var customTranspose by rememberSaveable { mutableStateOf<Int?>(null) }
    val chosen = OnlineConversionOptions(customPolicy ?: baseline.policy, customTranspose ?: baseline.transpose)
    var editing by remember { mutableStateOf<OnlineConversionOptions?>(null) }
    var detail by remember { mutableStateOf<OnlineScore?>(null) }
    var detailError by remember { mutableStateOf<Exception?>(null) }
    var detailLoading by remember { mutableStateOf(!downloaded) }
    var detailAttempt by remember { mutableIntStateOf(0) }
    var detailPercent by remember { mutableStateOf<Int?>(null) }
    var stage by remember { mutableStateOf<OnlineSongStage?>(null) }
    var transferPercent by remember { mutableStateOf<Int?>(null) }
    var actionError by remember { mutableStateOf<Exception?>(null) }
    var failedAction by remember { mutableStateOf<PrepareAction?>(null) }
    val compatibility by produceState<SongCompatibilityReport?>(null, detail, chosen) {
        value = null
        val score = detail
        if (inspect != null && score != null) value = inspect(score, chosen)
    }

    LaunchedEffect(entry.id, downloaded, localLoading, detailAttempt) {
        if (downloaded) {
            detailLoading = false
            detailError = null
            return@LaunchedEffect
        }
        detailLoading = true
        if (localLoading) return@LaunchedEffect
        detailError = null
        detailPercent = null
        try {
            detail = loadDetail(entry.id, detailAttempt > 0) { read, total ->
                detailPercent = downloadPercent(read, total)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            detailError = failure
        } finally {
            detailLoading = false
        }
    }

    fun run(action: PrepareAction) {
        if (!canPrepare || stage != null || detailLoading || (!downloaded && detail == null)) return
        val options = chosen.takeUnless { it == baseline }
        actionError = null
        failedAction = null
        transferPercent = null
        stage = when {
            action.update || action.refreshDetail -> OnlineSongStage.DOWNLOADING
            downloaded -> OnlineSongStage.ACTIVATING
            else -> OnlineSongStage.CONVERTING
        }
        scope.launch {
            try {
                if (action.refreshDetail) {
                    detail = loadDetail(entry.id, true) { read, total ->
                        transferPercent = downloadPercent(read, total)
                    }
                }
                prepare(detail?.entry ?: entry, detail, action.play, action.update, { stage = it },
                    { read, total -> transferPercent = downloadPercent(read, total) }, options)
                if (action.play) onPlay()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                actionError = failure
                failedAction = action.copy(
                    refreshDetail = !action.update && (action.refreshDetail || stage == OnlineSongStage.CONVERTING),
                )
            } finally {
                stage = null
            }
        }
    }

    val current = detail?.entry ?: entry
    val gutters = LocalStudioGutters.current
    val ready = canPrepare && stage == null && !detailLoading && (downloaded || detail != null)
    Column(Modifier.widthIn(max = Spacing.contentWidth).fillMaxSize()) {
        OnlineToolbar(onBack)
        LazyColumn(Modifier.fillMaxSize().testTag("online/details"),
            contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page,
                bottom = Spacing.card + gutters.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(Spacing.card)) {
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    PageTitle(current.title)
                    SupportingText(current.composer.ifBlank { stringResource(R.string.online_unknown_composer) })
                    if (downloaded) {
                        Text(stringResource(R.string.online_available_offline), Modifier.testTag("online/downloaded"),
                            color = StudioTheme.colorScheme.primary, style = StudioTheme.typography.labelLarge)
                    }
                }
            }
            if (detailLoading) item(key = "detail-loading") {
                OnlineTransfer(stringResource(R.string.online_loading_details), detailPercent)
            }
            detailError?.let { failure ->
                item(key = "detail-error") {
                    StatusCard(stringResource(R.string.online_detail_error), onlineErrorMessage(failure), error = true) {
                        TextAction(onClick = { detailAttempt++ }, modifier = Modifier.testTag("online/retry-detail")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
            detail?.takeIf { it.stale }?.let { cached ->
                item(key = "cached-detail") {
                    StatusCard(stringResource(R.string.online_cached_details),
                        cached.staleReason ?: stringResource(R.string.online_cached_details_hint))
                }
            }
            stage?.let { step ->
                item(key = "transfer") {
                    val label = when (step) {
                        OnlineSongStage.DOWNLOADING -> R.string.online_downloading
                        OnlineSongStage.CONVERTING -> R.string.online_converting
                        OnlineSongStage.SAVING -> R.string.online_saving
                        OnlineSongStage.ACTIVATING -> R.string.online_activating
                    }
                    OnlineTransfer(stringResource(label), transferPercent.takeIf { step == OnlineSongStage.DOWNLOADING })
                }
            }
            actionError?.let { failure ->
                item(key = "transfer-error") {
                    StatusCard(stringResource(R.string.online_transfer_error), onlineErrorMessage(failure), error = true) {
                        failedAction?.let { action ->
                            TextAction(onClick = { run(action) }, modifier = Modifier.testTag("online/retry-transfer"), enabled = ready) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    PrimaryButton(stringResource(if (downloaded) R.string.online_play else R.string.online_download_play),
                        { run(PrepareAction(play = true)) }, Modifier.fillMaxWidth().testTag("online/play"), enabled = ready)
                    if (!downloaded) {
                        TextAction(onClick = { run(PrepareAction(play = false)) },
                            modifier = Modifier.fillMaxWidth().testTag("online/download"), enabled = ready) {
                            Text(stringResource(R.string.online_download_only))
                        }
                    } else if (local.revision < maxOf(entry.updatedAt, current.updatedAt)) {
                        TextAction(onClick = { run(PrepareAction(play = false, update = true)) },
                            modifier = Modifier.fillMaxWidth().testTag("online/update"), enabled = ready) {
                            Text(stringResource(R.string.online_download_update))
                        }
                    }
                    ArrowPreference(title = stringResource(R.string.online_conversion_options),
                        summary = stringResource(R.string.online_conversion_summary,
                            stringResource(onlinePolicyLabel(chosen.policy)),
                            stringResource(R.string.import_transpose, chosen.transpose)),
                        modifier = Modifier.testTag("online/conversion-options"), enabled = canPrepare && stage == null,
                        onClick = { editing = chosen })
                    if (!canPrepare && stage == null) SupportingText(stringResource(R.string.online_play_blocked))
                }
            }
            if (inspect != null && detail != null) item(key = "compatibility") {
                ProductCard {
                    val report = compatibility
                    if (report == null) SupportingText(stringResource(R.string.score_compatibility_checking))
                    else {
                        val invalid = report.status == SongCompatibility.INCOMPATIBLE
                        SupportingText(stringResource(report.status.label()), error = invalid)
                        report.errors.forEach { SupportingText(musicDiagnostic(it), error = true) }
                        if (report.unmappedCount > 0)
                            SupportingText(stringResource(R.string.score_unmapped, report.unmappedCount), error = true)
                    }
                }
            }
            item(key = "metadata") { OnlineDetails(current) }
        }
    }
    editing?.let { draft ->
        OnlineConversionSheet(draft, baseline, downloaded,
            onChange = { editing = it },
            onDismiss = { editing = null },
            onApply = {
                customPolicy = draft.policy.takeUnless { draft == baseline }
                customTranspose = draft.transpose.takeUnless { draft == baseline }
                editing = null
            },
        )
    }
}

@Composable
private fun OnlineConversionSheet(
    draft: OnlineConversionOptions,
    baseline: OnlineConversionOptions,
    downloaded: Boolean,
    onChange: (OnlineConversionOptions) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    ProductSheet(stringResource(R.string.online_conversion_options), onDismiss) {
        SupportingText(stringResource(if (downloaded) R.string.online_conversion_local_hint else R.string.online_conversion_hint))
        Text(stringResource(R.string.import_monophony), style = StudioTheme.typography.titleMedium)
        val policies = MonophonyPolicy.entries.associateWith { stringResource(onlinePolicyLabel(it)) }
        Choices(MonophonyPolicy.entries, draft.policy, policies::getValue,
            { onChange(draft.copy(policy = it)) })
        QuantityControl(
            stringResource(R.string.import_transpose_label),
            stringResource(R.string.import_transpose, draft.transpose),
            draft.transpose > -24,
            draft.transpose < 24,
            { onChange(draft.copy(transpose = draft.transpose - 1)) },
            { onChange(draft.copy(transpose = draft.transpose + 1)) },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            PrimaryButton(stringResource(R.string.import_done), onApply, Modifier.testTag("online/apply-conversion"))
            if (draft != baseline) {
                TextAction(onClick = { onChange(baseline) }) { Text(stringResource(R.string.online_conversion_reset)) }
            }
        }
    }
}

private fun onlinePolicyLabel(policy: MonophonyPolicy): Int = when (policy) {
    MonophonyPolicy.HIGHEST_NOTE -> R.string.import_highest
    MonophonyPolicy.LOWEST_NOTE -> R.string.import_lowest
    MonophonyPolicy.LONGEST_NOTE -> R.string.import_longest
    MonophonyPolicy.TRACK_SELECTION -> R.string.import_track_policy
}

@Composable
private fun OnlineDetails(entry: OnlineEntry) {
    ProductCard {
        if (entry.uploader.isNotBlank()) OnlineMetadata(stringResource(R.string.online_uploader), entry.uploader)
        OnlineMetadata(stringResource(R.string.online_tempo), onlineBpm(entry.bpm))
        OnlineMetadata(stringResource(R.string.online_length), musicDuration(entry.durationMs))
        OnlineMetadata(stringResource(R.string.online_note_count), musicNumber(entry.noteCount))
        OnlineMetadata(stringResource(R.string.online_difficulty), onlineDifficulty(entry.difficulty))
        if (entry.updatedAt > 0) OnlineMetadata(stringResource(R.string.online_updated), musicDate(entry.updatedAt))
        if (entry.tags.isNotEmpty()) {
            SupportingText(stringResource(R.string.online_tags))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                entry.tags.forEach { tag ->
                    Text(tag, Modifier.clip(ShapeTokens.pill).background(StudioTheme.colorScheme.background)
                        .padding(horizontal = Spacing.small, vertical = 4.dp), style = StudioTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun OnlineMetadata(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically) {
        SupportingText(label, Modifier.weight(1f))
        Text(value, Modifier.weight(1.2f), style = StudioTheme.typography.bodyMedium)
    }
}

@Composable
private fun OnlineTransfer(label: String, percent: Int?) {
    ProductCard(Modifier.testTag("online/transfer")) {
        Text(if (percent == null) label else stringResource(R.string.online_transfer_percent, label, percent),
            style = StudioTheme.typography.labelLarge)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = percent?.div(100f))
    }
}

private fun downloadPercent(bytes: Long, total: Long?): Int? =
    total?.takeIf { it > 0 }?.let { (bytes.toDouble() * 100 / it).toInt().coerceIn(0, 100) }

@Composable
private fun onlineErrorMessage(error: Exception): String = when (error) {
    is OnlineLibraryException -> stringResource(when (error.kind) {
        OnlineLibraryException.Kind.INVALID_ID -> R.string.online_error_invalid_id
        OnlineLibraryException.Kind.NOT_FOUND -> R.string.online_error_gone
        OnlineLibraryException.Kind.RATE_LIMITED -> R.string.online_error_rate_limit
        OnlineLibraryException.Kind.SERVER -> R.string.online_error_server
        OnlineLibraryException.Kind.NETWORK -> R.string.online_error_network
        OnlineLibraryException.Kind.TIMEOUT -> R.string.online_error_timeout
        OnlineLibraryException.Kind.PROTOCOL -> R.string.online_error_protocol
    })
    is OnlineSongException -> error.message ?: stringResource(R.string.online_error_generic)
    else -> stringResource(R.string.online_error_generic)
}

@Composable
private fun onlineBpm(bpm: Double): String = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0])
    .apply { maximumFractionDigits = 2 }.format(bpm)

@Composable
private fun onlineDifficulty(value: Int): String = if (value == 0) stringResource(R.string.online_unknown_difficulty)
    else stringResource(R.string.online_difficulty_level, value)
