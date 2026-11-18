package com.huanhren.deltaharmonica.overlay

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.view.ViewConfiguration
import androidx.compose.runtime.snapshotFlow
import com.huanhren.deltaharmonica.ui.theme.ThemeMode
import kotlinx.coroutines.flow.combine
import kotlin.math.abs
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.huanhren.deltaharmonica.DeltaHarmonicaApp
import com.huanhren.deltaharmonica.input.TapResult
import com.huanhren.deltaharmonica.input.NoteAction
import com.huanhren.deltaharmonica.input.NoteActionType
import com.huanhren.deltaharmonica.input.NoteActionState
import com.huanhren.deltaharmonica.input.DisplaySnapshot
import com.huanhren.deltaharmonica.input.FocusedWindow
import com.huanhren.deltaharmonica.input.RootGestureConnection
import com.huanhren.deltaharmonica.input.RootCleanup
import com.huanhren.deltaharmonica.input.displaySnapshot
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.playback.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import kotlin.coroutines.resume

enum class OverlayTestState { READY, HIDING_OVERLAYS, DISPATCHING, GESTURE_COMPLETED, GESTURE_CANCELLED, FAILED, RESTORING_OVERLAYS }

class CalibrationOverlayService : Service() {
    companion object {
        const val ACTION_START_PLAYBACK = "com.huanhren.deltaharmonica.START_PLAYBACK"
        const val ACTION_SHOW_PLAYER = "com.huanhren.deltaharmonica.SHOW_PLAYER"
        const val ACTION_SHOW_CALIBRATION = "com.huanhren.deltaharmonica.SHOW_CALIBRATION"
    }

    private val app get() = application as DeltaHarmonicaApp
    private val overlayPrefs by lazy { getSharedPreferences("overlay_ui", MODE_PRIVATE) }
    private var gamePanel: GamePlayerPanel? = null
    private var gamePanelJob: Job? = null
    private var openAsPlayer = false
    private var attachedLayout: InstrumentLayout? = null

    private fun overlayStyle(): OverlayStyle {
        val dark = when (app.preferences.theme.value) {
            ThemeMode.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        return OverlayStyle(this, dark)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private lateinit var displayManager: DisplayManager
    private var panel: View? = null
    private var loadingPanel = false
    private var startAfterLoad = false
    private val markers = mutableListOf<View>()
    private var layout: InstrumentLayout? = null
    private var snapshot: DisplaySnapshot? = null
    private var selected: String? = null
    private var shown = true
    private var dirty = false
    private var saving = false
    private var testJob: Job? = null
    private var armJob: Job? = null
    private var focusJob: Job? = null
    @Volatile private var focusPackage: String? = null
    @Volatile private var focusObservedAt: Long = 0
    private var playbackRunner: PlaybackRunner? = null
    private var stopView: View? = null
    private var destroyed = false
    private var panelAttached = false
    private var panelParams: WindowManager.LayoutParams? = null
    private var testState = OverlayTestState.READY
    private var runtimeState: InstrumentRuntimeState
        get() = PlaybackPreparation.runtimeState.value
        set(value) { PlaybackPreparation.runtimeState.value = value }
    private var controlsCalibrated = false
    private lateinit var runtimeInfo: TextView
    private var holdDuration = 500L
    private var currentAction: NoteAction? = null
    private lateinit var durationLabel: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var targetInfo: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = checkDisplay()
        override fun onDisplayChanged(displayId: Int) = checkDisplay()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(listener, null)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state="悬浮窗权限不可用，请在系统设置中开启",phase=PlaybackStatus.FAILED); stopSelf(); return START_NOT_STICKY }
        val starting = intent?.action == ACTION_START_PLAYBACK
        val requestedPlayer = starting || intent?.action == ACTION_SHOW_PLAYER
        if (panel != null && (testJob?.isActive == true || armJob?.isActive == true)) return START_NOT_STICKY
        if (dirty && requestedPlayer) {
            status.text = "请先保存校准草稿，再打开播放器"
            if (starting) rejectPendingStart(status.text.toString())
            return START_NOT_STICKY
        }
        if (loadingPanel) {
            if (starting) { startAfterLoad = true; openAsPlayer = true }
            return START_NOT_STICKY
        }
        loadingPanel = true
        startAfterLoad = starting
        openAsPlayer = requestedPlayer
        scope.launch {
            try {
                // Reload persisted coordinates when returning from an in-app calibration.
                val saved = if (dirty) attachedLayout else app.layouts.load()
                if (!dirty) {
                    attachedLayout = saved
                    layout = saved ?: DefaultLayouts.create()
                }
                val existing = panel
                if (existing == null || openAsPlayer != (gamePanel != null)) {
                    detachPanel()
                    if (openAsPlayer) showGamePanel() else showPanel()
                } else gamePanel?.refresh()
                checkDisplay()
                if (startAfterLoad) { startAfterLoad = false; armPlayback() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (startAfterLoad || starting) rejectPendingStart("悬浮窗无法打开：${e.localizedMessage}")
                Toast.makeText(this@CalibrationOverlayService, "悬浮窗无法打开：${e.localizedMessage}", Toast.LENGTH_LONG).show()
                stopSelf()
            } finally { loadingPanel = false }
        }
        return START_NOT_STICKY
    }

    private fun rejectPendingStart(message: String) {
        PlaybackPreparation.pending = null
        PlaybackPreparation.progress.value = PlaybackPreparation.progress.value.copy(state = message, phase = PlaybackStatus.FAILED)
    }

    private fun detachPanel() {
        gamePanelJob?.cancel()
        gamePanelJob = null
        gamePanel?.dispose()
        gamePanel = null
        removeMarkers()
        panel?.let { if (panelAttached) wm.removeViewImmediate(it) }
        panelAttached = false
        panel = null
    }

