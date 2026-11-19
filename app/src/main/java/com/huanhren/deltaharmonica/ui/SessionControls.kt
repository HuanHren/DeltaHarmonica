package com.huanhren.deltaharmonica.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.playback.PlaybackProgress
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun rememberControlsProgress(): State<PlaybackProgress> {
    val controlProgress = remember {
        PlaybackPreparation.progress.map { it.copy(elapsedMs = 0L) }.distinctUntilChanged()
    }
    return controlProgress.collectAsStateWithLifecycle(initialValue = PlaybackProgress())
}

@StringRes
internal fun sessionStatus(progress: PlaybackProgress): Int = when (progress.phase) {
    PlaybackStatus.READY -> R.string.player_phase_ready
    PlaybackStatus.CHECKING -> R.string.player_phase_checking
    PlaybackStatus.CONFIRMING -> R.string.player_phase_confirming
    PlaybackStatus.WAITING_FOR_GAME -> R.string.player_phase_waiting
    PlaybackStatus.PLAYING -> R.string.player_phase_playing
    PlaybackStatus.PAUSING -> R.string.session_pausing
    PlaybackStatus.PAUSED -> R.string.session_paused
    PlaybackStatus.STOPPING -> R.string.player_phase_stopping
    PlaybackStatus.COMPLETED -> if (progress.stopped) R.string.player_phase_stopped else R.string.player_phase_completed
    PlaybackStatus.FAILED -> R.string.player_phase_failed
}

internal fun sessionFraction(progress: PlaybackProgress): Float = when {
    progress.phase == PlaybackStatus.COMPLETED && !progress.stopped && progress.total > 0 -> 1f
    progress.durationMs > 0 -> (progress.elapsedMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    else -> playbackFraction(progress)
}

@Composable
internal fun MusicIconButton(
    icon: ProductIcon,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color = Color.Transparent,
    foreground: Color = StudioTheme.colorScheme.onSurface,
    extent: Dp = Spacing.touchTarget,
    glyphSize: Dp = 22.dp,
    animateGlyph: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalAppearance.current.reducedEffects
    val scale by animateFloatAsState(if (pressed && !reduced) .91f else 1f, tween(MusicMotion.feedback), label = "control press")
    val shape = RoundedCornerShape(if (extent > 56.dp) 24.dp else 14.dp)
    val ink = if (enabled) foreground else foreground.copy(alpha = .4f)
    Box(modifier.size(extent).semantics { contentDescription = description }
        .clip(shape).clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }.background(background, shape),
            contentAlignment = Alignment.Center) {
            if (animateGlyph && !reduced) {
                AnimatedContent(icon, transitionSpec = {
                    (fadeIn(tween(MusicMotion.feedback)) + scaleIn(tween(MusicMotion.feedback), initialScale = .72f))
                        .togetherWith(fadeOut(tween(95)) + scaleOut(tween(95), targetScale = .72f))
                }, label = "playback control") { glyph ->
                    ProductGlyph(glyph, ink, Modifier.size(glyphSize))
                }
            } else ProductGlyph(icon, ink, Modifier.size(glyphSize))
        }
    }
}

@Composable
internal fun SessionProgressLine(fraction: Float, modifier: Modifier = Modifier, thickness: Dp = 4.dp) {
    val colors = StudioTheme.colorScheme
    val reduced = LocalAppearance.current.reducedEffects
    val animated = animateFloatAsState(fraction, tween(if (reduced) 0 else MusicMotion.feedback), label = "session progress")
    Canvas(modifier.fillMaxWidth().height(thickness).progressSemantics(fraction)) {
        val radius = size.height / 2f
        val end = (size.width - radius).coerceAtLeast(radius)
        val start = Offset(radius, radius)
        drawLine(colors.onSurface.copy(alpha = .12f), start, Offset(end, radius), size.height, StrokeCap.Round)
        val progress = animated.value
        if (progress > 0f) drawLine(colors.primary, start, Offset(radius + (end - radius) * progress, radius), size.height, StrokeCap.Round)
    }
}

@Composable
internal fun MiniPlayer(workspace: MidiWorkspace, onOpen: (start: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val id = workspace.songId ?: return
    if (workspace.report == null) return
    val colors = StudioTheme.colorScheme
    val progress by rememberControlsProgress()
    val controls = currentPlaybackControls()
    val active = progress.phase == PlaybackStatus.PLAYING || progress.phase == PlaybackStatus.PAUSING
    val paused = progress.phase == PlaybackStatus.PAUSED
    val canCancel = progress.phase == PlaybackStatus.WAITING_FOR_GAME && controls.canStop
    val glyph = when {
        canCancel -> ProductIcon.Stop
        active -> ProductIcon.Pause
        else -> ProductIcon.Play
    }
    val actionLabel = when {
        canCancel -> R.string.overlay_stop
        paused -> R.string.session_resume
        active -> R.string.session_pause
        else -> R.string.studio_play
    }
    Column(modifier.testTag("mini/player").clip(ShapeTokens.card).background(colors.surface)) {
        Row(Modifier.fillMaxWidth().padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).testTag("mini/open")
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.session_expand)) { onOpen(false) }
                .padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MusicArtwork(id, Modifier.size(44.dp), origin = "mini")
                Column(Modifier.weight(1f)) {
                    Text(workspace.name, Modifier.coverTransition(id, "mini", "title"), style = StudioTypography.titleMedium,
                        overflow = TextOverflow.Ellipsis, maxLines = 1)
                    SupportingText(stringResource(sessionStatus(progress)), error = progress.phase == PlaybackStatus.FAILED, maxLines = 1)
                }
            }
            MusicIconButton(glyph, stringResource(actionLabel), {
                when {
                    canCancel -> PlaybackPreparation.stopPlayback()
                    active || paused -> PlaybackPreparation.togglePause()
                    else -> onOpen(true)
                }
            }, Modifier.testTag("mini/control"), enabled = paused || canCancel || progress.phase == PlaybackStatus.PLAYING || controls.canStart,
                foreground = colors.primary, animateGlyph = true)
        }
        MiniTimeline()
    }
}

@Composable
private fun MiniTimeline() {
    val progress by PlaybackPreparation.progress.collectAsStateWithLifecycle()
    SessionProgressLine(sessionFraction(progress), Modifier.padding(horizontal = 10.dp).padding(bottom = 5.dp), 2.dp)
}
