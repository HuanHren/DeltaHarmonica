package com.huanhren.deltaharmonica.overlay

import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.StringRes
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.model.Coordinates
import com.huanhren.deltaharmonica.model.InstrumentRuntimeState
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.ScreenSize
import com.huanhren.deltaharmonica.model.SyncStatus
import com.huanhren.deltaharmonica.model.TargetType
import com.huanhren.deltaharmonica.model.TouchTarget
import kotlin.math.roundToInt

internal class GameCalibrationPanel(
    private var style: OverlayStyle,
    private val onResize: () -> Unit,
    private val drag: (View, (() -> Unit)?) -> Unit,
    private val actions: Actions,
) {
    data class Actions(
        val select: (String) -> Unit,
        val nudge: (Int, Int) -> Unit,
        val save: () -> Unit,
        val recalibrate: () -> Unit,
        val toggleMarkers: () -> Unit,
        val confirmCoordinates: () -> Unit,
        val testTap: () -> Unit,
        val testNote: (Boolean) -> Unit,
        val adjustDuration: (Long) -> Unit,
        val sendRegion: (Region) -> Unit,
        val syncRegion: (Region) -> Unit,
        val sendSemitone: (Boolean) -> Unit,
        val syncSemitone: (Boolean) -> Unit,
        val resetState: () -> Unit,
        val close: () -> Unit,
    )

    data class State(
        val targets: List<TouchTarget>,
        val selected: String?,
        val display: ScreenSize?,
        val compatible: Boolean,
        val draft: Boolean,
        val hasReference: Boolean,
        val shown: Boolean,
        val coordinatesConfirmed: Boolean,
        val busy: Boolean,
        val duration: Long,
        val runtime: InstrumentRuntimeState,
        val root: Boolean,
        val backendAvailable: Boolean,
        val notice: String,
        val result: String,
    )

    private enum class Tab(@StringRes val title: Int) {
        POSITION(R.string.game_cal_position),
        TEST(R.string.game_cal_test_tab),
        REGISTER(R.string.game_cal_register_tab),
    }

    private val context get() = style.context
    val view = FrameLayout(context)
    var minimized = true
        private set
    private var tab = Tab.POSITION
    private var more = false
    private var state: State? = null
    private val bindings = mutableListOf<(State) -> Unit>()
    val collapsedWidth get() = style.dp(174)
    val collapsedHeight get() = style.touchSize
    val preferredWidth get() = maxOf(style.dp(372), style.touchSize * 4 + style.dp(42))
    val maxHeight get() = style.dp(490)
    val screenMargin get() = style.dp(8)

    init { render() }

    fun updateStyle(value: OverlayStyle) {
        style = value
        render()
    }

    fun update(value: State) {
        state = value
        bindings.forEach { it(value) }
    }

    private fun text(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)

    private fun render() {
        bindings.clear()
        view.removeAllViews()
        if (minimized) {
            val capsule = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = style.panelBackground(capsule = true)
                setPadding(style.dp(10), 0, style.dp(10), 0)
                isFocusable = true
                contentDescription = text(R.string.game_cal_expand)
            }
            capsule.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_launcher)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(style.dp(26), style.dp(26)).apply { marginEnd = style.dp(7) })
            val labels = column()
            labels.addView(style.text(text(R.string.game_cal_title), 13f, strong = true).oneLine())
            labels.addView(boundText(11f) { readiness(it) }.oneLine())
            capsule.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            capsule.addView(style.text("›", 22f, strong = true))
            drag(capsule) {
                minimized = false
                render()
            }
            bind { if (Build.VERSION.SDK_INT >= 30) capsule.stateDescription = readiness(it) }
            view.addView(capsule, FrameLayout.LayoutParams(-1, -1))
        } else {
            val card = column().apply {
                background = style.panelBackground()
                setPadding(style.dp(12), style.dp(6), style.dp(12), style.dp(8))
            }
            val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
            val title = style.text(text(R.string.game_cal_title), 17f, strong = true).oneLine().apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(title, LinearLayout.LayoutParams(0, style.touchSize, 1f))
            drag(title, null)
            addButton(header, "⋯", width = style.touchSize, selected = more, size = 20f, description = text(R.string.game_cal_more)) {
                more = !more
                render()
            }
            addButton(header, "−", width = style.touchSize, size = 19f, description = text(R.string.game_cal_collapse)) {
                minimized = true
                more = false
                render()
            }
            card.addView(header)
            card.addView(boundText(11f) { value ->
                val target = value.targets.find { it.id == value.selected }?.let(::targetLabel)
                    ?: text(R.string.game_cal_choose_target)
                val register = value.runtime.pitchRegion?.let(::regionLabel) ?: text(R.string.game_cal_unknown)
                val semitone = value.runtime.semitoneEnabled?.let(::semitoneLabel) ?: text(R.string.game_cal_semitone_unknown)
                val saved = text(when {
                    value.draft -> R.string.game_cal_draft
                    value.hasReference -> R.string.game_cal_ready
                    else -> R.string.game_cal_unsaved
                })
                text(R.string.game_cal_summary, target, register, semitone, saved)
            }.apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, sectionParams())

            val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
            val body = column()
            body.addView(boundText(13f) { it.notice }.apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                bind { visibility = if (it.notice.isBlank()) View.GONE else View.VISIBLE }
            }, sectionParams())
            if (more) addMore(body) else {
                body.addView(row().apply {
                    addButton(this, text(R.string.game_cal_save), primary = true, enabled = { !it.busy && it.compatible }, action = actions.save)
                    addButton(this, text(R.string.game_cal_tap), enabled = ::canTap, action = actions.testTap)
                    addButton(this, text(R.string.game_cal_recalibrate), enabled = { !it.busy }, action = actions.recalibrate)
                    val markers = addButton(this, text(R.string.game_cal_hide), enabled = { !it.busy }, action = actions.toggleMarkers)
                    bind { markers.text = text(if (it.shown) R.string.game_cal_hide else R.string.game_cal_show) }
                }, sectionParams())
                body.addView(row().apply {
                    Tab.entries.forEach { next ->
                        addButton(this, text(next.title), selected = next == tab) {
                            if (tab != next) {
                                tab = next
                                render()
                            }
                        }
                    }
                }, sectionParams())
                when (tab) {
                    Tab.POSITION -> addPosition(body)
                    Tab.TEST -> addTest(body)
                    Tab.REGISTER -> addRegister(body)
                }
            }
            scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
            card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            view.addView(card, FrameLayout.LayoutParams(-1, -1))
        }
        onResize()
    }

    private fun addPosition(body: LinearLayout) {
        addTargetPicker(body)
        body.addView(boundText(12f) { state ->
            val target = state.targets.find { it.id == state.selected }
            val display = state.display
            if (target == null || display == null) text(R.string.game_cal_choose_target)
            else {
                val point = Coordinates.toScreen(target.position, display)
                text(R.string.game_cal_coordinates, point.x.roundToInt(), point.y.roundToInt(), target.position.x, target.position.y)
            }
        }, sectionParams())
        body.addView(row().apply {
            val available: (State) -> Boolean = { !it.busy && it.compatible && it.selected != null }
            addButton(this, "X −250", enabled = available, description = text(R.string.game_cal_nudge_x, -250)) { actions.nudge(-250, 0) }
            addButton(this, "X +250", enabled = available, description = text(R.string.game_cal_nudge_x, 250)) { actions.nudge(250, 0) }
            addButton(this, "Y −250", enabled = available, description = text(R.string.game_cal_nudge_y, -250)) { actions.nudge(0, -250) }
            addButton(this, "Y +250", enabled = available, description = text(R.string.game_cal_nudge_y, 250)) { actions.nudge(0, 250) }
        }, sectionParams())
        body.addView(style.text(text(R.string.game_cal_position_tip), 12f), sectionParams())
        body.addView(row().apply {
            val confirm = addButton(this, text(R.string.game_cal_confirm), primary = true,
                enabled = { !it.busy && !it.draft && it.compatible && it.hasReference },
                action = actions.confirmCoordinates)
            bind { confirm.text = text(if (it.coordinatesConfirmed) R.string.game_cal_confirmed else R.string.game_cal_confirm) }
        }, sectionParams())
    }

    private fun addTest(body: LinearLayout) {
        addTargetPicker(body)
        val canPlay: (State) -> Boolean = { state ->
            canTap(state) && state.targets.any { it.id == state.selected && it.type == TargetType.NOTE }
        }
        body.addView(row().apply {
            addButton(this, text(R.string.game_cal_short), enabled = canPlay) { actions.testNote(false) }
            addButton(this, text(R.string.game_cal_hold_action), primary = true, enabled = canPlay) { actions.testNote(true) }
        }, sectionParams())
        body.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(boundText(13f) { text(R.string.game_cal_duration, it.duration) }, LinearLayout.LayoutParams(0, -2, 1f))
            addButton(this, "−250 ms", width = style.dp(80), enabled = { !it.busy && it.duration > 250 }) { actions.adjustDuration(-250) }
            addButton(this, "+250 ms", width = style.dp(80), enabled = { !it.busy && it.duration < 2000 }) { actions.adjustDuration(250) }
        }, sectionParams())
        body.addView(boundText(12f) { it.result.ifBlank { text(R.string.game_cal_results_hint) } }, sectionParams())
    }

    private fun canTap(state: State): Boolean =
        !state.busy && !state.draft && state.hasReference && state.compatible && state.selected != null

    private fun addRegister(body: LinearLayout) {
        fun confidence(status: SyncStatus) = text(when (status) {
            SyncStatus.UNKNOWN -> R.string.game_cal_unknown
            SyncStatus.APP_TRACKED -> R.string.game_cal_sent
            SyncStatus.USER_CONFIRMED -> R.string.game_cal_observed
        })
        body.addView(boundText(12f) {
            val runtime = it.runtime
            text(R.string.game_cal_runtime,
                runtime.pitchRegion?.let(::regionLabel) ?: text(R.string.game_cal_unknown), confidence(runtime.regionStatus),
                runtime.semitoneEnabled?.let(::semitoneLabel) ?: text(R.string.game_cal_unknown), confidence(runtime.semitoneStatus))
        }, sectionParams())
        val allowed: (State) -> Boolean = { !it.busy && it.coordinatesConfirmed && it.compatible && it.hasReference }
        body.addView(style.text(text(R.string.game_cal_send_heading), 13f, strong = true), sectionParams())
        addRegisterRows(body, allowed, actions.sendRegion, actions.sendSemitone)
        body.addView(style.text(text(R.string.game_cal_sync_heading), 13f, strong = true), sectionParams())
        addRegisterRows(body, { !it.busy }, actions.syncRegion, actions.syncSemitone)
        body.addView(boundText(12f) { it.result.ifBlank { text(R.string.overlay_sync_hint) } }, sectionParams())
    }

    private fun addRegisterRows(body: LinearLayout, enabled: (State) -> Boolean, region: (Region) -> Unit, semitone: (Boolean) -> Unit) {
        body.addView(row().apply {
            listOf(Region.LOW, Region.NATURAL, Region.HIGH).forEach {
                addButton(this, regionLabel(it), enabled = enabled) { region(it) }
            }
        }, sectionParams())
        body.addView(row().apply {
            listOf(false, true).forEach {
                addButton(this, semitoneLabel(it), enabled = enabled) { semitone(it) }
            }
        }, sectionParams())
    }

    private fun addMore(body: LinearLayout) {
        body.addView(boundText(12f) {
            text(R.string.game_cal_input,
                text(if (it.root) R.string.game_cal_root else R.string.game_cal_accessibility),
                text(if (it.backendAvailable) R.string.game_cal_input_available else R.string.game_cal_input_unavailable))
        }, sectionParams())
        body.addView(row().apply {
            addButton(this, text(R.string.game_cal_reset), enabled = { !it.busy }, action = actions.resetState)
            addButton(this, text(R.string.game_cal_exit), enabled = { !it.busy }, action = actions.close)
        }, sectionParams())
    }

    private fun addTargetPicker(body: LinearLayout) {
        val picker = Spinner(context, Spinner.MODE_DROPDOWN).apply {
            background = style.panelBackground()
            setPopupBackgroundDrawable(style.panelBackground())
            contentDescription = text(R.string.game_cal_choose_target)
        }
        var ids = emptyList<String>()
        var labels = emptyList<String>()
        val adapter = object : BaseAdapter() {
            override fun getCount() = labels.size
            override fun getItem(position: Int) = labels[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?) =
                (convertView as? TextView ?: style.text("", 14f, strong = true).apply {
                    minHeight = style.touchSize
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(style.dp(10), 0, style.dp(8), 0)
                }).apply { text = labels[position] }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?) = getView(position, convertView, parent)
        }
        picker.adapter = adapter
        picker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                ids.getOrNull(position)?.takeIf { it != state?.selected }?.let(actions.select)
            }
        }
        val field = FrameLayout(context)
        field.addView(picker, FrameLayout.LayoutParams(-1, -1))
        field.addView(style.text("⌄", 18f, strong = true).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(style.dp(30), -1, Gravity.END))
        body.addView(field, sectionParams(style.touchSize))
        bind { value ->
            val next = value.targets.map { it.id }
            val nextLabels = value.targets.map(::targetLabel)
            if (ids != next || labels != nextLabels) {
                ids = next
                labels = nextLabels
                adapter.notifyDataSetChanged()
            }
            val selected = ids.indexOf(value.selected)
            if (selected >= 0 && selected != picker.selectedItemPosition) picker.setSelection(selected, false)
            picker.isEnabled = !value.busy && ids.isNotEmpty()
        }
    }

    fun targetLabel(target: TouchTarget): String = when (target.id) {
        "REGION_LOW" -> regionLabel(Region.LOW)
        "REGION_NATURAL" -> regionLabel(Region.NATURAL)
        "REGION_HIGH" -> regionLabel(Region.HIGH)
        "SEMITONE_TOGGLE" -> text(R.string.game_cal_semitone)
        else -> target.id.removePrefix("NOTE_").toIntOrNull()?.takeIf { target.id.startsWith("NOTE_") }
            ?.let { text(R.string.overlay_key, it) } ?: target.label
    }

    private fun regionLabel(region: Region) = text(when (region) {
        Region.LOW -> R.string.overlay_low
        Region.NATURAL -> R.string.overlay_natural
        Region.HIGH -> R.string.overlay_high
    })

    private fun semitoneLabel(enabled: Boolean) =
        text(if (enabled) R.string.overlay_semitone_on else R.string.overlay_semitone_off)

    private fun readiness(value: State) = text(when {
        value.busy -> R.string.game_cal_busy
        !value.compatible -> R.string.game_cal_need_display
        value.draft -> R.string.game_cal_draft
        value.coordinatesConfirmed -> R.string.game_cal_confirmed
        value.hasReference -> R.string.game_cal_ready
        else -> R.string.game_cal_unsaved
    })

    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun sectionParams(height: Int = -2) =
        LinearLayout.LayoutParams(-1, height).apply { topMargin = style.dp(5) }

    private fun TextView.oneLine() = apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun bind(block: (State) -> Unit) {
        bindings.add(block)
        state?.let(block)
    }

    private fun boundText(size: Float, value: (State) -> String) = style.text("", size).apply {
        bind { state ->
            val next = value(state)
            if (text != next) text = next
        }
    }

    private fun addButton(
        row: LinearLayout,
        label: String,
        width: Int = 0,
        selected: Boolean = false,
        primary: Boolean = false,
        size: Float = 12f,
        description: String? = null,
        enabled: (State) -> Boolean = { true },
        action: () -> Unit,
    ): Button = style.button(label, selected, primary, size = size, action = action).apply {
        description?.let { contentDescription = it }
        val gap = if (row.childCount == 0) 0 else style.dp(4)
        row.addView(this, LinearLayout.LayoutParams(width, style.touchSize, if (width == 0) 1f else 0f).apply {
            marginStart = gap
        })
        bind { isEnabled = enabled(it) }
    }
}