    private fun showGamePanel() {
        status = overlayStyle().text("")
        val model = app.model
        val player = GamePlayerPanel(this, overlayStyle(), model, { attachedLayout }, ::resizeGamePanel,
            ::attachPlayerDrag, onClose = {
                if (!PlaybackPreparation.stopPlayback()) stopSelf()
            }, onArm = ::armPlayback)
        gamePanel = player
        panel = player.view
        panelParams = params(dp(52), dp(52))
        val start = displaySnapshot(this).size
        val origin = playerOrigin(start)
        panelParams?.apply { x = origin.x.roundToInt(); y = origin.y.roundToInt() }
        wm.addView(player.view, panelParams)
        panelAttached = true
        resizeGamePanel()
        gamePanelJob = scope.launch {
            launch { PlaybackPreparation.progress.collect(player::applyProgress) }
            launch {
                combine(model.songs.items, model.songs.problems, model.libraryLoading, model.libraryBusy, model.libraryError) {
                        _, _, _, _, _ -> Unit
                }.collect { player.refresh() }
            }
            launch {
                combine(model.imports.state, snapshotFlow {
                    val workspace = model.midiWorkspace
                    Triple(workspace.name, workspace.range, workspace.speed)
                }) { _, _ -> Unit }.collect { player.refresh() }
            }
        }
    }

    private fun playerOrigin(size: ScreenSize) = ScreenPoint(
        overlayPrefs.getFloat("x", .88f).coerceIn(0f, 1f) * (size.width - dp(52)).coerceAtLeast(0),
        overlayPrefs.getFloat("y", .24f).coerceIn(0f, 1f) * (size.height - dp(52)).coerceAtLeast(0),
    )

