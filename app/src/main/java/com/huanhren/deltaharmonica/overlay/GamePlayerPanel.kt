package com.huanhren.deltaharmonica.overlay

import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
import com.huanhren.deltaharmonica.ui.label
import com.huanhren.deltaharmonica.ui.playbackControls
import com.huanhren.deltaharmonica.ui.playbackFraction
import com.huanhren.deltaharmonica.ui.songDuration

/** The service owns the window. This panel only renders state and emits the same commands as the app player. */
internal class GamePlayerPanel(
    private val context: Context,
    private val style: OverlayStyle,
    private val model: AppViewModel,
    private val layout: () -> InstrumentLayout?,
    private val onResize: () -> Unit,
    private val drag: (View, (() -> Unit)?) -> Unit,
    private val onClose: () -> Unit,
    private val onArm: () -> Unit,
) {
    private enum class Page { BUBBLE, PLAYER, SONGS, SYNC, CONFIRM }

    val view = FrameLayout(context)
    private var page = Page.BUBBLE
    val minimized get() = page == Page.BUBBLE
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
    private var displayHeightDp = 600
    private var ownedConfirmation = false
    private var songList: ListView? = null

    init { render() }

    fun markBounds(heightDp: Int) {
        if (heightDp == displayHeightDp) return
        displayHeightDp = heightDp
        render()
    }

    fun refresh() {
        if (page == Page.BUBBLE) return
        if (page == Page.CONFIRM && PlaybackPreparation.progress.value.phase != PlaybackStatus.CONFIRMING) {
            ownedConfirmation = false
            page = Page.PLAYER
        }
        render()
    }

    fun dispose() {
        if (ownedConfirmation) model.cancelPreflight()
        ownedConfirmation = false
    }

    fun applyProgress(progress: PlaybackProgress) {
        val controls = playbackControls(progress, PlaybackPreparation.activeRunner != null, PlaybackPreparation.cancelPendingStart != null)
        val busy = !controls.canStart
        val refreshControls = lastBusy != busy
        lastBusy = busy
        if (refreshControls && page != Page.BUBBLE && !ownedConfirmation) {
            render()
            return
        }
        status?.text = localError ?: progress.state
        note?.text = "${progress.current ?: "—"}   ${progress.completed}/${progress.total}"
        progressBar?.progress = (playbackFraction(progress) * 1000).toInt()
        start?.isEnabled = allowed() && workspace.report != null && layout() != null
        stop?.isEnabled = controls.canStop
    }

    private fun allowed() = currentPlaybackControls().canStart &&
        !model.imports.state.value.active && !model.libraryBusy.value

    private fun open(next: Page) {
        if (page == Page.CONFIRM && next != Page.CONFIRM) dispose()
        page = next
        localError = null
        render()
    }

    private fun render() {
        val index = songList?.firstVisiblePosition ?: 0
        val offset = songList?.getChildAt(0)?.top ?: 0
        status = null; progressBar = null; note = null; start = null; stop = null; songList = null
        view.removeAllViews()
        view.alpha = if (minimized) .88f else 1f
        if (minimized) {
            view.background = null
            val bubble = style.button("♫", primary = true) { open(Page.PLAYER) }.apply {
                contentDescription = "展开游戏播放器"
                textSize = 23f
            }
            view.addView(bubble, FrameLayout.LayoutParams(-1, -1))
            drag(bubble) { open(Page.PLAYER) }
            onResize()
            return
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(style.dp(10), style.dp(8), style.dp(10), style.dp(8))
        }
        view.background = style.rounded(style.panel, 22)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = style.text(when (page) {
            Page.SONGS -> "选择曲目"
            Page.SYNC -> "同步游戏状态"
            Page.CONFIRM -> "确认演奏"
            else -> "DeltaHarmonica"
        }, 15f, strong = true).apply {
            setPadding(style.dp(4), style.dp(8), style.dp(4), style.dp(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(heading, LinearLayout.LayoutParams(0, style.dp(42), 1f))
        drag(heading, null)
        header.addView(style.button("−") { open(Page.BUBBLE) }.apply { contentDescription = "最小化播放器" },
            LinearLayout.LayoutParams(style.dp(42), style.dp(42)))
        header.addView(style.button("×") { dispose(); onClose() }.apply { contentDescription = "关闭悬浮窗" },
            LinearLayout.LayoutParams(style.dp(42), style.dp(42)).apply { marginStart = style.dp(4) })
        body.addView(header)
        when (page) {
            Page.SONGS -> {
                addSongs(body)
                songList?.setSelectionFromTop(index, offset)
            }
            Page.SYNC -> addSync(body)
            Page.CONFIRM -> addConfirmation(body)
            else -> addPlayer(body)
        }
        val maxHeight = style.dp((displayHeightDp - 16).coerceAtLeast(120))
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; isVerticalScrollBarEnabled = true }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        view.addView(scroll, FrameLayout.LayoutParams(-1, -2))
        if (Build.VERSION.SDK_INT >= 28 && view.isAttachedToWindow) {
            body.alpha = .9f
            body.animate().alpha(1f).setDuration(125L).start()
        }
        val progress = PlaybackPreparation.progress.value
        lastBusy = !currentPlaybackControls().canStart
        applyProgress(progress)
        onResize()
    }

    private fun addPlayer(body: LinearLayout) {
        addRow(body) {
            title(workspace.name.ifBlank { "尚未选择曲目" })
            control("选择曲目", enabled = allowed(), weight = 1f) { open(Page.SONGS) }
        }
        addRow(body) {
            PlaybackRange.entries.forEach { value ->
                control(value.label, workspace.range == value, enabled = allowed()) {
                    model.changePlan { range = value }; render()
                }
            }
            listOf(1.0, 2.0).forEach { value ->
                control("${value.toInt()}×", workspace.speed == value, enabled = allowed()) {
                    model.changePlan { speed = value }; render()
                }
            }
        }
        val stateLine = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        note = style.text("", 15f, strong = true).also {
            stateLine.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(8) })
        }
        status = style.text("").apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }.also {
            stateLine.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(stateLine)
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(style.accent)
        }.also { body.addView(it, LinearLayout.LayoutParams(-1, style.dp(8))) }
        if (layout() == null) body.addView(style.text("请先在应用中保存校准布局。"))
        addRow(body) {
            control("同步状态", enabled = allowed()) { openSync(start = false) }
            start = control("开始", primary = true, enabled = allowed() && workspace.report != null && layout() != null) {
                if (PlaybackPreparation.runtimeState.value.syncStatus == SyncStatus.USER_CONFIRMED) preflight()
                else openSync(start = true)
            }
            stop = control("停止", enabled = currentPlaybackControls().canStop) { PlaybackPreparation.stopPlayback() }
        }
    }

    private fun openSync(start: Boolean) {
        val runtime = PlaybackPreparation.runtimeState.value
        selectedRegion = runtime.pitchRegion
        selectedSemitone = runtime.semitoneEnabled
        startAfterSync = start
        open(Page.SYNC)
    }
    private fun addSongs(body: LinearLayout) {
        val items = model.songs.items.value
        val problems = model.songs.problems.value
        val recent = model.preferences?.recent?.value.orEmpty()
        val songs = items.sortedWith(compareByDescending<SongLibraryItem> { recent[it.id] ?: Long.MIN_VALUE }
            .thenByDescending { it.createdAt })
        val message = localError ?: model.libraryError.value ?: when {
            model.libraryBusy.value -> "正在加载曲目…"
            model.libraryLoading.value -> "正在读取曲库…"
            songs.isEmpty() -> "曲库为空，请在应用中导入 MIDI 或 JSON。"
            else -> "${songs.size} 首曲目"
        }
        body.addView(style.text(message))
        if (songs.isNotEmpty()) {
            val list = ListView(context).apply {
                dividerHeight = style.dp(2)
                isNestedScrollingEnabled = true
                adapter = object : BaseAdapter() {
                    override fun getCount() = songs.size
                    override fun getItem(position: Int) = songs[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun areAllItemsEnabled() = false
                    override fun isEnabled(position: Int) = allowed() && getItem(position).id !in problems
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val song = getItem(position)
                        return ((convertView as? TextView) ?: style.text("", 14f, strong = true).apply {
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER_VERTICAL
                            minHeight = style.dp(46)
                            setPadding(style.dp(6), style.dp(3), style.dp(6), style.dp(3))
                        }).apply {
                            val tag = if (workspace.songId == song.id) "✓ " else ""
                            text = "$tag${song.displayName}\n${problems[song.id] ?: "${song.eventCount} 音 · ${songDuration(song.durationMs)}"}"
                        }
                    }
                }
                setOnItemClickListener { _, _, position, _ ->
                    localError = null
                    model.activateSong(songs[position].id) { error ->
                        if (!view.isAttachedToWindow || page != Page.SONGS) return@activateSong
                        if (error == null) open(Page.PLAYER)
                        else { localError = error; render() }
                    }
                }
            }
            songList = list
            body.addView(list, LinearLayout.LayoutParams(-1, style.dp(minOf(170, (displayHeightDp - 150).coerceAtLeast(50)))))
        }
        addRow(body) {
            control("返回") { open(Page.PLAYER) }
            if (model.libraryError.value != null) control("重试") { model.refreshLibrary() }
        }
    }

    private fun addSync(body: LinearLayout) {
        body.addView(style.text("根据游戏画面确认当前音区与半音。"))
        addRow(body) {
            Region.entries.forEach { value -> control(value.label, selectedRegion == value) { selectedRegion = value; render() } }
        }
        addRow(body) {
            control("半音关", selectedSemitone == false) { selectedSemitone = false; render() }
            control("半音开", selectedSemitone == true) { selectedSemitone = true; render() }
        }
        addRow(body) {
            control("返回") { open(Page.PLAYER) }
            control("确认", primary = true, enabled = allowed() && selectedRegion != null && selectedSemitone != null) {
                if (!allowed()) return@control
                PlaybackPreparation.runtimeState.value = PlaybackPreparation.runtimeState.value
                    .confirmRegion(requireNotNull(selectedRegion)).confirmSemitone(requireNotNull(selectedSemitone))
                open(Page.PLAYER)
                if (startAfterSync) preflight()
            }
        }
    }

    private fun preflight() {
        if (model.startPreflight(layout(), android.provider.Settings.canDrawOverlays(context))) {
            calibrated = PlaybackPreparation.controlsConfirmed
            ownedConfirmation = true
            open(Page.CONFIRM)
        }
    }

    private fun addConfirmation(body: LinearLayout) {
        val plan = workspace.dryRun ?: return
        body.addView(style.text("${plan.events.size} 个事件 · 预计 ${songDuration(plan.estimatedTotalMs)}"))
        plan.warnings.forEach { body.addView(style.text(it)) }
        addRow(body) {
            control(if (calibrated) "✓ 目标已校准" else "确认目标已校准", calibrated) {
                calibrated = !calibrated
                render()
            }
        }
        addRow(body) {
            control("取消") { open(Page.PLAYER) }
            control("确认并开始", primary = true, enabled = calibrated) {
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
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, style.dp(4), 0, style.dp(2))
        }
        ControlRow(row).content()
        body.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private inner class ControlRow(private val row: LinearLayout) {
        fun title(value: String) {
            val text = style.text(value, 16f, strong = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1.7f))
        }

        fun control(label: String, selected: Boolean = false, primary: Boolean = false, enabled: Boolean = true,
                    weight: Float = 1f,
                    action: () -> Unit): View {
            val button = style.button(label, selected, primary, action).apply { isEnabled = enabled; alpha = if (enabled) 1f else .65f }
            row.addView(button, LinearLayout.LayoutParams(0, -2, weight).apply {
                if (row.childCount > 0) marginStart = style.dp(4)
            })
            return button
        }
    }
}
