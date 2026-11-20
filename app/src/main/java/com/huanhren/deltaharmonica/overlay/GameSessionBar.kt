package com.huanhren.deltaharmonica.overlay

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.annotation.StringRes
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.playback.PlaybackProgress
import com.huanhren.deltaharmonica.playback.PlaybackStatus
import com.huanhren.deltaharmonica.ui.sessionFraction
import com.huanhren.deltaharmonica.ui.sessionStatus
import com.huanhren.deltaharmonica.ui.songDuration

/** Compact controls remain visible while the regular player window is detached for input dispatch. */
internal class GameSessionBar(
    private val style: OverlayStyle,
    title: String,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onToggleDetails: () -> Unit,
    onToggleMusic: (() -> Unit)? = null,
) {
    val compactWidth = (if (onToggleMusic != null) 4 else 3) * style.touchSize
    val compactHeight = style.touchSize
    val expandedWidth = maxOf(compactWidth, style.dp(264))
    val expandedHeight = style.touchSize + style.dp((76 * style.context.resources.configuration.fontScale.coerceAtLeast(1f)).toInt())

    val view = LinearLayout(style.context).apply {
        orientation = LinearLayout.VERTICAL
        background = style.panelBackground()
    }
    private val detailsToggle = style.button("⌄", capsule = true, size = 18f, action = onToggleDetails)
    private val music = onToggleMusic?.let { action ->
        style.button("♫", capsule = true, size = 18f, action = action).apply {
            contentDescription = label(R.string.game_music_center)
        }
    }
    private val pause = style.button("Ⅱ", capsule = true, size = 18f, action = onTogglePause)
    private val stop = style.button("■", capsule = true, size = 14f, action = onStop)
    private val info = LinearLayout(style.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(style.dp(12), 0, style.dp(12), style.dp(6))
        visibility = View.GONE
    }
    private val status = style.text("", 12f).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val timeline = ProgressBar(style.context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(style.accent)
        progressBackgroundTintList = ColorStateList.valueOf(style.track)
        contentDescription = label(R.string.overlay_progress)
    }
    var expanded = false
        private set

    init {
        val controls = LinearLayout(style.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            listOfNotNull(detailsToggle, music, pause, stop).forEach { addView(it, LinearLayout.LayoutParams(0, style.touchSize, 1f)) }
        }
        stop.contentDescription = label(R.string.overlay_stop)
        view.addView(controls, LinearLayout.LayoutParams(-1, compactHeight))
        val heading = style.text(title, 15f, strong = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(heading)
        info.addView(status)
        info.addView(timeline, LinearLayout.LayoutParams(-1, style.dp(8)))
        view.addView(info, LinearLayout.LayoutParams(-1, expandedHeight - compactHeight))
        setExpanded(false)
    }

    fun setExpanded(value: Boolean) {
        expanded = value
        info.visibility = if (value) View.VISIBLE else View.GONE
        detailsToggle.text = if (value) "⌃" else "⌄"
        detailsToggle.contentDescription = label(if (value) R.string.overlay_minimize else R.string.overlay_expand)
    }

    fun update(progress: PlaybackProgress, canExpand: Boolean, canPause: Boolean, canUseMusic: Boolean = false) {
        val paused = progress.phase == PlaybackStatus.PAUSED
        pause.text = if (paused) "▶" else "Ⅱ"
        pause.contentDescription = label(if (paused) R.string.session_resume else R.string.session_pause)
        pause.isEnabled = canPause && (paused || progress.phase == PlaybackStatus.PLAYING)
        detailsToggle.isEnabled = paused && canExpand
        music?.isEnabled = canUseMusic
        stop.isEnabled = progress.phase != PlaybackStatus.STOPPING
        if (expanded) {
            val phase = label(sessionStatus(progress))
            val time = "${songDuration(progress.elapsedMs.coerceAtMost(progress.durationMs))} / ${songDuration(progress.durationMs)}"
            status.text = style.context.getString(R.string.session_overlay_timing, phase, time)
            timeline.progress = (sessionFraction(progress) * 1000).toInt()
        }
    }

    private fun label(@StringRes id: Int) = style.context.getString(id)
}