    private fun resizeGamePanel() {
        val game = gamePanel ?: return
        val lp = panelParams ?: return
        if (!panelAttached) return
        val size = displaySnapshot(this).size
        lp.width = if (game.minimized) dp(52) else minOf(dp(292), (size.width - dp(12)).coerceAtLeast(dp(52)))
        lp.height = if (game.minimized) dp(52) else WindowManager.LayoutParams.WRAP_CONTENT
        val anchor = playerOrigin(size)
        lp.x = anchor.x.roundToInt().coerceIn(0, (size.width - lp.width).coerceAtLeast(0))
        lp.y = anchor.y.roundToInt()
        wm.updateViewLayout(game.view, lp)
        game.view.post {
            if (gamePanel === game && panelAttached) movePanel(ScreenPoint(lp.x.toFloat(), lp.y.toFloat()))
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachPlayerDrag(view: View, onClick: (() -> Unit)?) {
        val threshold = ViewConfiguration.get(this).scaledTouchSlop
        if (onClick != null) view.setOnClickListener { onClick() }
        view.setOnTouchListener(object : View.OnTouchListener {
            private var down = ScreenPoint(0f, 0f)
            private var origin = ScreenPoint(0f, 0f)
            private var dragging = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val lp = panelParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        down = ScreenPoint(event.rawX, event.rawY)
                        origin = ScreenPoint(lp.x.toFloat(), lp.y.toFloat())
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - down.x
                        val dy = event.rawY - down.y
                        if (abs(dx) >= threshold || abs(dy) >= threshold) dragging = true
                        if (dragging) movePanel(ScreenPoint(origin.x + dx, origin.y + dy))
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            val size = (snapshot ?: displaySnapshot(this@CalibrationOverlayService)).size
                            val x = lp.x.toFloat() / (size.width - dp(52)).coerceAtLeast(1)
                            val y = lp.y.toFloat() / (size.height - dp(52)).coerceAtLeast(1)
                            overlayPrefs.edit().putFloat("x", x).putFloat("y", y).apply()
                        } else if (onClick != null) v.performClick()
                    }
                }
                return true
            }
        })
    }
    private fun armPlayback() {
        if (armJob?.isActive == true || testJob?.isActive == true) { PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state="已有播放请求正在执行，请等待或停止"); return }
        val prepared=PlaybackPreparation.pending
        if (prepared == null || prepared.plan.events.isEmpty() || prepared.plan.events.size != prepared.count || prepared.plan.fatalErrors.isNotEmpty()) {
            PlaybackPreparation.progress.value=PlaybackProgress(state="脚本或播放范围无效，请重新检查",phase=PlaybackStatus.FAILED); return
        }
        if (!PlaybackPreparation.controlsConfirmed) { PlaybackPreparation.progress.value=PlaybackProgress(prepared.count,state="请先确认控制点已校准",phase=PlaybackStatus.FAILED); return }
        controlsCalibrated=true
        PlaybackPreparation.progress.value=PlaybackProgress.waitingForGame(prepared.plan)
        Log.i("DeltaPlayback", "waiting count=${prepared.plan.events.size}")
        armJob=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val app = application as DeltaHarmonicaApp
                val root = app.rootBackend
                val accessibility = app.accessibilityBackend
                val preferRoot = root.preferred
                val input = selectPlaybackInput(preferRoot, { accessibility.status().available }) {
                    PlaybackPreparation.progress.value = PlaybackPreparation.progress.value.copy(state = "正在检查 Root 授权")
                    root.status().available || root.checkAccess().available
                }
                if (input == null || root.releaseUncertain) {
                    val reason = if (preferRoot || root.releaseUncertain) root.status().message else accessibility.status().message
                    Log.w("DeltaPlayback", "input unavailable: $reason")
                    PlaybackPreparation.progress.value = PlaybackPreparation.progress.value.copy(state = reason, phase = PlaybackStatus.FAILED)
                    return@launch
                }
                Log.i("DeltaPlayback", "preflight backend=$input requestedRoot=$preferRoot")
                focusPackage=null; focusObservedAt=0
                focusJob=scope.launch {
                    while (isActive) {
                        val observed = input.focusedPackage(root::focusedPackage, accessibility::focusedPackage)
                        focusPackage=observed; focusObservedAt=android.os.SystemClock.elapsedRealtime()
                        delay(100)
                    }
                }
                PlaybackPreparation.progress.value=PlaybackProgress.waitingForGame(prepared.plan)
                val found=GameWindowWaiter.awaitGame(60_000) { hasFreshGameFocus() }
                if (!found) { Log.w("DeltaPlayback", "game focus confirmation timed out"); PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state="无法确认游戏窗口：请切回游戏口琴界面并重试",phase=PlaybackStatus.FAILED) }
                else { Log.i("DeltaPlayback", "game window confirmed"); nextFrame(); nextFrame(); startPlayback(input) }
            } catch (_: CancellationException) { PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state=if(destroyed) "校准服务已停止，请重新启动" else "已取消等待，未发送触摸",phase=if(destroyed) PlaybackStatus.FAILED else PlaybackStatus.COMPLETED) }
            catch (e: Exception) { rejectPendingStart("准备演奏失败：${e.localizedMessage}") }
            finally {
                PlaybackPreparation.cancelPendingStart = null
                if (testJob?.isActive != true) {
                    if (PlaybackPreparation.pending === prepared) PlaybackPreparation.pending = null
                    focusJob?.cancel()
                    focusJob = null
                    focusPackage = null
                }
            }
        }
        PlaybackPreparation.cancelPendingStart={ armJob?.cancel() }
        armJob?.start()
    }
    private fun hasFreshGameFocus() = FocusedWindow.isFreshGame(focusPackage,focusObservedAt,android.os.SystemClock.elapsedRealtime())
    private fun gameUid(): Int? = runCatching {
        packageManager.getApplicationInfo(FocusedWindow.GAME_PACKAGE, 0).uid
    }.getOrNull()
    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun showPanel() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6),dp(4),dp(6),dp(4)); setBackgroundColor(0xee202830.toInt()) }
        val dragHandle = object : TextView(this) {
            override fun performClick(): Boolean = super.performClick()
        }.apply {
            text = "拖动面板"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        }
        box.addView(dragHandle)
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; text = "等待横屏" }
        box.addView(status)
        serviceStatus = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f }
        targetInfo = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f }
        box.addView(serviceStatus); box.addView(targetInfo)
        val targets = layout?.targets.orEmpty()
        val chooser = Spinner(this).apply {
            adapter = ArrayAdapter(this@CalibrationOverlayService, android.R.layout.simple_spinner_dropdown_item, targets.map { it.label })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) { selected = null; updateTargetInfo() }
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selected = targets.getOrNull(position)?.id; updateTargetInfo()
                }
            }
        }
        box.addView(chooser)
        updateServiceStatus()
        val row = LinearLayout(this)
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 10f; setPadding(0,0,0,0); setOnClickListener { action() }
            row.addView(this, LinearLayout.LayoutParams(dp(58), dp(42)))
        }
        button("保存") { save() }
        button("测试点击") { testClick() }
        toggle = button("隐藏") { if (saving) return@button; shown = !shown; toggle.text = if (shown) "隐藏" else "恢复"; refreshMarkers() }
        button("重校准") { if (!saving) recalibrate() }
        button("退出") { if (saving) return@button; if (dirty) Toast.makeText(this, "未保存的更改已放弃", Toast.LENGTH_SHORT).show(); stopSelf() }
        box.addView(row)
        val noteRow = LinearLayout(this)
        fun noteButton(label: String, action: () -> Unit) {
            noteRow.addView(Button(this).apply { text = label; textSize = 10f; setPadding(0,0,0,0); setOnClickListener { action() } }, LinearLayout.LayoutParams(dp(58), dp(42)))
        }
        noteButton("短按") { testClick(150L, NoteActionType.TAP) }
        noteButton("长按") { testClick(holdDuration, NoteActionType.HOLD) }
        noteButton("−250") { holdDuration = (holdDuration - 250).coerceAtLeast(250); durationLabel.text = "长按 ${holdDuration}ms" }
        noteButton("+250") { holdDuration = (holdDuration + 250).coerceAtMost(2000); durationLabel.text = "长按 ${holdDuration}ms" }
        durationLabel = TextView(this).apply { text = "长按 ${holdDuration}ms"; setTextColor(Color.WHITE); textSize = 11f }
        box.addView(noteRow); box.addView(durationLabel)
        runtimeInfo = TextView(this).apply { setTextColor(Color.WHITE); textSize = 10f }
        box.addView(runtimeInfo)
        fun controlRow(items: List<Pair<String, () -> Unit>>) {
            val row = LinearLayout(this)
            items.forEach { (label, action) -> row.addView(Button(this).apply {
                text = label; textSize = 9f; setPadding(0,0,0,0); setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(58), dp(40))) }
            box.addView(row)
        }
        controlRow(listOf("升调" to { requestRegion(Region.HIGH) }, "自然" to { requestRegion(Region.NATURAL) }, "降调" to { requestRegion(Region.LOW) }, "开半音" to { requestSemitone(true) }, "关半音" to { requestSemitone(false) }))
        controlRow(listOf("同步高" to { confirmRegion(Region.HIGH) }, "同步中" to { confirmRegion(Region.NATURAL) }, "同步低" to { confirmRegion(Region.LOW) }, "半音开" to { confirmSemitone(true) }, "半音关" to { confirmSemitone(false) }))
        controlRow(listOf("坐标确认" to { if (testJob?.isActive != true && !dirty) { controlsCalibrated = true; PlaybackPreparation.controlsConfirmed=true; status.text = "已确认控制点坐标；请随时按游戏状态重新同步"; updateRuntimeInfo() } else status.text = "先保存布局并等待操作结束" },
            "状态重置" to { if (testJob?.isActive != true) { runtimeState = runtimeState.reset(); updateRuntimeInfo(); status.text = "游戏状态已设为未知" } }))
        updateRuntimeInfo()
        panel = box
        panelParams = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var pointerOrigin = ScreenPoint(0f, 0f)
            private var panelOrigin = ScreenPoint(0f, 0f)
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val params = panelParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pointerOrigin = ScreenPoint(event.rawX, event.rawY)
                        panelOrigin = ScreenPoint(params.x.toFloat(), params.y.toFloat())
                    }
                    MotionEvent.ACTION_MOVE -> movePanel(ScreenPoint(
                        panelOrigin.x + event.rawX - pointerOrigin.x,
                        panelOrigin.y + event.rawY - pointerOrigin.y,
                    ))
                    MotionEvent.ACTION_UP -> view.performClick()
                }
                return true
            }
        })
        wm.addView(box, panelParams); panelAttached = true
    }

    private fun movePanel(origin: ScreenPoint) {
        val view = panel ?: return
        val params = panelParams ?: return
        val display = snapshot ?: return
        if (!panelAttached) return
        val clamped = OverlayGeometry.clampPanelOrigin(origin, display.size, view.width, view.height)
        val x = clamped.x.roundToInt()
        val y = clamped.y.roundToInt()
        if (params.x != x || params.y != y) {
            params.x = x
            params.y = y
            wm.updateViewLayout(view, params)
        }
    }

    private fun checkDisplay() {
        if (panel == null) return
        val next = displaySnapshot(this)
        if (testJob?.isActive == true && snapshot != next) {
            snapshot = next
            if (playbackRunner != null) playbackRunner?.requestStop() else testJob?.cancel(CancellationException("屏幕环境已改变"))
            return
        }
        if (snapshot != null && snapshot != next && dirty) {
            shown = false
            toggle.text = "恢复"
            status.text = "屏幕变化，未保存坐标暂停；请重新校准"
        }
        val previous = snapshot
        snapshot = next
        gamePanel?.let {
            if (next != previous) {
                val density = resources.displayMetrics.density
                it.markBounds((next.size.height / density).roundToInt())
                resizeGamePanel()
            }
            return
        }
        panelParams?.let { movePanel(ScreenPoint(it.x.toFloat(), it.y.toFloat())) }
        refreshMarkers()
    }
    private fun recalibrate() {
        val current = snapshot ?: return
        val draft = layout ?: return
        if (!current.supported || current.size.orientation != draft.orientation) {
            status.text = "请先进入布局对应的横屏全屏界面"; return
        }
        controlsCalibrated = false
        PlaybackPreparation.controlsConfirmed = false
        layout = draft.copy(referenceDisplay = ReferenceDisplay(current.size.width, current.size.height))
        dirty = true; shown = true; toggle.text = "隐藏"; refreshMarkers()
    }
    private fun refreshMarkers() {
        removeMarkers()
        if (gamePanel != null) return
        val draft = layout ?: return
        val current = snapshot ?: return
        if (!current.supported || !OverlayGeometry.compatible(draft, current.size)) {
            status.text = "方向或屏幕尺寸不匹配；进入横屏后点重校准"
            return
        }
        if (!shown) { status.text = "标记已隐藏 · 点击恢复"; return }
        status.text = if (draft.referenceDisplay == null) "尺寸未知，请校准并保存" else "拖动标记 · 点击选择"
        val markerSize = dp(48)
        draft.targets.forEach { target ->
            val view = TextView(this).apply {
                text = target.label; textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (target.id == selected) 0xcc007bff.toInt() else 0xcc152f40.toInt()); setStroke(dp(2), Color.WHITE) }
                contentDescription = "校准 ${target.label}"
            }
            val point = OverlayGeometry.windowOrigin(target.position, current.size, markerSize)
            val lp = params(markerSize, markerSize).apply { x = point.x.roundToInt(); y = point.y.roundToInt() }
            view.setOnTouchListener(object : View.OnTouchListener {
                var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    if (saving) return true
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = lp.x; startY = lp.y; selected = target.id; updateTargetInfo(); return true }
                        MotionEvent.ACTION_MOVE -> {
                            lp.x = startX + (event.rawX - downX).roundToInt(); lp.y = startY + (event.rawY - downY).roundToInt()
                            val normalized = OverlayGeometry.pointFromOrigin(ScreenPoint(lp.x.toFloat(), lp.y.toFloat()), current.size, markerSize)
                            val origin = OverlayGeometry.windowOrigin(normalized, current.size, markerSize)
                            lp.x = origin.x.roundToInt(); lp.y = origin.y.roundToInt()
                            wm.updateViewLayout(v, lp)
                            layout = layout?.let { it.copy(targets = it.targets.map { t -> if (t.id == target.id) t.copy(position = normalized) else t }) }
                            dirty = true
                            controlsCalibrated = false
                            PlaybackPreparation.controlsConfirmed = false
                            updateRuntimeInfo()
                            updateTargetInfo()
                            status.text = "${target.label}  x=%.4f y=%.4f".format(normalized.x, normalized.y)
                            return true
                        }
                        MotionEvent.ACTION_UP -> { val p = layout?.targets?.find { it.id == target.id }?.position; status.text = "${target.label}  x=%.4f y=%.4f".format(p?.x ?: 0f, p?.y ?: 0f); return true }
                    }
                    return false
                }
            })
            wm.addView(view, lp); markers.add(view)
        }
        // Keep panel controls above markers where their bounds overlap.
        panel?.let { view ->
            if (panelAttached) {
                wm.removeViewImmediate(view); panelAttached = false
                wm.addView(view, panelParams); panelAttached = true
            }
        }
    }
    private fun removeMarkers() {
        while (markers.isNotEmpty()) {
            val marker = markers.last()
            wm.removeViewImmediate(marker)
            markers.removeAt(markers.lastIndex)
        }
    }
    private fun updateServiceStatus() {
        val app = application as DeltaHarmonicaApp
        serviceStatus.text = if (app.rootBackend.preferred) "Root：${app.rootBackend.status().message}"
            else "无障碍：${app.accessibilityBackend.status().message}"
    }
    private fun updateTargetInfo() {
        val target = layout?.targets?.find { it.id == selected }
        targetInfo.text = target?.let { "目标：${it.label}  x=%.4f y=%.4f".format(it.position.x, it.position.y) } ?: "请选择目标"
    }
    private suspend fun nextFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }
    private fun updateRuntimeInfo() {
        fun confidence(s: SyncStatus) = when (s) { SyncStatus.UNKNOWN -> "未知"; SyncStatus.APP_TRACKED -> "已发送待确认"; SyncStatus.USER_CONFIRMED -> "用户确认" }
        val region = when (runtimeState.pitchRegion) { Region.HIGH -> "升调"; Region.NATURAL -> "自然"; Region.LOW -> "降调"; null -> "未知" }
        val semitone = when (runtimeState.semitoneEnabled) { true -> "开"; false -> "关"; null -> "未知" }
        runtimeInfo.text = "音区:$region (${confidence(runtimeState.regionStatus)}) 半音:$semitone (${confidence(runtimeState.semitoneStatus)})\n控制点:${if (controlsCalibrated) "已确认" else "待确认"}"
    }
    private fun confirmRegion(region: Region) {
        if (testJob?.isActive == true) { status.text = "操作正在执行"; return }
        runtimeState = runtimeState.confirmRegion(region); updateRuntimeInfo(); status.text = "已按用户观察同步音区"
    }
    private fun confirmSemitone(enabled: Boolean) {
        if (testJob?.isActive == true) { status.text = "操作正在执行"; return }
        runtimeState = runtimeState.confirmSemitone(enabled); updateRuntimeInfo(); status.text = "已按用户观察同步半音"
    }
    private fun requestRegion(region: Region) {
        if (testJob?.isActive == true) { status.text = "操作正在执行"; return }
        if (!controlsCalibrated) { status.text = "请先确认控制点已对准并保存"; return }
        if (runtimeState.planRegion(region) == ControlDecision.SKIP) { status.text = "音区相同，已跳过；请确认游戏状态"; return }
        testClick(targetOverride = "REGION_${region.name}", onControlResult = { result ->
            runtimeState = runtimeState.regionResult(region, result); updateRuntimeInfo()
            if (result == TapResult.Completed) status.text = "音区 ${region.name} 已发送，等待确认游戏状态"
            else status.text = "音区切换未确认，请重新同步：$result"
        })
    }
    private fun requestSemitone(next: Boolean) {
        if (testJob?.isActive == true) { status.text = "操作正在执行"; return }
        when (runtimeState.planSemitone(next)) {
            ControlDecision.NEEDS_SYNC -> { status.text = "半音未知，请先按游戏显示同步开或关"; return }
            ControlDecision.SKIP -> { status.text = "半音状态相同，已跳过；请确认游戏状态"; return }
            ControlDecision.SEND -> Unit
        }
        if (!controlsCalibrated) { status.text = "请先确认控制点已对准并保存"; return }
        testClick(targetOverride = "SEMITONE_TOGGLE", onControlResult = { result ->
            runtimeState = runtimeState.semitoneResult(next, result); updateRuntimeInfo()
            if (result == TapResult.Completed) status.text = "半音切换已发送，等待确认游戏状态"
            else status.text = "半音状态未知，请重新同步：$result"
        })
    }
    private fun testClick(durationMs: Long? = null, actionType: NoteActionType = NoteActionType.TAP,
        targetOverride: String? = null, onControlResult: ((TapResult) -> Unit)? = null) {
        if (saving || testJob?.isActive == true) { status.text = "正在执行，请稍候"; return }
        val draft = layout ?: return
        val before = snapshot ?: return
        val targetId = targetOverride ?: selected ?: run { status.text = "请选择目标"; return }
        if (dirty) { status.text = "请先保存校准，再测试"; return }
        val chosen = draft.targets.find { it.id == targetId }
        if (chosen == null) { status.text = "目标不存在"; return }
        if (durationMs != null && chosen.type != TargetType.NOTE) { status.text = "请选择音键；控制目标请使用测试点击"; return }
        val app = application as DeltaHarmonicaApp
        val backend = app.accessibilityBackend
        val root = app.rootBackend
        updateServiceStatus()
        if (root.releaseUncertain) { status.text = "上次 Root 触摸释放待确认，请先检查游戏"; return }
        if (!root.preferred && !backend.status().available) { status.text = backend.status().message; return }
        if (!before.supported || displaySnapshot(this) != before || draft.referenceDisplay == null || !OverlayGeometry.compatible(draft, before.size)) {
            status.text = "布局未校准或显示环境不匹配，请重新校准"; return
        }
        if (onControlResult == null && durationMs == null && chosen.type != TargetType.NOTE) {
            runtimeState = runtimeState.reset(); updateRuntimeInfo()
            status.text = "手动测试控制键，游戏状态已设为未知"
        }
        val panelView = panel ?: return
        val originalShown = shown
        testJob = scope.launch(start = CoroutineStart.LAZY) {
            var result: TapResult? = null
            var touch: RootGestureConnection? = null
            try {
                if (root.preferred && root.status().available) touch = gameUid()?.let { root.openTouch(it) }
                if (touch == null && !backend.status().available) {
                    result = TapResult.Unavailable("Root 输入不可用，无障碍回退未连接")
                    return@launch
                }
                result = OverlayTapCoordinator.execute(draft, targetId, before.size, true,
                    hide = {
                        testState = OverlayTestState.HIDING_OVERLAYS
                        removeMarkers()
                        if (panelAttached) { wm.removeViewImmediate(panelView); panelAttached = false }
                    },
                    awaitWindows = { nextFrame(); nextFrame() },
                    environmentValid = { displaySnapshot(this@CalibrationOverlayService) == before &&
                        (touch != null || backend.status().available) },
                    dispatch = { point ->
                        testState = OverlayTestState.DISPATCHING
                        val rootTouch = touch
                        if (rootTouch != null) rootTouch.play(
                            CombinedGesture.create(point, durationMs ?: 60L, emptyList()),
                            stop = { destroyed || displaySnapshot(this@CalibrationOverlayService) != before },
                        ) else if (durationMs == null) backend.tap(point) else {
                            val action = NoteAction(targetId, point, actionType, durationMs, android.os.SystemClock.elapsedRealtime(), NoteActionState.DISPATCHING)
                            currentAction = action
                            val outcome = backend.perform(action)
                            currentAction = action.copy(state = when (outcome) {
                                TapResult.Completed -> NoteActionState.COMPLETED
                                TapResult.Cancelled -> NoteActionState.CANCELLED
                                else -> NoteActionState.FAILED
                            }, failureReason = when (outcome) {
                                is TapResult.Unavailable -> outcome.reason
                                is TapResult.InvalidPoint -> outcome.reason
                                TapResult.TimedOut -> "超时，释放状态未确认"
                                TapResult.Rejected -> "系统拒绝手势"
                                TapResult.Busy -> "已有手势执行中"
                                else -> null
                            })
                            outcome
                        }
                    },
                    restore = {
                        testState = OverlayTestState.RESTORING_OVERLAYS
                        if (!destroyed) {
                            if (!panelAttached) { wm.addView(panelView, panelParams); panelAttached = true }
                            shown = originalShown
                            snapshot = displaySnapshot(this@CalibrationOverlayService)
                            refreshMarkers()
                        }
                    })
            } catch (_: CancellationException) {
                result = TapResult.Cancelled
            } catch (e: Exception) {
                result = TapResult.Unavailable("恢复悬浮层失败：${e.localizedMessage}")
                Toast.makeText(this@CalibrationOverlayService, result.reason, Toast.LENGTH_LONG).show()
            } finally {
                touch?.let { root.closeTouch(it) }
                if (!destroyed && panelAttached) {
                    testState = when (result) {
                        TapResult.Completed -> OverlayTestState.GESTURE_COMPLETED
                        TapResult.Cancelled -> OverlayTestState.GESTURE_CANCELLED
                        else -> OverlayTestState.FAILED
                    }
                    status.text = when (val outcome = result) {
                        TapResult.Completed -> "系统已完成触摸手势，请确认游戏按键是否响应。悬浮层已恢复"
                        TapResult.Cancelled -> "手势取消或屏幕变化；释放状态请检查；悬浮层已恢复"
                        TapResult.TimedOut -> "手势超时，释放状态未确认；悬浮层已恢复"
                        TapResult.Rejected -> "系统拒绝手势；悬浮层已恢复"
                        TapResult.Busy -> "无障碍手势正忙；悬浮层已恢复"
                        is TapResult.Unavailable -> "${outcome.reason}；请检查悬浮层状态"
                        is TapResult.InvalidPoint -> "${outcome.reason}；悬浮层已恢复"
                        null -> "测试未完成；请检查悬浮层状态"
                    }
                    updateServiceStatus(); updateTargetInfo()
                    onControlResult?.invoke(result ?: TapResult.Unavailable("未完成"))
                }
            }
        }
        testJob?.start()
    }
    private fun startPlayback(input: PlaybackInput) {
        fun reject(message: String) { Log.w("DeltaPlayback", "preflight rejected: $message"); status.text=message; PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state=message,phase=PlaybackStatus.FAILED) }
        if (saving || dirty || testJob?.isActive == true) { reject("请保存布局并等待当前操作结束"); return }
        val prepared = PlaybackPreparation.pending ?: run { reject("请重新选择曲目并开始"); return }
        val draft = layout ?: run { reject("布局不可用，请重新打开校准"); return }
        val before = snapshot ?: run { reject("屏幕信息不可用，请返回游戏后重试"); return }
        val app = application as DeltaHarmonicaApp
        val backend = app.accessibilityBackend
        val root = app.rootBackend
        if (root.releaseUncertain) { reject("上次 Root 触摸释放待确认，请先检查游戏"); return }
        if ((input == PlaybackInput.ACCESSIBILITY && !backend.status().available) || !Settings.canDrawOverlays(this)) {
            reject("请检查输入后端和悬浮窗权限"); return
        }
        if (!controlsCalibrated || runtimeState.semitoneStatus != SyncStatus.USER_CONFIRMED || runtimeState.semitoneEnabled == null) {
            reject("请确认控制点并同步游戏半音状态"); return
        }
        if (!before.supported || displaySnapshot(this) != before || !OverlayGeometry.compatible(draft, before.size)) {
            reject("屏幕方向或尺寸不匹配，请重新校准"); return
        }
        val fresh = runCatching { PlaybackPlanner.plan(prepared.script,draft,runtimeState.pitchRegion,runtimeState.semitoneEnabled,prepared.start,prepared.count,prepared.speed,combined=prepared.plan.combined,minimumTouchMs=prepared.plan.minimumTouchMs,combinedLeadMs=prepared.plan.combinedLeadMs) }.getOrNull()
        if (fresh == null || fresh.fatalErrors.isNotEmpty() || (fresh.warnings.isNotEmpty() && !prepared.warningsConfirmed) || fresh.warnings.any { it !in prepared.plan.warnings }) {
            reject("预检未通过，请检查目标、布局和警告"); return
        }
        val panelView = panel ?: return
        val originalShown = shown
        val runner = PlaybackRunner(); playbackRunner = runner; PlaybackPreparation.activeRunner = runner
        Log.i("DeltaPlayback", "session start count=${fresh.events.size} combined=${fresh.combined}")
        PlaybackPreparation.progress.value = PlaybackProgress(fresh.events.size,0,null,"正在隐藏悬浮层",PlaybackStatus.CHECKING)
        PlaybackPreparation.actualTimings.clear()
        PlaybackPreparation.pending = null
        testJob = scope.launch(start = CoroutineStart.LAZY) {
            var outcome: PlaybackResult? = null
            var sessionStartMs=0L
            var currentSourceIndex: Int? = null
            var overlayRestored=false
            var touch: RootGestureConnection? = null
            fun environmentValid() = !destroyed && displaySnapshot(this@CalibrationOverlayService) == before &&
                (touch != null || backend.status().available) && Settings.canDrawOverlays(this@CalibrationOverlayService) && hasFreshGameFocus()
            suspend fun playRoot(connection: RootGestureConnection, gesture: CombinedGesture, index: Int?, duration: Long): TapResult {
                if (gesture.strokes.any { !root.validPoint(it.point) }) return TapResult.InvalidPoint("坐标不在默认屏幕内")
                return connection.play(gesture, stop = { runner.stopRequested || !environmentValid() }) { timing ->
                    val down = timing.noteDown
                    val up = timing.noteUp
                    if (index != null && down != null) {
                        PlaybackPreparation.actualTimings[index] = ActualTiming(
                            down - sessionStartMs, up?.minus(sessionStartMs), 0, duration, "Root ${timing.status}",
                        )
                        Log.i("DeltaRoot", "event=$index requestedMs=$duration down=$down up=$up status=${timing.status}")
                    }
                }
            }
            fun timingResult(result: TapResult): String = when(result) {
                TapResult.Completed -> "系统报告完成"
                TapResult.Cancelled -> "系统报告取消"
                TapResult.Rejected -> "系统拒绝派发"
                TapResult.TimedOut -> "等待回调超时"
                TapResult.Busy -> "手势正忙"
                is TapResult.Unavailable -> result.reason
                is TapResult.InvalidPoint -> result.reason
            }
            try {
                if (input == PlaybackInput.ROOT) {
                    touch = gameUid()?.let { uid ->
                        val volumeStop: (() -> Unit)? = if (app.preferences.volumeStop.value) ({
                            if (PlaybackPreparation.activeRunner === runner) PlaybackPreparation.stopPlayback()
                        }) else null
                        root.openTouch(uid, volumeStop)
                    }
                    if (touch == null && !backend.status().available) {
                        reject("Root 输入初始化失败，无障碍回退不可用")
                        outcome = PlaybackResult(0, fresh.events.size, false, "Root 输入初始化失败", 0, 0)
                        return@launch
                    }
                    if (touch == null) Log.w("DeltaRoot", "startup fallback to accessibility before touch")
                }
                Log.i("DeltaPlayback", "input backend=${if (touch != null) PlaybackInput.ROOT else PlaybackInput.ACCESSIBILITY} speed=${prepared.speed}")
                outcome = runner.run(fresh,
                    hide = {
                        removeMarkers()
                        if (panelAttached) { wm.removeViewImmediate(panelView); panelAttached = false }
                        val stop = overlayStyle().button("停止", primary = true) { PlaybackPreparation.stopPlayback() }.apply {
                            setOnClickListener { if (PlaybackPreparation.stopPlayback()) { text = "等待释放"; isEnabled = false } }
                        }
                        wm.addView(stop,params(dp(90),dp(48)).apply { x = (before.size.width-dp(90)).coerceAtLeast(0); y=0 })
                        stopView = stop
                    },
                    awaitWindows = {
                        nextFrame(); nextFrame()
                        val stop = stopView ?: error("停止窗口不可用")
                        val location = IntArray(2); stop.getLocationOnScreen(location)
                        val ids = fresh.events.flatMap { e -> listOf(e.event.noteKeyId) + (if(e.regionSwitch) listOf("REGION_${e.event.pitchRegion.name}") else emptyList()) + (if(e.semitoneSwitch) listOf("SEMITONE_TOGGLE") else emptyList()) }.toSet()
                        val overlaps = draft.targets.filter { it.id in ids }.any { target ->
                            val point = Coordinates.toScreen(target.position,before.size)
                            point.x >= location[0] && point.x < location[0]+stop.width && point.y >= location[1] && point.y < location[1]+stop.height
                        }
                        check(!overlaps) { "停止窗口覆盖目标，请调整校准位置" }
                    },
                    valid = ::environmentValid,
                    dispatch = { targetId, duration ->
                        val target = draft.targets.find { it.id == targetId }
                        if (target == null) TapResult.Unavailable("目标不存在") else {
                            val action = NoteAction(targetId,Coordinates.toScreen(target.position,before.size),NoteActionType.HOLD,duration,android.os.SystemClock.elapsedRealtime(),NoteActionState.DISPATCHING)
                            var dispatched: Long?=null; var callback: Long?=null
                            val index=currentSourceIndex
                            val connection = touch
                            val result = if (connection != null)
                                playRoot(connection, CombinedGesture.create(action.point, duration, emptyList()), index.takeIf { target.type == TargetType.NOTE }, duration)
                            else backend.perform(action,{ dispatched=it },{ callback=it })
                            if(target.type == TargetType.NOTE && index != null && dispatched != null)
                                PlaybackPreparation.actualTimings[index]=ActualTiming(dispatched!!-sessionStartMs,callback?.minus(sessionStartMs),0,duration,timingResult(result))
                            if (targetId.startsWith("REGION_")) runtimeState = runtimeState.regionResult(Region.valueOf(targetId.removePrefix("REGION_")),result)
                            if (targetId == "SEMITONE_TOGGLE") {
                                val next = runtimeState.semitoneEnabled?.not()
                                runtimeState = if (next == null) runtimeState.reset() else runtimeState.semitoneResult(next,result)
                            }
                            result
                        }
                    },
                    restore = {
                        if (!overlayRestored) {
                            var restoreFailure: Exception?=null
                            stopView?.let { view ->
                                try { wm.removeViewImmediate(view); stopView=null } catch(e: Exception) { restoreFailure=e }
                            }
                            if (!destroyed) try {
                                if (!panelAttached) { wm.addView(panelView,panelParams); panelAttached = true }
                                shown = originalShown; snapshot = displaySnapshot(this@CalibrationOverlayService); refreshMarkers()
                            } catch(e: Exception) {
                                if(restoreFailure == null) restoreFailure=e else restoreFailure!!.addSuppressed(e)
                            }
                            if(restoreFailure != null) throw restoreFailure!!
                            overlayRestored=true
                        }
                    }, onPhase = { phase ->
                        val description=when(phase) {
                            PlaybackPhase.WAITING_FOR_GESTURE -> "当前手势执行中"
                            PlaybackPhase.GESTURE_FINISHED -> "当前手势已结束"
                            PlaybackPhase.RESTORING -> "正在恢复悬浮层"
                            PlaybackPhase.RESTORED -> "悬浮层已恢复"
                            else -> null
                        }
                        if (description != null) PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state=description)
                    }, dryRunCompleted=true, warningsConfirmed=prepared.warningsConfirmed,
                    onSessionStarted = { sessionStartMs=it },
                    onEvent = { completed, key, finished ->
                        if (!finished) currentSourceIndex=fresh.events.getOrNull(completed)?.sourceIndex
                        val stopping=PlaybackPreparation.progress.value.phase == PlaybackStatus.STOPPING
                        PlaybackPreparation.progress.value = PlaybackProgress(fresh.events.size,completed,key,if(stopping) "停止已请求，当前手势已结束" else if(finished) "手势完成" else "准备音符",if(stopping) PlaybackStatus.STOPPING else PlaybackStatus.PLAYING)
                        (stopView as? Button)?.let { if (it.isEnabled) it.text = "停止 $completed/${fresh.events.size}" }
                    }, dispatchCombined = { item, duration ->
                        val ids=buildList {
                            if (item.regionSwitch) add("REGION_${item.event.pitchRegion.name}")
                            if (item.semitoneSwitch) add("SEMITONE_TOGGLE")
                        }
                        val controls=ids.mapNotNull { id -> draft.targets.find { it.id == id }?.let { Coordinates.toScreen(it.position,before.size) } }
                        val note=draft.targets.find { it.id == item.event.noteKeyId }
                        var dispatched: Long?=null; var callback: Long?=null
                        val result = if (note == null || controls.size != ids.size) TapResult.Unavailable("组合手势目标不存在") else try {
                            val gesture = CombinedGesture.create(Coordinates.toScreen(note.position,before.size),duration,controls,leadMs=fresh.combinedLeadMs)
                            val connection = touch
                            if (connection != null) playRoot(connection, gesture, item.sourceIndex, duration)
                            else backend.performCombined(gesture,{ dispatched=it },{ callback=it })
                        } catch (e: IllegalArgumentException) { TapResult.Unavailable("组合目标重合或时长无效") }
                        if(dispatched != null)
                            PlaybackPreparation.actualTimings[item.sourceIndex]=ActualTiming(dispatched!!-sessionStartMs,callback?.minus(sessionStartMs),if(controls.isEmpty()) 0 else fresh.combinedLeadMs,duration,timingResult(result))
                        if (item.regionSwitch) runtimeState=runtimeState.regionResult(item.event.pitchRegion,result)
                        if (item.semitoneSwitch) runtimeState=runtimeState.semitoneResult(item.event.semitoneEnabled,result)
                        result
                    })
            } catch (e: CancellationException) { runtimeState = runtimeState.reset() }
            catch (e: Exception) { runtimeState = runtimeState.reset(); if (!destroyed) Toast.makeText(this@CalibrationOverlayService,"播放或恢复失败：${e.localizedMessage}",Toast.LENGTH_LONG).show() }
            finally {
                val cleanup = touch?.let { root.closeTouch(it) }
                if (cleanup == RootCleanup.OBSERVED_IDLE) {
                    outcome = outcome?.copy(failure = "游戏触摸中断，Android 输入队列已清理")
                } else if (root.releaseUncertain) {
                    outcome = outcome?.copy(failure = "Root 触摸释放待确认")
                }
                playbackRunner = null
                focusJob?.cancel(); focusJob=null; focusPackage=null
                PlaybackPreparation.activeRunner = null
                Log.i("DeltaPlayback", "session result completed=${outcome?.completed} remaining=${outcome?.remaining} stopped=${outcome?.stopped} elapsedMs=${outcome?.elapsedMs} failure=${outcome?.failure}")
                PlaybackPreparation.progress.value = PlaybackPreparation.progress.value.copy(phase=if(outcome == null || outcome?.failure != null) PlaybackStatus.FAILED else PlaybackStatus.COMPLETED, state = when {
                    outcome == null -> "播放中断，检查游戏与悬浮层"
                    outcome?.failure != null -> "失败：${outcome?.failure}"
                    outcome?.stopped == true -> if (!destroyed && panelAttached) "已停止，悬浮层已恢复" else "已停止，请检查悬浮层"
                    else -> if (!destroyed && panelAttached) "已完成，悬浮层已恢复" else "已完成，请检查悬浮层"
                })
                if (!destroyed && panelAttached) {
                    val r = outcome
                    if (r?.failure != null) runtimeState = runtimeState.reset()
                    if (gamePanel == null) updateRuntimeInfo()
                    val measuredDelay=fresh.events.mapNotNull { item ->
                        PlaybackPreparation.actualTimings[item.sourceIndex]?.let { (it.dispatchMs+it.noteOffsetMs-item.requestedStartMs).coerceAtLeast(0) }
                    }.maxOrNull()
                    status.text = if (r == null) "播放未完成；请检查悬浮层和游戏状态" else
                        "系统完成 ${r.completed}，未执行 ${r.remaining}；音区 ${r.regionSwitches} 次，半音 ${r.semitoneSwitches} 次；会话 ${r.elapsedMs}ms；请求相对原曲最大偏差 ${measuredDelay?.let { "$it ms" } ?: "无记录"}，相对已拉伸计划最大额外延迟 ${r.maxLatenessMs} ms；${r.failure ?: if(r.stopped) "已停止" else "已结束"}；悬浮层已恢复。游戏响应需确认"
                }
            }
        }
        testJob?.start()
    }
    private fun save() {
        if (saving) return
        val current = snapshot ?: return
        val draft = layout ?: return
        if (!current.supported || !OverlayGeometry.compatible(draft, current.size) || displaySnapshot(this) != current) {
            status.text = "屏幕不匹配，请重新校准"; return
        }
        saving = true
        val saved = draft.copy(referenceDisplay = ReferenceDisplay(current.size.width, current.size.height), updatedAt = maxOf(System.currentTimeMillis(), draft.createdAt))
        scope.launch {
            try {
                (application as DeltaHarmonicaApp).layouts.save(saved)
                layout = saved; attachedLayout = saved; dirty = false; status.text = "已保存 · ${saved.targets.size} 个目标"
            } catch (e: Exception) { status.text = "保存失败：${e.localizedMessage}" }
            finally { saving = false }
        }
    }
    override fun onDestroy() {
        if (armJob?.isActive == true || testJob?.isActive == true) {
            Log.w("DeltaPlayback", "overlay service destroyed during session")
            PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state="校准服务已停止，请重新启动后检查游戏状态",phase=PlaybackStatus.FAILED)
        }
        destroyed = true; PlaybackPreparation.cancelPendingStart=null; playbackRunner?.requestStop(); focusJob?.cancel(); focusJob=null; focusPackage=null; stopView?.let { runCatching { wm.removeViewImmediate(it) } }; stopView=null; displayManager.unregisterDisplayListener(listener); scope.cancel(); runCatching { removeMarkers() }
        panel?.let { if (panelAttached) runCatching { wm.removeViewImmediate(it) } }; panelAttached = false; panel = null
        gamePanel?.dispose()
        super.onDestroy()
    }
}

