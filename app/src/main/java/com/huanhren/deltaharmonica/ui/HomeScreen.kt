package com.huanhren.deltaharmonica.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.data.SongLibraryItem
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun HomeScreen(state: AppUiState, model: AppViewModel, onCalibrate: () -> Unit, onLibrary: () -> Unit,
               onPlayer: () -> Unit, onSettings: () -> Unit, onImport: () -> Unit, onOverlay: () -> Unit, onMenu: () -> Unit = {}) {
    val songs by model.songs.items.collectAsStateWithLifecycle()
    val loading by model.libraryLoading.collectAsStateWithLifecycle()
    val error by model.libraryError.collectAsStateWithLifecycle()
    val recent = model.preferences?.recent?.collectAsStateWithLifecycle()?.value.orEmpty()
    val history = remember(songs, recent) { songs.filter { it.id in recent }.sortedByDescending { recent[it.id] }.take(4) }
    val progress by rememberControlsProgress()
    val workspace = model.midiWorkspace
    val appearance = LocalAppearance.current
    val current = workspace.report != null
    val openSong: (SongLibraryItem) -> Unit = { item ->
        model.activateSong(item.id) { problem ->
            if (problem == null) onPlayer() else workspace.message = problem
        }
    }
    val highlight = workspace.name.ifBlank { history.firstOrNull()?.displayName ?: "" }
    val heading = if (current) R.string.studio_current_song else if (history.isNotEmpty()) R.string.studio_last_played else R.string.nav_library
    val title = highlight.ifBlank { stringResource(R.string.studio_empty_song) }
    val openFeatured: () -> Unit = when {
        current -> onPlayer
        history.isNotEmpty() -> { { openSong(history.first()) } }
        else -> onLibrary
    }
    Page(stringResource(R.string.nav_home), actions = {
        MusicIconButton(ProductIcon.More, stringResource(R.string.studio_routes), onMenu, Modifier.testTag("studio/menu"))
    }) {
        val libraryCaption = if (loading) stringResource(R.string.studio_library_wait)
            else stringResource(R.string.studio_selected_library, songs.size)
        StudioFeature(title, stringResource(heading), libraryCaption,
            if (progress.phase == PlaybackStatus.READY) null else stringResource(sessionStatus(progress)), current || history.isNotEmpty(), openFeatured)
        AnimatedContent(targetState = appearance.layout, transitionSpec = {
            if (appearance.reducedEffects) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(180)) togetherWith fadeOut(tween(120))
        }, label = "studio-home-layout") { arrangement ->
            Column(Modifier.fillMaxWidth().testTag("home/layout/${arrangement.name}"),
                verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                when (arrangement) {
                    HomeLayout.WORKBENCH -> {
                        HomeShortcuts(model.rootUiAllowed, onImport, onLibrary, onOverlay)
                        RecentPieces(history, openSong)
                    }
                    HomeLayout.LIST -> {
                        HomeShortcuts(model.rootUiAllowed, onImport, onLibrary, onOverlay)
                        ProductCard {
                            SectionTitle(stringResource(R.string.studio_last_played))
                            val list = (history + songs).distinctBy(SongLibraryItem::id).take(6)
                            if (list.isEmpty()) SupportingText(stringResource(R.string.studio_no_recent))
                            list.forEach { song -> PieceLink(song) { openSong(song) } }
                            TextAction(onLibrary) { Text(stringResource(R.string.studio_search)) }
                        }
                    }
                    HomeLayout.GRID -> {
                        val actions = buildList {
                            add(Triple(R.string.nav_library, ProductIcon.Library, onLibrary))
                            add(Triple(R.string.import_music, ProductIcon.Wave, onImport))
                            add(Triple(R.string.play_mode, ProductIcon.Play, onPlayer))
                            add(Triple(R.string.performance_settings, ProductIcon.Settings, onSettings))
                            if (model.rootUiAllowed) add(Triple(R.string.overlay_settings, ProductIcon.Wave, onOverlay))
                        }
                        actions.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                                row.forEach { (label, icon, click) ->
                                    ProductCard(Modifier.weight(1f).then(if (label == R.string.import_music) Modifier.testTag("home/import") else Modifier)
                                        .clip(ShapeTokens.card).clickable(onClick = click)) {
                                        ProductGlyph(icon, StudioTheme.colorScheme.primary)
                                        Text(stringResource(label), style = StudioTypography.titleMedium)
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        RecentPieces(history, openSong)
                    }
                }
            }
        }
        error?.let { StatusCard(stringResource(R.string.studio_error_library), it, error = true) {
            TextAction(model::refreshLibrary) { Text(stringResource(R.string.retry)) }
        } }
        if (workspace.message.isNotBlank()) SupportingText(musicDiagnostic(workspace.message))
        ProductCard {
            SectionTitle(stringResource(R.string.studio_setup))
            listOf("Root", "Accessibility").forEach { backend ->
                state.backends.firstOrNull { it.first == backend }?.second?.let { SupportingText("$backend · ${backendSummary(backend, it)}") }
            }
            val layout = state.saved
            SupportingText(state.loadError ?: when {
                state.loading -> stringResource(R.string.layout_loading)
                layout == null -> stringResource(R.string.layout_missing)
                else -> stringResource(R.string.layout_ready, layout.targets.size)
            }, error = state.loadError != null)
            TextAction(if (state.loadError == null) onCalibrate else model::load, enabled = !state.loading && !state.saving) {
                Text(stringResource(if (state.loadError == null) R.string.calibrate else R.string.retry_read))
            }
        }
    }
}

@Composable
private fun StudioFeature(title: String, eyebrow: String, caption: String, playback: String?,
                          selected: Boolean, onPlay: () -> Unit) {
    val palette = StudioTheme.colorScheme
    val ink = palette.onPrimaryContainer
    Box(Modifier.fillMaxWidth().clip(ShapeTokens.card)
        .background(Brush.linearGradient(listOf(palette.primaryContainer, palette.surface)))) {
        StudioWave(Modifier.matchParentSize(), palette.primary)
        Column(Modifier.padding(Spacing.page), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(eyebrow, style = StudioTypography.labelLarge, color = ink)
            Text(title, style = StudioTypography.headlineLarge, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SupportingText(caption)
            playback?.let { Text(it, style = StudioTypography.bodyMedium, color = ink) }
            Spacer(Modifier.height(Spacing.small))
            PrimaryButton(stringResource(if (selected) R.string.studio_play else R.string.studio_pick_play),
                onPlay, Modifier.testTag("home/play"))
        }
    }
}

@Composable
private fun StudioWave(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val center = size.height * .57f
        repeat(45) { index ->
            val section = index / 44f
            val x = size.width * (.42f + .58f * section)
            val extent = size.height * (.05f + .25f * abs(sin(index * 1.92f))) * section
            drawLine(color.copy(alpha = .12f + section * .11f), Offset(x, center - extent), Offset(x, center + extent),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun HomeShortcuts(overlay: Boolean, onImport: () -> Unit, onLibrary: () -> Unit, onOverlay: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        SecondaryAction(onImport, Modifier.testTag("home/import")) { Text(stringResource(R.string.import_music)) }
        SecondaryAction(onLibrary) { Text(stringResource(R.string.open_library)) }
        if (overlay) SecondaryAction(onOverlay) { Text(stringResource(R.string.open_overlay)) }
    }
}

@Composable
private fun RecentPieces(history: List<SongLibraryItem>, onOpen: (SongLibraryItem) -> Unit) {
    if (history.isEmpty()) return
    ProductCard {
        SectionTitle(stringResource(R.string.studio_recent))
        history.forEach { song -> PieceLink(song) { onOpen(song) } }
    }
}

@Composable
private fun PieceLink(item: SongLibraryItem, onClick: () -> Unit) {
    ArrowPreference(title = item.displayName, summary = stringResource(R.string.studio_file_meta,
        item.sourceType.name, item.eventCount, songDuration(item.durationMs)), onClick = onClick,
        insideMargin = PaddingValues(vertical = Spacing.item))
}
