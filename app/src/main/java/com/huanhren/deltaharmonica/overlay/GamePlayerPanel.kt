package com.huanhren.deltaharmonica.overlay

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.widget.ImageView
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.data.SongLibraryItem
import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.SyncStatus
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.playback.PlaybackProgress
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import com.huanhren.deltaharmonica.ui.AppViewModel
import com.huanhren.deltaharmonica.ui.PlaybackRange
import com.huanhren.deltaharmonica.ui.currentPlaybackControls
import com.huanhren.deltaharmonica.ui.playbackControls
import com.huanhren.deltaharmonica.ui.sessionFraction
import com.huanhren.deltaharmonica.ui.sessionStatus
import com.huanhren.deltaharmonica.ui.songDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The service owns the window and input lifecycle; this panel projects the shared playback state. */
internal class GamePlayerPanel(
    private var style: OverlayStyle,
    private val model: AppViewModel,
    private val layout: () -> InstrumentLayout?,
    private val onResize: () -> Unit,
    private val drag: (View, (() -> Unit)?) -> Unit,
    private val onClose: () -> Unit,
    private val onArm: () -> Unit,
    private val scope: CoroutineScope,
    private val musicPrefs: MusicCenterPreferences,
    private val onKeyboard: (Boolean) -> Unit,
    private var displayHeight: Int,
    private val renderBlocked: () -> Boolean = { false },
) {
    private enum class Page { BUBBLE, PLAYER, MUSIC, SYNC, CONFIRM }

    private val context get() = style.context
    val view = FrameLayout(context)
    private var page = Page.BUBBLE
    val minimized get() = page == Page.BUBBLE
    val collapsedWidth get() = maxOf(style.dp(68), style.touchSize)
    val collapsedHeight get() = style.touchSize
    val preferredWidth get() = if (page == Page.MUSIC) maxOf(style.dp(405), style.touchSize * 4 + style.dp(40))
        else maxOf(style.dp(328), style.touchSize * 5 + style.dp(40))
    val sizingMode get() = page.ordinal
    val screenMargin get() = style.dp(8)

    private val workspace get() = model.midiWorkspace
    private var startAfterSync = false
    private var selectedRegion: Region? = null
    private var selectedSemitone: Boolean? = null
    private var calibrated = false
    private var localError: String? = null
    private var status: TextView? = null
    private var progressBar: ProgressBar? = null
    private var note: TextView? = null
    private var start: View? = null
    private var stop: View? = null
    private var lastBusy = false
    private var ownedConfirmation = false
    private var preflightJob: Job? = null
    private var musicCenter: GameMusicCenter? = null
    private var volumeValue: TextView? = null
    private var bubbleButton: View? = null
    private var bubbleDot: View? = null
    private var pendingRender = false

    init { render() }

    fun flushPendingRender() {
        if (pendingRender) render()
    }

    fun updateStyle(next: OverlayStyle) {
        style = next
        render()
    }

    fun markBounds(height: Int) {
        if (height == displayHeight) {
            onResize()
            return
        }
        displayHeight = height
        render()
    }

    fun refresh() {
        if (minimized || page == Page.MUSIC) return
        if (page == Page.CONFIRM && PlaybackPreparation.progress.value.phase != PlaybackStatus.CONFIRMING) {
            ownedConfirmation = false
            page = Page.PLAYER
        }
        render()
    }

    fun dispose() {
        preflightJob?.cancel()
        musicCenter?.dispose()
        musicCenter = null
        cancelOwnedConfirmation()
    }

    private fun cancelOwnedConfirmation() {
        if (ownedConfirmation) model.cancelPreflight()
        ownedConfirmation = false
    }

    fun applyProgress(progress: PlaybackProgress) {
        val controls = playbackControls(progress, PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null)
        val busy = !controls.canStart
        val refreshControls = lastBusy != busy
        lastBusy = busy
        if (refreshControls && !minimized && page != Page.MUSIC && !ownedConfirmation) {
            render()
            return
        }
        val phaseLabel = label(sessionStatus(progress))
        bubbleDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when (progress.phase) {
                PlaybackStatus.PLAYING -> style.accent
                PlaybackStatus.PAUSING, PlaybackStatus.PAUSED, PlaybackStatus.WAITING_FOR_GAME -> 0xFFE8B16A.toInt()
                PlaybackStatus.FAILED -> 0xFFDF7676.toInt()
                else -> style.track
            })
        }
        bubbleButton?.let { bubble ->
            if (Build.VERSION.SDK_INT >= 30) bubble.stateDescription = phaseLabel
            else bubble.contentDescription = "${label(R.string.overlay_expand)} · $phaseLabel"
        }
        status?.text = when (progress.phase) {
            PlaybackStatus.READY -> label(R.string.overlay_ready)
            PlaybackStatus.CHECKING -> label(R.string.overlay_checking)
            PlaybackStatus.CONFIRMING -> label(R.string.overlay_confirm)
            PlaybackStatus.WAITING_FOR_GAME -> label(R.string.overlay_wait_game)
            PlaybackStatus.PLAYING -> label(R.string.overlay_playing)
            PlaybackStatus.PAUSING -> label(R.string.session_pausing)
            PlaybackStatus.PAUSED -> label(R.string.session_paused)
            PlaybackStatus.STOPPING -> label(R.string.overlay_stopping)
            PlaybackStatus.COMPLETED -> phaseLabel
            PlaybackStatus.FAILED -> label(R.string.overlay_failure_detail, progress.state)
        }
        note?.text = progress.current?.let {
            val key = if (it.startsWith("NOTE_")) it.removePrefix("NOTE_").toIntOrNull() else null
            val name = key?.let { number -> label(R.string.overlay_key, number) } ?: it
            label(R.string.overlay_progress_note, name, progress.completed, progress.total)
        } ?: label(R.string.overlay_progress_count, progress.completed, progress.total)
        progressBar?.progress = (sessionFraction(progress) * 1000).toInt()
        start?.isEnabled = allowed() && workspace.report != null && layout() != null
        stop?.isEnabled = controls.canStop
    }

    private fun label(@StringRes id: Int, vararg args: Any) =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    private fun allowed() = currentPlaybackControls().canStart &&
        !model.imports.state.value.active && !model.libraryBusy.value

    private fun open(next: Page) {
        if (page == Page.CONFIRM && next != Page.CONFIRM) cancelOwnedConfirmation()
        if (page == Page.MUSIC && next != Page.MUSIC) musicCenter?.hide()
        if (next == Page.BUBBLE) preflightJob?.cancel()
        val changeSize = minimized != (next == Page.BUBBLE)
        page = next
        localError = null
        render()
        if (changeSize && style.motion) view.getChildAt(0)?.apply {
            alpha = .55f
            animate().alpha(1f).setDuration(180L).start()
        }
    }

    private fun render() {
        if (renderBlocked()) {
            pendingRender = true
            return
        }
        pendingRender = false
        status = null
        progressBar = null
        note = null
        start = null
        stop = null
        volumeValue = null
        bubbleButton = null
        bubbleDot = null
        view.removeAllViews()
        if (minimized) {
            view.background = null
            val bubble = FrameLayout(context).apply {
                background = style.panelBackground()
                contentDescription = label(R.string.overlay_expand)
                isFocusable = true
                setOnClickListener { open(Page.PLAYER) }
            }
            val mark = ImageView(context).apply {
                setImageResource(R.drawable.ic_launcher)
                background = GradientDrawable().apply { cornerRadius = style.dp(7).toFloat() }
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            bubble.addView(mark, FrameLayout.LayoutParams(style.dp(27), style.dp(27), Gravity.CENTER))
            bubbleDot = View(context).also {
                bubble.addView(it, FrameLayout.LayoutParams(style.dp(7), style.dp(7), Gravity.END or Gravity.TOP).apply {
                    marginEnd = style.dp(8)
                    topMargin = style.dp(8)
                })
            }
            bubbleButton = bubble
            view.addView(bubble, FrameLayout.LayoutParams(-1, -1))
            drag(bubble) { open(Page.PLAYER) }
            applyProgress(PlaybackPreparation.progress.value)
            onResize()
            return
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(10))
        }
        view.background = style.panelBackground()
        addHeader(card)
        if (page == Page.MUSIC) {
            val center = music()
            val contentHeight = minOf(style.dp(292),
                (displayHeight - style.touchSize - screenMargin * 2 - card.paddingTop - card.paddingBottom).coerceAtLeast(1))
            (center.view.parent as? LinearLayout)?.removeView(center.view)
            card.addView(center.view, LinearLayout.LayoutParams(-1, contentHeight))
            center.show(duringPlayback = false)
            view.addView(card, FrameLayout.LayoutParams(-1, -2))
            onResize()
            return
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, style.dp(6), 0, 0)
        }
        when (page) {
            Page.SYNC -> addSync(body)
            Page.CONFIRM -> addConfirmation(body)
            else -> addPlayer(body)
        }
        val maxHeight = (displayHeight - screenMargin * 2 - card.paddingTop - card.paddingBottom - style.touchSize)
            .coerceAtLeast(0)
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight
                    else minOf(maxHeight, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(body, LinearLayout.LayoutParams(-1, -2))
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        view.addView(card, FrameLayout.LayoutParams(-1, -2))
        lastBusy = !currentPlaybackControls().canStart
        applyProgress(PlaybackPreparation.progress.value)
        onResize()
    }

    private fun addHeader(card: LinearLayout) {
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = style.text(when (page) {
            Page.MUSIC -> label(R.string.game_music_center)
            Page.SYNC -> label(R.string.overlay_sync)
            Page.CONFIRM -> label(R.string.overlay_confirm)
            else -> workspace.name.ifBlank { label(R.string.overlay_none) }
        }, 16f, strong = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(style.dp(3), 0, style.dp(8), 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(heading, LinearLayout.LayoutParams(0, style.touchSize, 1f))
        drag(heading, null)
        header.addView(style.button(if (page == Page.MUSIC) "‹" else "−", capsule = true, size = 20f) {
            open(if (page == Page.MUSIC) Page.PLAYER else Page.BUBBLE)
        }.apply {
            contentDescription = label(if (page == Page.MUSIC) R.string.action_back else R.string.overlay_minimize)
        }, LinearLayout.LayoutParams(style.touchSize, style.touchSize))
        header.addView(style.button("×", capsule = true, size = 20f) {
            dispose()
            onClose()
        }.apply {
            contentDescription = label(R.string.overlay_close)
        }, LinearLayout.LayoutParams(style.touchSize, style.touchSize).apply { marginStart = style.dp(4) })
        card.addView(header)
    }

    private fun addPlayer(body: LinearLayout) {
        addRow(body) {
            PlaybackRange.entries.forEach { value ->
                val name = when (value) {
                    PlaybackRange.FIRST_8 -> R.string.overlay_8_notes
                    PlaybackRange.FIRST_16 -> R.string.overlay_16_notes
                    PlaybackRange.ALL -> R.string.overlay_full
                }
                control(label(name), workspace.range == value, enabled = allowed()) {
                    model.changePlan { range = value }
                    render()
                }
            }
            listOf(1.0, 2.0).forEach { value ->
                control(label(R.string.overlay_speed, value.toInt()), workspace.speed == value, enabled = allowed()) {
                    model.changePlan { speed = value }
                    render()
                }
            }
        }
        val stateLine = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(style.dp(3), style.dp(8), style.dp(3), style.dp(4))
        }
        note = style.text("", 14f, strong = true).also {
            stateLine.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(8) })
        }
        status = style.text("", 12f).apply {
            gravity = Gravity.END
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }.also { stateLine.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        body.addView(stateLine)
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(style.accent)
            progressBackgroundTintList = ColorStateList.valueOf(style.track)
            contentDescription = label(R.string.overlay_progress)
        }.also { body.addView(it, LinearLayout.LayoutParams(-1, style.dp(8))) }
        localError?.let { body.addView(style.text(label(R.string.overlay_failure_detail, it))) }
        if (layout() == null) body.addView(style.text(label(R.string.overlay_needs_layout)))
        addRow(body) {
            control(label(R.string.game_music_center), enabled = allowed(), weight = 1.4f) { open(Page.MUSIC) }
            control(label(R.string.overlay_sync_action), enabled = allowed()) { openSync(start = false) }
            start = control(label(R.string.overlay_start), primary = allowed(),
                enabled = allowed() && workspace.report != null && layout() != null,
            ) { beginFromLoadedTrack() }
            val canStop = currentPlaybackControls().canStop
            stop = control(label(R.string.overlay_stop), primary = canStop, stopAction = true, enabled = canStop) {
                PlaybackPreparation.stopPlayback()
            }
        }
        addRow(body) {
            control("‹", enabled = allowed() && adjacentSong(-1) != null) {
                adjacentSong(-1)?.let { selectTrack(it) }
            }.contentDescription = label(R.string.game_music_previous)
            control("›", enabled = allowed() && adjacentSong(1) != null) {
                adjacentSong(1)?.let { selectTrack(it) }
            }.contentDescription = label(R.string.game_music_next)
            control("−", action = { changeVolume(-1) }).contentDescription = label(R.string.game_music_volume_down)
            control("+", action = { changeVolume(1) }).contentDescription = label(R.string.game_music_volume_up)
        }
        volumeValue = style.text(volumeLabel()).also { body.addView(it) }
    }

    private fun openSync(start: Boolean) {
        val runtime = PlaybackPreparation.runtimeState.value
        selectedRegion = runtime.pitchRegion
        selectedSemitone = runtime.semitoneEnabled
        startAfterSync = start
        open(Page.SYNC)
    }

    fun music(): GameMusicCenter = musicCenter ?: GameMusicCenter(style, model, scope, musicPrefs, layout, onKeyboard) {
        open(Page.PLAYER)
        beginFromLoadedTrack()
    }.also { musicCenter = it }

    fun prepareForPlayback() {
        releaseMusicKeyboard()
        if (page == Page.MUSIC) open(Page.PLAYER)
    }

    fun releaseMusicKeyboard() {
        musicCenter?.releaseKeyboard()
    }

    fun adjacentSong(step: Int): String? {
        val ids = model.songs.items.value.filterNot { it.id in model.songs.problems.value }.map(SongLibraryItem::id)
        return adjacentTrack(ids, workspace.songId, step)
    }

    fun selectTrack(id: String, play: Boolean = false) {
        if (!allowed()) return
        model.activateSong(id) { error ->
            if (!view.isAttachedToWindow) return@activateSong
            if (error == null) {
                open(Page.PLAYER)
                if (play) beginFromLoadedTrack()
            } else {
                localError = error
                render()
            }
        }
    }

    private fun beginFromLoadedTrack() {
        val unmapped = workspace.report?.compatibility?.unmappedCount ?: 0
        if (unmapped > 0) {
            localError = label(R.string.game_music_unmapped, unmapped)
            render()
            return
        }
        if (PlaybackPreparation.runtimeState.value.syncStatus == SyncStatus.USER_CONFIRMED) preflight()
        else openSync(start = true)
    }

    private fun volumeManager() = context.getSystemService(AudioManager::class.java)

    fun volumeLabel(): String {
        val audio = volumeManager()
        val percent = if (audio == null) 0 else runCatching {
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 /
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        }.getOrDefault(0)
        return label(R.string.game_music_volume_percent, percent)
    }

    fun changeVolume(step: Int) {
        val direction = if (step > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        runCatching { volumeManager()?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        volumeValue?.text = volumeLabel()
    }

    private fun addSync(body: LinearLayout) {
        body.addView(style.text(label(R.string.overlay_sync_hint)))
        addRow(body) {
            Region.entries.forEach { value ->
                val name = when (value) {
                    Region.LOW -> R.string.overlay_low
                    Region.NATURAL -> R.string.overlay_natural
                    Region.HIGH -> R.string.overlay_high
                }
                control(label(name), selectedRegion == value) {
                    selectedRegion = value
                    render()
                }
            }
        }
        addRow(body) {
            control(label(R.string.overlay_semitone_off), selectedSemitone == false) { selectedSemitone = false; render() }
            control(label(R.string.overlay_semitone_on), selectedSemitone == true) { selectedSemitone = true; render() }
        }
        addRow(body) {
            control(label(R.string.action_back)) { open(Page.PLAYER) }
            control(label(R.string.action_confirm), primary = true, enabled = allowed() && selectedRegion != null && selectedSemitone != null) {
                if (!allowed()) return@control
                PlaybackPreparation.runtimeState.value = PlaybackPreparation.runtimeState.value
                    .confirmRegion(requireNotNull(selectedRegion)).confirmSemitone(requireNotNull(selectedSemitone))
                open(Page.PLAYER)
                if (startAfterSync) preflight()
            }
        }
    }

    private fun preflight() {
        if (preflightJob?.isActive == true) return
        preflightJob = scope.launch {
            if (model.startPreflight(layout(), Settings.canDrawOverlays(context))) {
                calibrated = PlaybackPreparation.controlsConfirmed
                ownedConfirmation = true
                open(Page.CONFIRM)
            }
        }
    }

    private fun addConfirmation(body: LinearLayout) {
        val plan = workspace.dryRun ?: return
        body.addView(style.text(context.resources.getQuantityString(
            R.plurals.overlay_estimate, plan.events.size, plan.events.size, songDuration(plan.estimatedTotalMs),
        )))
        plan.warnings.forEach { body.addView(style.text(it)) }
        addRow(body) {
            control(label(if (calibrated) R.string.overlay_calibrated else R.string.overlay_confirm_calibration), calibrated) {
                calibrated = !calibrated
                render()
            }
        }
        addRow(body) {
            control(label(R.string.action_cancel)) { open(Page.PLAYER) }
            control(label(R.string.overlay_confirm_start), primary = true, enabled = calibrated) {
                ownedConfirmation = false
                page = Page.PLAYER
                model.confirmPreflight(onArm)
                render()
            }
        }
    }

    private fun addRow(body: LinearLayout, content: ControlRow.() -> Unit) {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, style.dp(4), 0, style.dp(2))
        }
        ControlRow(row).content()
        body.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private inner class ControlRow(private val row: LinearLayout) {
        fun control(
            label: String,
            selected: Boolean = false,
            primary: Boolean = false,
            stopAction: Boolean = false,
            enabled: Boolean = true,
            weight: Float = 1f,
            action: () -> Unit,
        ): View {
            val button = style.button(label, selected, primary, stop = stopAction, action = action).apply {
                isEnabled = enabled
            }
            row.addView(button, LinearLayout.LayoutParams(0, -2, weight).apply {
                if (row.childCount > 0) marginStart = style.dp(4)
            })
            return button
        }
    }
}
