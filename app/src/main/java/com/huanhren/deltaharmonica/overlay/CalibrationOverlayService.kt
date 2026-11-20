package com.huanhren.deltaharmonica.overlay

import android.app.Service
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.content.Intent
import android.content.res.Configuration
import android.view.ViewConfiguration
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.combine
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
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.huanhren.deltaharmonica.DeltaHarmonicaApp
import com.huanhren.deltaharmonica.R
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
import com.huanhren.deltaharmonica.ui.StudioAppearance
import com.huanhren.deltaharmonica.ui.withStudioLocale
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
    private var gameMotion: ValueAnimator? = null
    private var gameDisplay: ScreenSize? = null
    private var gameAnchor = NormalizedPoint(1f, .24f)
    private var calibrationPanel: GameCalibrationPanel? = null
    private var calibrationAnchor: NormalizedPoint? = null
    private var calibrationStylePending = false
    private var calibrationNotice = ""
    private var calibrationResult = ""
    private var calibrationBackendAvailable = false
    private var calibrationTesting = false
    private var activePanelDrag: OverlayDrag? = null
    private var gameSizingMode = -1
    private val musicPrefs by lazy { MusicCenterPreferences(this) }
    private var sessionMusicView: View? = null
    private var closeAfterPlayback = false
    private var gameTargetWidth = 0
    private var gameTargetHeight = 0
    private var gamePanelJob: Job? = null
    private var openAsPlayer = false
    private var attachedLayout: InstrumentLayout? = null

    private fun overlayStyle(appearance: StudioAppearance = app.preferences.appearance.value) =
        OverlayStyle(withStudioLocale(), appearance)

    private fun overlayText(id: Int) = withStudioLocale().getString(id)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private lateinit var displayManager: DisplayManager
    private var panel: View? = null
    private var loadingPanel = false
    private var startAfterLoad = false
    private data class Marker(val view: TextView, val params: WindowManager.LayoutParams)
    private val markers = mutableMapOf<String, Marker>()
    private var markersNeedOrdering = false
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
    private var holdDuration = 500L
    private var currentAction: NoteAction? = null
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
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkDisplay()
        gamePanel?.updateStyle(overlayStyle())
        if (activePanelDrag != null && calibrationPanel != null) calibrationStylePending = true
        else calibrationPanel?.updateStyle(overlayStyle())
        if (!calibrationTesting) refreshMarkers()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val starting = intent?.action == ACTION_START_PLAYBACK
        if (starting && PlaybackPreparation.pending == null && PlaybackPreparation.progress.value.stopped) {
            if (panel == null && !loadingPanel) stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) { PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state="悬浮窗权限不可用，请在系统设置中开启",phase=PlaybackStatus.FAILED); stopSelf(); return START_NOT_STICKY }
        val requestedPlayer = starting || intent?.action == ACTION_SHOW_PLAYER
        if (panel != null && (saving || testJob?.isActive == true || armJob?.isActive == true)) return START_NOT_STICKY
        if (dirty && requestedPlayer) {
            setCalibrationNotice(R.string.game_cal_save_before_player)
            if (starting) rejectPendingStart(calibrationNotice)
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
                } else {
                    gamePanel?.refresh()
                    updateCalibrationPanel()
                }
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
        if (PlaybackPreparation.pending == null && PlaybackPreparation.progress.value.stopped) return
        PlaybackPreparation.pending = null
        PlaybackPreparation.progress.value = PlaybackPreparation.progress.value.copy(state = message, phase = PlaybackStatus.FAILED)
    }

    private fun detachPanel() {
        gamePanelJob?.cancel()
        gamePanelJob = null
        cancelGameMotion()
        gameDisplay = null
        activePanelDrag = null
        gamePanel?.dispose()
        gamePanel = null
        calibrationPanel = null
        calibrationAnchor = null
        calibrationNotice = ""
        calibrationResult = ""
        calibrationStylePending = false
        removeMarkers()
        panel?.let { if (panelAttached) wm.removeViewImmediate(it) }
        panelAttached = false
        panel = null
    }

    private fun showGamePanel() {
        val model = app.model
        var appearance = app.preferences.appearance.value
        val style = overlayStyle(appearance)
        val size = displaySnapshot(this).size
        val player = GamePlayerPanel(style, model, { attachedLayout }, { resizeGamePanel() },
            ::attachPlayerDrag, onClose = {
                closeAfterPlayback = true
                if (!PlaybackPreparation.stopPlayback()) stopSelf()
            }, onArm = ::armPlayback, scope = scope, musicPrefs = musicPrefs,
            onKeyboard = ::setMusicKeyboard, displayHeight = size.height, renderBlocked = { activePanelDrag != null })
        gamePanel = player
        panel = player.view
        gameDisplay = size
        gameAnchor = readPlayerAnchor(size, player)
        gameSizingMode = player.sizingMode
        gameTargetWidth = player.collapsedWidth
        gameTargetHeight = player.collapsedHeight
        val origin = playerOrigin(size, player, player.collapsedWidth, player.collapsedHeight)
        panelParams = params(player.collapsedWidth, player.collapsedHeight).apply {
            x = origin.x.roundToInt()
            y = origin.y.roundToInt()
        }
        wm.addView(player.view, panelParams)
        panelAttached = true
        gamePanelJob = scope.launch {
            launch { PlaybackPreparation.progress.collect(player::applyProgress) }
            launch {
                app.preferences.appearance.collect { next ->
                    if (next != appearance) {
                        appearance = next
                        player.updateStyle(overlayStyle(next))
                    }
                }
            }
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

    private fun anchorKey(name: String, size: ScreenSize) = "anchor_" + name + "_" + size.orientation.name

    private fun readAnchor(key: String): NormalizedPoint? {
        val x = overlayPrefs.getFloat(key + "_x", Float.NaN)
        val y = overlayPrefs.getFloat(key + "_y", Float.NaN)
        return if (x in 0f..1f && y in 0f..1f) NormalizedPoint(x, y) else null
    }

    private fun writeAnchor(key: String, value: NormalizedPoint) {
        overlayPrefs.edit().putFloat(key + "_x", value.x).putFloat(key + "_y", value.y).apply()
    }

    private fun readPlayerAnchor(size: ScreenSize, game: GamePlayerPanel): NormalizedPoint {
        val key = anchorKey("player", size)
        readAnchor(key)?.let { return it }
        val old = size.orientation.name.lowercase(java.util.Locale.ROOT)
        if (!overlayPrefs.contains("y_$old") && !overlayPrefs.contains("y")) return gameAnchor
        val x = overlayPrefs.getFloat("x_$old", overlayPrefs.getFloat("x", 1f))
        val y = overlayPrefs.getFloat("y_$old", overlayPrefs.getFloat("y", .24f))
        val migrated = playerCapsule(size, game).legacyAnchor(x, y)
        writeAnchor(key, migrated)
        return migrated
    }

    private fun playerCapsule(size: ScreenSize, game: GamePlayerPanel) =
        OverlayFootprint(size, game.collapsedWidth, game.collapsedHeight, game.screenMargin)

    private fun playerOrigin(size: ScreenSize, game: GamePlayerPanel, width: Int, height: Int): ScreenPoint =
        OverlayFootprint(size, width, height, game.screenMargin).originOf(gameAnchor, playerCapsule(size, game))

    private fun cancelGameMotion() {
        gameMotion?.cancel()
        gameMotion = null
    }

    private fun updatePanelWindow(lp: WindowManager.LayoutParams, view: View, width: Int, height: Int, origin: ScreenPoint) {
        val x = origin.x.roundToInt()
        val y = origin.y.roundToInt()
        if (!panelAttached || (lp.width == width && lp.height == height && lp.x == x && lp.y == y)) return
        lp.width = width
        lp.height = height
        lp.x = x
        lp.y = y
        wm.updateViewLayout(view, lp)
    }

    private fun animateGameWindow(width: Int, height: Int, target: ScreenPoint, animate: Boolean) {
        val game = gamePanel ?: return
        val view = game.view
        val margin = game.screenMargin
        val lp = panelParams ?: return
        val size = gameDisplay ?: return
        cancelGameMotion()
        if (!animate || app.preferences.appearance.value.reducedEffects || !ValueAnimator.areAnimatorsEnabled()) {
            updatePanelWindow(lp, view, width, height, target)
            return
        }
        val startX = lp.x
        val startY = lp.y
        val startWidth = lp.width
        val startHeight = lp.height
        gameMotion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230L
            interpolator = DecelerateInterpolator(1.65f)
            addUpdateListener { animation ->
                val f = animation.animatedValue as Float
                val w = (startWidth + (width - startWidth) * f).roundToInt()
                val h = (startHeight + (height - startHeight) * f).roundToInt()
                val position = ScreenPoint(startX + (target.x - startX) * f, startY + (target.y - startY) * f)
                updatePanelWindow(lp, view, w, h, OverlayFootprint(size, w, h, margin).clamp(position))
            }
            start()
        }
    }

    private fun resizeGamePanel(animateReposition: Boolean = false) {
        val game = gamePanel ?: return
        val lp = panelParams ?: return
        if (!panelAttached || activePanelDrag != null) return
        val size = gameDisplay ?: return
        val width = if (game.minimized) minOf(game.collapsedWidth, size.width) else
            minOf(game.preferredWidth, (size.width - 2 * game.screenMargin).coerceAtLeast(1))
        val height = if (game.minimized) minOf(game.collapsedHeight, size.height) else {
            game.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size.height, View.MeasureSpec.AT_MOST))
            game.view.measuredHeight.coerceIn(1, size.height)
        }
        val changeMode = gameSizingMode != game.sizingMode
        gameSizingMode = game.sizingMode
        if (!changeMode && gameMotion?.isRunning == true && gameTargetWidth == width && gameTargetHeight == height) return
        gameTargetWidth = width
        gameTargetHeight = height
        val target = playerOrigin(size, game, width, height)
        val atTarget = lp.width == width && lp.height == height && lp.x == target.x.roundToInt() && lp.y == target.y.roundToInt()
        if (!changeMode && atTarget) return
        animateGameWindow(width, height, target, changeMode || animateReposition)
    }

    private fun attachPlayerDrag(view: View, onClick: (() -> Unit)?) {
        attachPanelDrag(view, onClick, beforeDrag = ::cancelGameMotion, margin = { gamePanel?.screenMargin ?: 0 }) { release ->
            val game = gamePanel ?: return@attachPanelDrag
            val size = gameDisplay ?: return@attachPanelDrag
            val lp = panelParams ?: return@attachPanelDrag
            if (release != null) {
                val footprint = OverlayFootprint(size, lp.width, lp.height, game.screenMargin)
                gameAnchor = playerCapsule(size, game).anchorAt(release, footprint, gameAnchor.x)
                writeAnchor(anchorKey("player", size), gameAnchor)
            }
            game.flushPendingRender()
            resizeGamePanel(animateReposition = release != null)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachPanelDrag(
        handle: View,
        onClick: (() -> Unit)?,
        beforeDrag: () -> Unit = {},
        margin: () -> Int,
        finish: (ScreenPoint?) -> Unit,
    ) {
        val threshold = ViewConfiguration.get(this).scaledTouchSlop
        if (onClick != null) handle.setOnClickListener { onClick() }
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var gesture: OverlayDrag? = null
            private var footprint: OverlayFootprint? = null
            private var pointerId = MotionEvent.INVALID_POINTER_ID

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val lp = panelParams ?: return false
                val panelView = panel ?: return false
                val display = snapshot ?: return false
                if (!panelAttached) return false
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    beforeDrag()
                    val start = OverlayDrag(ScreenPoint(event.rawX, event.rawY), ScreenPoint(lp.x.toFloat(), lp.y.toFloat()), threshold)
                    gesture = start
                    activePanelDrag = start
                    footprint = OverlayFootprint(display.size, lp.width, lp.height, margin())
                    pointerId = event.getPointerId(0)
                    return true
                }
                val active = gesture ?: return true
                if (activePanelDrag !== active) return true
                val bounds = footprint ?: return true
                val release = event.actionMasked == MotionEvent.ACTION_UP
                val stablePointer = event.pointerCount == 1 && event.getPointerId(0) == pointerId && display.size == bounds.display
                if (stablePointer && (event.actionMasked == MotionEvent.ACTION_MOVE || release)) {
                    val origin = active.position(ScreenPoint(event.rawX, event.rawY), bounds)
                    if (origin != null) updatePanelWindow(lp, panelView, bounds.width, bounds.height, origin)
                    if (!release) return true
                    activePanelDrag = null
                    gesture = null
                    if (origin == null && onClick != null) view.performClick()
                    finish(origin)
                } else if (!stablePointer || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    activePanelDrag = null
                    gesture = null
                    finish(null)
                }
                return true
            }
        })
    }

    private fun setMusicKeyboard(focusable: Boolean) {
        val view = panel ?: return
        val params = panelParams ?: return
        if (!panelAttached || gamePanel == null || (focusable && playbackRunner != null)) return
        val mask = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.flags = if (focusable) (params.flags and mask.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            else params.flags or mask
        params.softInputMode = if (focusable) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        wm.updateViewLayout(view, params)
    }

    private fun removeSessionMusic() {
        val window = sessionMusicView ?: return
        sessionMusicView = null
        gamePanel?.music()?.hide()
        runCatching { wm.removeViewImmediate(window) }
    }

    private fun armPlayback() {
        gamePanel?.prepareForPlayback()
        if (armJob?.isActive == true || testJob?.isActive == true) { PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state="已有播放请求正在执行，请等待或停止"); return }
        val prepared=PlaybackPreparation.pending
        if (prepared == null && PlaybackPreparation.progress.value.stopped) return
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
                    root.checkAccessForUse().available
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
                if (!found) { Log.w("DeltaPlayback", "game focus confirmation timed out"); PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state=overlayText(R.string.overlay_window_timeout),phase=PlaybackStatus.FAILED) }
                else { Log.i("DeltaPlayback", "game window confirmed"); nextFrame(); nextFrame(); startPlayback(input) }
            } catch (_: CancellationException) { PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state=if(destroyed) "校准服务已停止，请重新启动" else overlayText(R.string.overlay_wait_cancelled),phase=if(destroyed) PlaybackStatus.FAILED else PlaybackStatus.COMPLETED, stopped = !destroyed) }
            catch (e: Exception) { rejectPendingStart("准备演奏失败：${e.localizedMessage}") }
            finally {
                PlaybackPreparation.cancelPendingStart = null
                if (testJob?.isActive != true) {
                    if (PlaybackPreparation.pending === prepared) PlaybackPreparation.pending = null
                    focusJob?.cancel()
                    focusJob = null
                    focusPackage = null
                    if (closeAfterPlayback) stopSelf()
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
        val current = displaySnapshot(this)
        snapshot = current
        val style = overlayStyle()
        val editor = GameCalibrationPanel(
            style,
            onResize = ::resizeCalibrationPanel,
            drag = ::attachCalibrationDrag,
            actions = GameCalibrationPanel.Actions(
                select = ::selectTarget,
                nudge = ::nudgeTarget,
                save = ::save,
                recalibrate = ::recalibrate,
                toggleMarkers = {
                    if (!saving && !calibrationTesting) {
                        shown = !shown
                        refreshMarkers()
                    }
                },
                confirmCoordinates = ::confirmCoordinates,
                testTap = { testClick() },
                testNote = { hold -> if (hold) testClick(holdDuration, NoteActionType.HOLD) else testClick(150L, NoteActionType.TAP) },
                adjustDuration = {
                    holdDuration = (holdDuration + it).coerceIn(250L, 2000L)
                    updateCalibrationPanel()
                },
                sendRegion = ::requestRegion,
                syncRegion = ::confirmRegion,
                sendSemitone = ::requestSemitone,
                syncSemitone = ::confirmSemitone,
                resetState = {
                    if (!calibrationTesting) {
                        runtimeState = runtimeState.reset()
                        setCalibrationNotice(R.string.game_cal_state_reset)
                    }
                },
                close = {
                    if (!saving && !calibrationTesting) {
                        if (dirty) Toast.makeText(this, overlayText(R.string.game_cal_discarded), Toast.LENGTH_SHORT).show()
                        stopSelf()
                    }
                },
            ),
        )
        calibrationPanel = editor
        panel = editor.view
        selected = selected?.takeIf { id -> layout?.targets.orEmpty().any { it.id == id } }
            ?: layout?.targets?.firstOrNull()?.id
        controlsCalibrated = PlaybackPreparation.controlsConfirmed
        calibrationAnchor = readAnchor(anchorKey("calibration", current.size)) ?: NormalizedPoint(1f, .16f)
        val width = minOf(editor.collapsedWidth, current.size.width)
        val height = minOf(editor.collapsedHeight, current.size.height)
        val origin = calibrationOrigin(current.size, editor, width, height)
        val lp = params(width, height).apply { x = origin.x.roundToInt(); y = origin.y.roundToInt() }
        panelParams = lp
        updateServiceStatus()
        refreshMarkers()
        wm.addView(editor.view, lp)
        panelAttached = true
        markersNeedOrdering = false
    }

    private fun calibrationCapsule(display: ScreenSize, editor: GameCalibrationPanel) =
        OverlayFootprint(display, editor.collapsedWidth, editor.collapsedHeight, editor.screenMargin)

    private fun calibrationOrigin(display: ScreenSize, editor: GameCalibrationPanel, width: Int, height: Int): ScreenPoint =
        OverlayFootprint(display, width, height, editor.screenMargin)
            .originOf(calibrationAnchor ?: NormalizedPoint(1f, .16f), calibrationCapsule(display, editor))

    private fun resizeCalibrationPanel() {
        val editor = calibrationPanel ?: return
        val size = snapshot?.size ?: return
        val lp = panelParams ?: return
        if (!panelAttached || activePanelDrag != null) return
        val availableWidth = (size.width - 2 * editor.screenMargin).coerceAtLeast(1)
        val availableHeight = (size.height - 2 * editor.screenMargin).coerceAtLeast(1)
        val width = minOf(availableWidth, if (editor.minimized) editor.collapsedWidth else editor.preferredWidth)
        val collapsedHeight = minOf(availableHeight, editor.collapsedHeight)
        val height = if (editor.minimized) collapsedHeight else
            minOf(editor.maxHeight, (availableHeight * .86f).roundToInt().coerceAtLeast(collapsedHeight))
        updatePanelWindow(lp, editor.view, width, height, calibrationOrigin(size, editor, width, height))
    }

    private fun attachCalibrationDrag(view: View, onClick: (() -> Unit)?) {
        attachPanelDrag(view, onClick, margin = { calibrationPanel?.screenMargin ?: 0 }) { release ->
            val editor = calibrationPanel ?: return@attachPanelDrag
            val size = snapshot?.size ?: return@attachPanelDrag
            val lp = panelParams ?: return@attachPanelDrag
            if (release != null) {
                val footprint = OverlayFootprint(size, lp.width, lp.height, editor.screenMargin)
                val anchor = calibrationCapsule(size, editor)
                    .freeAnchorAt(release, footprint, calibrationAnchor ?: NormalizedPoint(1f, .16f))
                calibrationAnchor = anchor
                writeAnchor(anchorKey("calibration", size), anchor)
            }
            if (calibrationStylePending) {
                calibrationStylePending = false
                editor.updateStyle(overlayStyle())
            }
            resizeCalibrationPanel()
            orderMarkersBelowPanel()
        }
    }

    private fun acceptCalibrationDisplay(next: DisplaySnapshot) {
        val before = snapshot
        val changed = before != null && before != next
        snapshot = next
        if (changed) {
            if (dirty) {
                shown = false
                calibrationNotice = overlayText(R.string.game_cal_display_changed)
            } else if (layout?.let { OverlayGeometry.compatible(it, next.size) } != true) {
                calibrationNotice = overlayText(R.string.game_cal_display_mismatch)
            } else {
                calibrationNotice = ""
            }
            if (before?.size?.orientation != next.size.orientation) {
                calibrationAnchor = readAnchor(anchorKey("calibration", next.size)) ?: calibrationAnchor
            }
        }
        resizeCalibrationPanel()
        refreshMarkers()
    }

    private fun checkDisplay() {
        if (panel == null) return
        val next = displaySnapshot(this)
        if (testJob?.isActive == true) {
            if (snapshot != next) {
                if (playbackRunner != null) playbackRunner?.requestStop()
                else testJob?.cancel(CancellationException("屏幕环境已改变"))
            }
            return
        }
        val displayChanged = snapshot != next
        if (displayChanged) activePanelDrag = null
        gamePanel?.let { game ->
            snapshot = next
            if (displayChanged || next.size != gameDisplay) {
                cancelGameMotion()
                if (next.size != gameDisplay) gameAnchor = readPlayerAnchor(next.size, game)
                gameDisplay = next.size
                game.markBounds(next.size.height)
                game.flushPendingRender()
            }
            return
        }
        if (displayChanged && calibrationStylePending) {
            calibrationStylePending = false
            calibrationPanel?.updateStyle(overlayStyle())
        }
        acceptCalibrationDisplay(next)
    }

    private fun recalibrate() {
        if (saving || calibrationTesting) return
        val current = snapshot ?: return
        val draft = layout ?: return
        if (!current.supported || current.size.orientation != draft.orientation) {
            setCalibrationNotice(R.string.game_cal_enter_game)
            return
        }
        layout = draft.copy(referenceDisplay = ReferenceDisplay(current.size.width, current.size.height))
        shown = true
        markDraft()
        refreshMarkers()
    }

    private fun markDraft() {
        dirty = true
        controlsCalibrated = false
        PlaybackPreparation.controlsConfirmed = false
        calibrationNotice = ""
        calibrationResult = ""
    }

    private fun selectTarget(id: String) {
        if (saving || calibrationTesting || selected == id) return
        val previous = selected
        selected = id
        previous?.let { markers[it]?.let { marker -> highlightMarker(marker.view, false) } }
        markers[id]?.let { highlightMarker(it.view, true) }
        calibrationResult = ""
        updateCalibrationPanel()
    }

    private fun nudgeTarget(dx: Int, dy: Int) {
        val current = snapshot ?: return
        val draft = layout ?: return
        if (saving || calibrationTesting || !current.supported || !OverlayGeometry.compatible(draft, current.size)
            || displaySnapshot(this) != current) return
        val target = draft.targets.firstOrNull { it.id == selected } ?: return
        moveTarget(target.id, OverlayGeometry.nudgeTarget(target.position, current.size, dx, dy), current.size)
    }

    private fun moveTarget(id: String, position: NormalizedPoint, size: ScreenSize) {
        val draft = layout ?: return
        if (draft.targets.none { it.id == id && it.position != position }) return
        layout = draft.copy(targets = draft.targets.map { if (it.id == id) it.copy(position = position) else it })
        markers[id]?.let { placeMarker(it, position, size) }
        markDraft()
        updateCalibrationPanel()
    }

    private fun placeMarker(marker: Marker, position: NormalizedPoint, size: ScreenSize) {
        val lp = marker.params
        val markerSize = dp(48)
        val origin = OverlayGeometry.windowOrigin(position, size, markerSize)
        val x = origin.x.roundToInt()
        val y = origin.y.roundToInt()
        if (lp.x == x && lp.y == y && lp.width == markerSize) return
        lp.x = x
        lp.y = y
        lp.width = markerSize
        lp.height = markerSize
        wm.updateViewLayout(marker.view, lp)
    }

    private fun highlightMarker(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (selected) Color.rgb(0, 105, 175) else Color.rgb(21, 47, 64))
            setStroke(dp(if (selected) 3 else 1), Color.WHITE)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun createMarker(id: String, position: NormalizedPoint, current: DisplaySnapshot): Marker {
        val view = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { selectTarget(id) }
        }
        val markerSize = dp(48)
        val origin = OverlayGeometry.windowOrigin(position, current.size, markerSize)
        val lp = params(markerSize, markerSize).apply { x = origin.x.roundToInt(); y = origin.y.roundToInt() }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startDisplay: DisplaySnapshot? = null
            private var downX = 0f
            private var downY = 0f
            private var startX = 0f
            private var startY = 0f
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val liveDisplay = snapshot ?: return true
                if (saving || calibrationTesting) {
                    startDisplay = null
                    return true
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startDisplay = liveDisplay
                        downX = event.rawX
                        downY = event.rawY
                        val target = layout?.targets?.firstOrNull { it.id == id } ?: return true
                        val start = OverlayGeometry.windowOrigin(target.position, liveDisplay.size, lp.width)
                        startX = start.x
                        startY = start.y
                        dragging = false
                        selectTarget(id)
                    }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        if (liveDisplay != startDisplay || event.pointerCount != 1) return true
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dx * dx + dy * dy >= touchSlop * touchSlop) dragging = true
                        if (dragging) {
                            val target = OverlayGeometry.pointFromOrigin(ScreenPoint(startX + dx, startY + dy), liveDisplay.size, lp.width)
                            moveTarget(id, target, liveDisplay.size)
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            if (!dragging) v.performClick()
                            startDisplay = null
                        }
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> startDisplay = null
                }
                return true
            }
        })
        wm.addView(view, lp)
        markersNeedOrdering = true
        return Marker(view, lp)
    }

    private fun refreshMarkers() {
        val editor = calibrationPanel ?: return
        val draft = layout ?: return
        val current = snapshot ?: return
        val targets = if (current.supported && OverlayGeometry.compatible(draft, current.size)) draft.targets else emptyList()
        val ids = targets.map { it.id }.toSet()
        val obsolete = markers.keys.filterNot(ids::contains)
        obsolete.forEach { id -> markers.remove(id)?.let { wm.removeViewImmediate(it.view) } }
        targets.forEach { target ->
            val marker = markers[target.id] ?: if (shown) {
                createMarker(target.id, target.position, current).also { markers[target.id] = it }
            } else return@forEach
            val label = editor.targetLabel(target)
            marker.view.text = label
            marker.view.contentDescription = withStudioLocale().getString(R.string.game_cal_target_marker, label)
            marker.view.visibility = if (shown) View.VISIBLE else View.GONE
            highlightMarker(marker.view, target.id == selected)
            placeMarker(marker, target.position, current.size)
        }
        orderMarkersBelowPanel()
        updateCalibrationPanel()
    }

    private fun orderMarkersBelowPanel() {
        val view = calibrationPanel?.view ?: return
        if (!panelAttached || !markersNeedOrdering || activePanelDrag != null) return
        wm.removeViewImmediate(view)
        panelAttached = false
        wm.addView(view, panelParams)
        panelAttached = true
        markersNeedOrdering = false
    }

    private fun removeMarkers() {
        while (markers.isNotEmpty()) {
            val (id, marker) = markers.entries.last()
            wm.removeViewImmediate(marker.view)
            markers.remove(id)
        }
        markersNeedOrdering = false
    }

    private fun updateServiceStatus() {
        calibrationBackendAvailable = if (app.rootBackend.preferred) app.rootBackend.status().available
            else app.accessibilityBackend.status().available
        updateCalibrationPanel()
    }

    private fun updateCalibrationPanel() {
        val editor = calibrationPanel ?: return
        val current = snapshot
        val draft = layout
        editor.update(GameCalibrationPanel.State(
            targets = draft?.targets.orEmpty(),
            selected = selected,
            display = current?.size,
            compatible = current?.supported == true && draft?.let { OverlayGeometry.compatible(it, current.size) } == true,
            draft = dirty,
            hasReference = draft?.referenceDisplay != null,
            shown = shown,
            coordinatesConfirmed = controlsCalibrated,
            busy = saving || calibrationTesting,
            duration = holdDuration,
            runtime = runtimeState,
            root = app.rootBackend.preferred,
            backendAvailable = calibrationBackendAvailable,
            notice = calibrationNotice,
            result = calibrationResult,
        ))
    }

    private fun setCalibrationNotice(id: Int, vararg args: Any) {
        calibrationNotice = withStudioLocale().getString(id, *args)
        updateCalibrationPanel()
    }

    private fun confirmCoordinates() {
        val current = snapshot ?: return
        val draft = layout ?: return
        if (saving || calibrationTesting || dirty || draft.referenceDisplay == null || !current.supported ||
            !OverlayGeometry.compatible(draft, current.size) || displaySnapshot(this) != current) {
            setCalibrationNotice(R.string.game_cal_coordinates_needed)
            return
        }
        controlsCalibrated = true
        PlaybackPreparation.controlsConfirmed = true
        setCalibrationNotice(R.string.game_cal_coordinates_ack)
    }
    private suspend fun nextFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }
    private fun confirmRegion(region: Region) {
        if (calibrationTesting) {
            setCalibrationNotice(R.string.game_cal_busy)
            return
        }
        runtimeState = runtimeState.confirmRegion(region)
        setCalibrationNotice(R.string.game_cal_region_synced)
    }

    private fun confirmSemitone(enabled: Boolean) {
        if (calibrationTesting) {
            setCalibrationNotice(R.string.game_cal_busy)
            return
        }
        runtimeState = runtimeState.confirmSemitone(enabled)
        setCalibrationNotice(R.string.game_cal_semitone_synced)
    }

    private fun requestRegion(region: Region) {
        if (calibrationTesting) {
            setCalibrationNotice(R.string.game_cal_busy)
            return
        }
        if (!controlsCalibrated) {
            setCalibrationNotice(R.string.game_cal_coordinates_needed)
            return
        }
        if (runtimeState.planRegion(region) == ControlDecision.SKIP) {
            setCalibrationNotice(R.string.game_cal_region_skip)
            return
        }
        testClick(targetOverride = "REGION_${region.name}") { result ->
            runtimeState = runtimeState.regionResult(region, result)
            setCalibrationNotice(if (result == TapResult.Completed) R.string.game_cal_region_sent else R.string.game_cal_control_failed)
        }
    }

    private fun requestSemitone(next: Boolean) {
        if (calibrationTesting) {
            setCalibrationNotice(R.string.game_cal_busy)
            return
        }
        when (runtimeState.planSemitone(next)) {
            ControlDecision.NEEDS_SYNC -> {
                setCalibrationNotice(R.string.game_cal_semitone_need_sync)
                return
            }
            ControlDecision.SKIP -> {
                setCalibrationNotice(R.string.game_cal_semitone_skip)
                return
            }
            ControlDecision.SEND -> Unit
        }
        if (!controlsCalibrated) {
            setCalibrationNotice(R.string.game_cal_coordinates_needed)
            return
        }
        testClick(targetOverride = "SEMITONE_TOGGLE") { result ->
            runtimeState = runtimeState.semitoneResult(next, result)
            setCalibrationNotice(if (result == TapResult.Completed) R.string.game_cal_semitone_sent else R.string.game_cal_control_failed)
        }
    }

    private fun testClick(durationMs: Long? = null, actionType: NoteActionType = NoteActionType.TAP,
        targetOverride: String? = null, onControlResult: ((TapResult) -> Unit)? = null) {
        if (saving || testJob?.isActive == true) {
            setCalibrationNotice(R.string.game_cal_busy)
            return
        }
        val draft = layout ?: return
        val before = snapshot ?: return
        val targetId = targetOverride ?: selected ?: run {
            setCalibrationNotice(R.string.game_cal_choose_target)
            return
        }
        if (dirty) {
            setCalibrationNotice(R.string.game_cal_save_before_test)
            return
        }
        val chosen = draft.targets.find { it.id == targetId }
        if (chosen == null) {
            setCalibrationNotice(R.string.game_cal_target_missing)
            return
        }
        if (durationMs != null && chosen.type != TargetType.NOTE) {
            setCalibrationNotice(R.string.game_cal_note_required)
            return
        }
        val app = application as DeltaHarmonicaApp
        val backend = app.accessibilityBackend
        val root = app.rootBackend
        updateServiceStatus()
        if (root.releaseUncertain) {
            setCalibrationNotice(R.string.game_cal_root_release)
            return
        }
        if (!root.preferred && !backend.status().available) {
            setCalibrationNotice(R.string.game_cal_input_needed)
            return
        }
        if (!before.supported || displaySnapshot(this) != before || draft.referenceDisplay == null || !OverlayGeometry.compatible(draft, before.size)) {
            setCalibrationNotice(R.string.game_cal_layout_required)
            return
        }
        val manualControl = onControlResult == null && durationMs == null && chosen.type != TargetType.NOTE
        if (manualControl) runtimeState = runtimeState.reset()
        val panelView = panel ?: return
        val originalShown = shown
        calibrationTesting = true
        calibrationResult = ""
        setCalibrationNotice(R.string.game_cal_busy)
        testJob = scope.launch(start = CoroutineStart.LAZY) {
            var result: TapResult? = null
            var touch: RootGestureConnection? = null
            try {
                if (root.preferred && root.checkAccessForUse().available) touch = gameUid()?.let { root.openTouch(it) }
                if (touch == null && !backend.status().available) {
                    result = TapResult.Unavailable("Root 输入不可用，无障碍回退未连接")
                    return@launch
                }
                result = OverlayTapCoordinator.execute(draft, targetId, before.size, true,
                    hide = {
                        testState = OverlayTestState.HIDING_OVERLAYS
                        removeMarkers()
                        cancelGameMotion()
                        activePanelDrag = null
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
                        ) else {
                            val action = NoteAction(targetId, point, actionType, durationMs ?: 60L,
                                android.os.SystemClock.elapsedRealtime(), NoteActionState.DISPATCHING)
                            currentAction = action
                            val outcome = backend.perform(action, expectedPackage = FocusedWindow.GAME_PACKAGE)
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
                            shown = originalShown
                            acceptCalibrationDisplay(displaySnapshot(this@CalibrationOverlayService))
                            if (!panelAttached) {
                                wm.addView(panelView, panelParams)
                                panelAttached = true
                                markersNeedOrdering = false
                            }
                            resizeCalibrationPanel()
                        }
                    })
            } catch (_: CancellationException) {
                result = TapResult.Cancelled
            } catch (e: Exception) {
                result = TapResult.Unavailable("恢复悬浮层失败：${e.localizedMessage}")
                Log.w("DeltaCalibration", "Test gesture or overlay restoration failed", e)
            } finally {
                touch?.let { root.closeTouch(it) }
                calibrationTesting = false
                if (!destroyed && panelAttached) {
                    val live = displaySnapshot(this@CalibrationOverlayService)
                    if (live != snapshot) acceptCalibrationDisplay(live)
                    testState = when (result) {
                        TapResult.Completed -> OverlayTestState.GESTURE_COMPLETED
                        TapResult.Cancelled -> OverlayTestState.GESTURE_CANCELLED
                        else -> OverlayTestState.FAILED
                    }
                    calibrationResult = overlayText(when (result) {
                        TapResult.Completed -> R.string.game_cal_test_completed
                        TapResult.Cancelled -> R.string.game_cal_test_cancelled
                        TapResult.TimedOut -> R.string.game_cal_test_timeout
                        TapResult.Rejected -> R.string.game_cal_test_rejected
                        TapResult.Busy -> R.string.game_cal_test_busy
                        else -> R.string.game_cal_test_incomplete
                    })
                    calibrationNotice = if (manualControl) overlayText(R.string.game_cal_manual_control) else ""
                    updateServiceStatus()
                    onControlResult?.invoke(result ?: TapResult.Unavailable("未完成"))
                }
            }
        }
        testJob?.start()
    }
    private suspend fun startPlayback(input: PlaybackInput) {
        fun reject(message: String) { Log.w("DeltaPlayback", "preflight rejected: $message"); PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state=message,phase=PlaybackStatus.FAILED) }
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
        val planningState = runtimeState
        val fresh = withContext(Dispatchers.Default) {
            runCatching {
                PlaybackPlanner.plan(prepared.script, draft, planningState.pitchRegion, planningState.semitoneEnabled,
                    prepared.start, prepared.count, prepared.speed, combined = prepared.plan.combined,
                    minimumTouchMs = prepared.plan.minimumTouchMs, combinedLeadMs = prepared.plan.combinedLeadMs,
                    gestureOverheadMs = prepared.plan.gestureOverheadMs,
                    checkActive = { ensureActive() })
            }.getOrNull()
        }
        if (PlaybackPreparation.pending !== prepared || !hasFreshGameFocus() ||
            displaySnapshot(this) != before || runtimeState != planningState) {
            reject("屏幕或游戏状态已变化，请重新预检")
            return
        }
        if (fresh == null || fresh.fatalErrors.isNotEmpty() || (fresh.warnings.isNotEmpty() && !prepared.warningsConfirmed) || fresh.warnings.any { it !in prepared.plan.warnings }) {
            reject("预检未通过，请检查目标、布局和警告"); return
        }
        val panelView = panel ?: return
        val originalShown = shown
        val runner = PlaybackRunner(); playbackRunner = runner; PlaybackPreparation.activeRunner = runner
        Log.i("DeltaPlayback", "session start count=${fresh.events.size} combined=${fresh.combined}")
        PlaybackPreparation.progress.value = PlaybackProgress(fresh.events.size, state = "正在隐藏悬浮层",
            phase = PlaybackStatus.CHECKING, durationMs = fresh.estimatedTotalMs)
        PlaybackPreparation.actualTimings.clear()
        PlaybackPreparation.pending = null
        testJob = scope.launch(start = CoroutineStart.LAZY) {
            var outcome: PlaybackResult? = null
            var sessionStartMs=0L
            var clockJob: Job? = null
            var sessionBar: GameSessionBar? = null
            var sessionParams: WindowManager.LayoutParams? = null
            var compactOrigin: ScreenPoint? = null
            var expandedOrigin: ScreenPoint? = null
            var accessibilityGestureActive = false
            var resumingControls = false
            var queuedTrack: String? = null
            var touch: RootGestureConnection? = null
            val screenTargets = OverlayGeometry.protectedTouchPoints(draft.targets.map { Coordinates.toScreen(it.position, before.size) })

            fun canInteractWithSessionMusic(): Boolean {
                if (resumingControls || accessibilityGestureActive) return false
                return when (PlaybackPreparation.progress.value.phase) {
                    PlaybackStatus.PAUSED -> true
                    PlaybackStatus.PLAYING -> touch != null && root.status().available
                    else -> false
                }
            }

            fun updateSessionBar() {
                sessionBar?.update(PlaybackPreparation.progress.value,
                    canExpand = expandedOrigin != null && !resumingControls && sessionMusicView == null,
                    canPause = !accessibilityGestureActive && !resumingControls,
                    canUseMusic = canInteractWithSessionMusic())
            }

            fun resizeSessionBar(expand: Boolean) {
                val bar = sessionBar ?: return
                val lp = sessionParams ?: return
                val origin = (if (expand) expandedOrigin else compactOrigin) ?: return
                lp.width = if (expand) bar.expandedWidth else bar.compactWidth
                lp.height = if (expand) bar.expandedHeight else bar.compactHeight
                lp.x = origin.x.toInt()
                lp.y = origin.y.toInt()
                bar.setExpanded(expand)
                wm.updateViewLayout(bar.view, lp)
                updateSessionBar()
            }

            fun toggleMusic() {
                if (sessionMusicView != null) {
                    removeSessionMusic()
                    updateSessionBar()
                    return
                }
                val bar = sessionParams ?: return
                val player = gamePanel ?: return
                if (!canInteractWithSessionMusic()) return
                if (sessionBar?.expanded == true) resizeSessionBar(false)
                val style = overlayStyle()
                val width = minOf(style.dp(396), before.size.width - style.dp(16))
                val height = minOf(style.dp(292), before.size.height - style.dp(16))
                val reserved = OverlayRect(bar.x, bar.y, bar.width, bar.height)
                val origin = musicWindowOrigin(before.size, width, height, screenTargets, reserved)
                if (origin == null) {
                    Toast.makeText(this@CalibrationOverlayService, overlayText(R.string.game_music_no_space), Toast.LENGTH_SHORT).show()
                    return
                }
                val center = player.music()
                (center.view.parent as? ViewGroup)?.removeView(center.view)
                val window = LinearLayout(style.context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = style.panelBackground()
                    setPadding(style.dp(9), style.dp(5), style.dp(9), style.dp(7))
                }
                val actions = LinearLayout(style.context)
                val volume = style.text(player.volumeLabel(), 12f)
                fun action(text: String, desc: Int, enabled: Boolean = true, invoke: () -> Unit) {
                    actions.addView(style.button(text, size = 14f, action = invoke).apply {
                        contentDescription = overlayText(desc)
                        isEnabled = enabled
                    }, LinearLayout.LayoutParams(0, style.touchSize, 1f))
                }
                fun queueTrack(step: Int) {
                    if (app.model.libraryBusy.value) return
                    queuedTrack = player.adjacentSong(step) ?: return
                    PlaybackPreparation.stopPlayback()
                    removeSessionMusic()
                    updateSessionBar()
                }
                action("‹", R.string.game_music_previous, player.adjacentSong(-1) != null) { queueTrack(-1) }
                action("›", R.string.game_music_next, player.adjacentSong(1) != null) { queueTrack(1) }
                action("−", R.string.game_music_volume_down) { player.changeVolume(-1); volume.text = player.volumeLabel() }
                action("+", R.string.game_music_volume_up) { player.changeVolume(1); volume.text = player.volumeLabel() }
                action("×", R.string.overlay_close) { removeSessionMusic(); updateSessionBar() }
                window.addView(actions)
                window.addView(volume)
                window.addView(center.view, LinearLayout.LayoutParams(-1, 0, 1f))
                center.show(duringPlayback = true)
                val lp = params(width, height).apply { x = origin.x.toInt(); y = origin.y.toInt() }
                try {
                    wm.addView(window, lp)
                    sessionMusicView = window
                } catch (failure: RuntimeException) {
                    center.hide()
                    (center.view.parent as? ViewGroup)?.removeView(center.view)
                    Log.w("DeltaPlayback", "could not open music center", failure)
                    return
                }
                window.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val actual = OverlayRect(location[0], location[1], view.width, view.height)
                    if (view === sessionMusicView && (actual.overlaps(reserved) || screenTargets.any(actual::contains))) {
                        removeSessionMusic()
                        updateSessionBar()
                    }
                }
                updateSessionBar()
            }

            suspend fun verifySessionWindow() {
                nextFrame()
                nextFrame()
                val controls = stopView ?: error("演奏控制窗口不可用")
                check(controls.width > 0 && controls.height > 0) { "演奏控制窗口不可用" }
                val location = IntArray(2)
                controls.getLocationOnScreen(location)
                check(screenTargets.none { point ->
                    point.x >= location[0] && point.x < location[0] + controls.width &&
                        point.y >= location[1] && point.y < location[1] + controls.height
                }) { "演奏控制窗口覆盖目标，请调整校准位置" }
            }
            var currentSourceIndex: Int? = null
            var overlayRestored=false
            fun environmentValid() = !destroyed && displaySnapshot(this@CalibrationOverlayService) == before &&
                (if (touch != null) root.status().available else backend.status().available) && Settings.canDrawOverlays(this@CalibrationOverlayService) && hasFreshGameFocus()
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
                val continuousSequence = if (touch != null && fresh.combined) {
                    RootSequencePlanner.build(fresh, draft.targets.associate {
                        it.id to Coordinates.toScreen(it.position, before.size)
                    })
                } else null
                val actualInput = if (touch != null) PlaybackInput.ROOT else PlaybackInput.ACCESSIBILITY
                PlaybackPreparation.activeInput = actualInput
                Log.i("DeltaPlayback", "input backend=$actualInput speed=${prepared.speed}")
                outcome = runner.run(fresh,
                    hide = {
                        removeMarkers()
                        cancelGameMotion()
                        activePanelDrag = null
                        if (panelAttached) { wm.removeViewImmediate(panelView); panelAttached = false }
                        fun controls(withMusic: Boolean) = GameSessionBar(overlayStyle(), app.model.midiWorkspace.name,
                            onTogglePause = {
                                if (!accessibilityGestureActive && !resumingControls && PlaybackPreparation.togglePause()) {
                                    updateSessionBar()
                                }
                            }, onStop = {
                                if (PlaybackPreparation.stopPlayback()) updateSessionBar()
                            }, onToggleDetails = {
                                val current = sessionBar
                                if (current != null && !resumingControls &&
                                    PlaybackPreparation.progress.value.phase == PlaybackStatus.PAUSED) {
                                    resizeSessionBar(!current.expanded)
                                }
                            }, onToggleMusic = if (withMusic) ::toggleMusic else null)
                        var bar = controls(withMusic = gamePanel != null)
                        var candidate = OverlayGeometry.controlBarOrigin(before.size, bar.compactWidth, bar.compactHeight, screenTargets)
                        if (candidate == null) {
                            bar = controls(withMusic = false)
                            candidate = OverlayGeometry.controlBarOrigin(before.size, bar.compactWidth, bar.compactHeight, screenTargets)
                        }
                        val origin = candidate ?: error("演奏控制窗口覆盖目标，请调整校准位置")
                        compactOrigin = origin
                        expandedOrigin = OverlayGeometry.controlBarOrigin(before.size, bar.expandedWidth, bar.expandedHeight, screenTargets)
                        val lp = params(bar.compactWidth, bar.compactHeight).apply { x = origin.x.toInt(); y = origin.y.toInt() }
                        wm.addView(bar.view, lp)
                        stopView = bar.view
                        sessionBar = bar
                        sessionParams = lp
                        updateSessionBar()
                    },
                    awaitWindows = ::verifySessionWindow,
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
                            else backend.perform(action, { dispatched = it }, { callback = it }, FocusedWindow.GAME_PACKAGE)
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
                            removeSessionMusic()
                            var restoreFailure: Exception?=null
                            stopView?.let { view ->
                                try { wm.removeViewImmediate(view); stopView=null } catch(e: Exception) { restoreFailure=e }
                            }
                            sessionBar = null
                            sessionParams = null
                            if (!destroyed && !closeAfterPlayback) try {
                                if (!panelAttached) { wm.addView(panelView,panelParams); panelAttached = true }
                                shown = originalShown
                                val restoredDisplay = displaySnapshot(this@CalibrationOverlayService)
                                snapshot = restoredDisplay
                                gamePanel?.let {
                                    if (gameDisplay != restoredDisplay.size) {
                                        gameDisplay = restoredDisplay.size
                                        gameAnchor = readPlayerAnchor(restoredDisplay.size, it)
                                    }
                                    it.markBounds(restoredDisplay.size.height)
                                }
                                refreshMarkers()
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
                        if (phase == PlaybackPhase.WAITING_FOR_GESTURE) accessibilityGestureActive = touch == null
                        if (phase == PlaybackPhase.GESTURE_FINISHED) accessibilityGestureActive = false
                        if (phase == PlaybackPhase.RESUMED) resumingControls = false
                        val current = PlaybackPreparation.progress.value
                        if (current.phase != PlaybackStatus.STOPPING) when (phase) {
                            PlaybackPhase.PAUSED -> PlaybackPreparation.progress.value =
                                current.copy(phase = PlaybackStatus.PAUSED, elapsedMs = runner.elapsedMs())
                            PlaybackPhase.RESUMED -> PlaybackPreparation.progress.value =
                                current.copy(phase = PlaybackStatus.PLAYING)
                            else -> if (description != null) {
                                PlaybackPreparation.progress.value = current.copy(state = description)
                            }
                        }
                        updateSessionBar()
                    }, dryRunCompleted=true, warningsConfirmed=prepared.warningsConfirmed,
                    onSessionStarted = {
                        sessionStartMs = it
                        clockJob = scope.launch {
                            while (isActive) {
                                val current = PlaybackPreparation.progress.value
                                if (current.phase in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSING)) {
                                    PlaybackPreparation.progress.value = current.copy(elapsedMs = runner.elapsedMs())
                                }
                                updateSessionBar()
                                delay(200)
                            }
                        }
                    },
                    onEvent = { completed, key, finished ->
                        if (!finished) currentSourceIndex=fresh.events.getOrNull(completed)?.sourceIndex
                        val current = PlaybackPreparation.progress.value
                        val phase = when (current.phase) {
                            PlaybackStatus.STOPPING, PlaybackStatus.PAUSING, PlaybackStatus.PAUSED -> current.phase
                            else -> PlaybackStatus.PLAYING
                        }
                        PlaybackPreparation.progress.value = current.copy(completed = completed, current = key,
                            elapsedMs = runner.elapsedMs(), phase = phase)
                        updateSessionBar()
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
                            else backend.performCombined(gesture, { dispatched = it }, { callback = it }, FocusedWindow.GAME_PACKAGE)
                        } catch (e: IllegalArgumentException) { TapResult.Unavailable("组合目标重合或时长无效") }
                        if(dispatched != null)
                            PlaybackPreparation.actualTimings[item.sourceIndex]=ActualTiming(dispatched!!-sessionStartMs,callback?.minus(sessionStartMs),if(controls.isEmpty()) 0 else fresh.combinedLeadMs,duration,timingResult(result))
                        if (item.regionSwitch) runtimeState=runtimeState.regionResult(item.event.pitchRegion,result)
                        if (item.semitoneSwitch) runtimeState=runtimeState.semitoneResult(item.event.semitoneEnabled,result)
                        result
                    }, onResuming = {
                        resumingControls = true
                        removeSessionMusic()
                        if (sessionBar?.expanded == true) resizeSessionBar(false) else updateSessionBar()
                        verifySessionWindow()
                    }, dispatchContinuous = continuousSequence?.let { sequence ->
                        { onNote: (Int, Long?, Long?) -> Unit,
                          isPaused: () -> Boolean,
                          onPaused: () -> Unit,
                          onResumed: () -> Unit ->
                            requireNotNull(touch).playSequence(
                                steps = sequence,
                                stop = { runner.stopRequested || !environmentValid() },
                                pause = isPaused,
                                onNote = { index, down, up ->
                                    val item = fresh.events[index]
                                    PlaybackPreparation.actualTimings[item.sourceIndex] = ActualTiming(
                                        down - sessionStartMs, up?.minus(sessionStartMs),
                                        if (item.regionSwitch || item.semitoneSwitch) fresh.combinedLeadMs else 0,
                                        item.predictedEndMs - item.predictedStartMs, "Root continuous OK",
                                    )
                                    if (item.regionSwitch)
                                        runtimeState = runtimeState.regionResult(item.event.pitchRegion, TapResult.Completed)
                                    if (item.semitoneSwitch)
                                        runtimeState = runtimeState.semitoneResult(item.event.semitoneEnabled, TapResult.Completed)
                                    onNote(index, down, up)
                                },
                                onPaused = onPaused,
                                onResumed = onResumed,
                            )
                        }
                    })
            } catch (e: CancellationException) { runtimeState = runtimeState.reset() }
            catch (e: Exception) { runtimeState = runtimeState.reset(); if (!destroyed) Toast.makeText(this@CalibrationOverlayService,"播放或恢复失败：${e.localizedMessage}",Toast.LENGTH_LONG).show() }
            finally {
                clockJob?.cancel()
                removeSessionMusic()
                val cleanup = touch?.let { root.closeTouch(it) }
                if (cleanup == RootCleanup.OBSERVED_IDLE) {
                    outcome = outcome?.copy(failure = "游戏触摸中断，Android 输入队列已清理")
                } else if (root.releaseUncertain) {
                    outcome = outcome?.copy(failure = "Root 触摸释放待确认")
                }
                playbackRunner = null
                focusJob?.cancel(); focusJob=null; focusPackage=null
                PlaybackPreparation.activeInput = null
                PlaybackPreparation.activeRunner = null
                Log.i("DeltaPlayback", "session result completed=${outcome?.completed} remaining=${outcome?.remaining} stopped=${outcome?.stopped} elapsedMs=${outcome?.elapsedMs} maxLatenessMs=${outcome?.maxLatenessMs} failure=${outcome?.failure}")
                PlaybackPreparation.progress.value = PlaybackPreparation.progress.value.copy(phase=if(outcome == null || outcome?.failure != null) PlaybackStatus.FAILED else PlaybackStatus.COMPLETED,
                    elapsedMs = outcome?.elapsedMs ?: runner.elapsedMs(), stopped = outcome?.stopped == true, state = when {
                    outcome == null -> overlayText(R.string.overlay_interrupted)
                    outcome?.failure != null -> "失败：${outcome?.failure}"
                    outcome?.stopped == true -> overlayText(
                        if (!destroyed && panelAttached) R.string.overlay_stopped_restored else R.string.overlay_stopped_check,
                    )
                    else -> overlayText(
                        if (!destroyed && panelAttached) R.string.overlay_completed_restored else R.string.overlay_completed_check,
                    )
                })
                if (closeAfterPlayback) stopSelf()
                else if (!destroyed && outcome?.stopped == true && outcome?.failure == null) {
                    queuedTrack?.let { gamePanel?.selectTrack(it, play = true) }
                }
            }
        }
        testJob?.start()
    }
    private fun save() {
        if (saving || calibrationTesting) return
        val current = snapshot ?: return
        val draft = layout ?: return
        if (!current.supported || !OverlayGeometry.compatible(draft, current.size) || displaySnapshot(this) != current) {
            setCalibrationNotice(R.string.game_cal_display_mismatch)
            return
        }
        saving = true
        setCalibrationNotice(R.string.game_cal_busy)
        val saved = draft.copy(referenceDisplay = ReferenceDisplay(current.size.width, current.size.height),
            updatedAt = maxOf(System.currentTimeMillis(), draft.createdAt))
        scope.launch {
            try {
                app.layouts.save(saved)
                layout = saved
                attachedLayout = saved
                dirty = false
                setCalibrationNotice(R.string.game_cal_saved, saved.targets.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DeltaCalibration", "Could not save game layout", e)
                setCalibrationNotice(R.string.game_cal_save_error)
            } finally {
                saving = false
                updateCalibrationPanel()
            }
        }
    }

    override fun onDestroy() {
        if (armJob?.isActive == true || testJob?.isActive == true) {
            Log.w("DeltaPlayback", "overlay service destroyed during session")
            PlaybackPreparation.progress.value=PlaybackPreparation.progress.value.copy(state="校准服务已停止，请重新启动后检查游戏状态",phase=PlaybackStatus.FAILED)
        }
        gamePanel?.releaseMusicKeyboard()
        removeSessionMusic()
        cancelGameMotion()
        destroyed = true; PlaybackPreparation.cancelPendingStart=null; playbackRunner?.requestStop(); focusJob?.cancel(); focusJob=null; focusPackage=null; stopView?.let { runCatching { wm.removeViewImmediate(it) } }; stopView=null; displayManager.unregisterDisplayListener(listener); scope.cancel(); runCatching { removeMarkers() }
        panel?.let { if (panelAttached) runCatching { wm.removeViewImmediate(it) } }; panelAttached = false; panel = null
        gamePanel?.dispose()
        super.onDestroy()
    }
}
