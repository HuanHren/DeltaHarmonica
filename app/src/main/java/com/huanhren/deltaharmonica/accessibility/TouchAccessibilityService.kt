package com.huanhren.deltaharmonica.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.KeyEvent
import com.huanhren.deltaharmonica.input.*
import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.playback.CombinedGesture
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.playback.PlaybackInput
import com.huanhren.deltaharmonica.DeltaHarmonicaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Process-local bridge. Never persists a service reference beyond its connected lifecycle. */
object AccessibilityConnection {
    internal var service: TouchAccessibilityService? = null
        private set
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()

    internal fun attach(value: TouchAccessibilityService) {
        service = value
        mutableConnected.value = true
    }

    internal fun detach(value: TouchAccessibilityService) {
        if (service === value) {
            service = null
            mutableConnected.value = false
        }
    }
}

class TouchAccessibilityService : AccessibilityService() {
    private val gestureGate = NoteActionGate()
    private val stopKey = PlaybackKeyPress()
    private val pauseKey = PlaybackKeyPress()

    override fun onServiceConnected() { AccessibilityConnection.attach(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    internal fun focusedPackage(): String? = windows.firstOrNull { it.isFocused }
        ?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        ?.root?.packageName?.toString()
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> stopKey
            KeyEvent.KEYCODE_VOLUME_UP -> pauseKey
            else -> return false
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> key.down(event.deviceId, event.downTime, repeated = event.repeatCount > 0) {
                val preferences = (application as DeltaHarmonicaApp).preferences
                if (key === stopKey) preferences.volumeStop.value && PlaybackPreparation.stopPlayback()
                else preferences.volumePause.value &&
                    PlaybackPreparation.activeInput == PlaybackInput.ACCESSIBILITY &&
                    focusedPackage() == FocusedWindow.GAME_PACKAGE &&
                    PlaybackPreparation.togglePause()
            }
            KeyEvent.ACTION_UP -> key.up(event.deviceId, event.downTime)
            else -> false
        }
    }
    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityConnection.detach(this)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        AccessibilityConnection.detach(this)
        super.onDestroy()
    }

    internal suspend fun tap(point: ScreenPoint, durationMs: Long = 60L, onDispatch: (Long) -> Unit = {}, onCallback: (Long) -> Unit = {},
                             expectedPackage: String? = null): TapResult =
        if (durationMs !in 1L..MAX_GESTURE_DURATION_MS) TapResult.Unavailable("时长必须为 1–${MAX_GESTURE_DURATION_MS} 毫秒")
        else dispatchCombined(CombinedGesture.create(point, durationMs, emptyList()), onDispatch, onCallback, expectedPackage)

    internal suspend fun dispatchCombined(combined: CombinedGesture, onDispatch: (Long) -> Unit = {}, onCallback: (Long) -> Unit = {},
                                          expectedPackage: String? = null): TapResult = withContext(Dispatchers.Main.immediate) {
        if (AccessibilityConnection.service !== this@TouchAccessibilityService) {
            return@withContext TapResult.Unavailable("服务已断开")
        }
        if (combined.strokes.maxOf { it.startMs+it.durationMs } > GestureDescription.getMaxGestureDuration())
            return@withContext TapResult.Unavailable("手势时长超出当前 Android 系统允许的范围")
        val display = displaySnapshot(this@TouchAccessibilityService)
        if (!display.supported || combined.strokes.any { it.point.x !in 0f..(display.size.width - 1f) || it.point.y !in 0f..(display.size.height - 1f) }) {
            return@withContext TapResult.InvalidPoint("坐标不在默认屏幕内")
        }
        gestureGate.run {
            withContext(NonCancellable) {
            withTimeoutOrNull(combined.strokes.maxOf { it.startMs+it.durationMs } + 2_000L) {
                suspendCancellableCoroutine<TapResult> { continuation ->
                    val completion=GestureCompletion()
                    continuation.invokeOnCancellation { completion.tryFinish() }
                    fun finish(result: TapResult, callback: Boolean = false) {
                        if (completion.tryFinish() && continuation.isActive) {
                            if (callback) runCatching { onCallback(System.nanoTime()/1_000_000) }
                            continuation.resume(result)
                        }
                    }
                    val builder = GestureDescription.Builder()
                    combined.strokes.forEach { stroke ->
                        val path = Path().apply { moveTo(stroke.point.x,stroke.point.y) }
                        builder.addStroke(GestureDescription.StrokeDescription(path,stroke.startMs,stroke.durationMs))
                    }
                    val gesture = builder.build()
                    val callback = object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            finish(TapResult.Completed,true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            finish(TapResult.Cancelled,true)
                        }
                    }
                    try {
                        if (expectedPackage != null && focusedPackage() != expectedPackage) {
                            finish(TapResult.Unavailable("游戏已失去前台焦点"))
                            return@suspendCancellableCoroutine
                        }
                        runCatching { onDispatch(System.nanoTime()/1_000_000) }
                        val accepted = dispatchGesture(gesture, callback, null)
                        if (!accepted) finish(TapResult.Rejected)
                    } catch (_: SecurityException) {
                        finish(TapResult.Unavailable("系统未授权手势能力"))
                    } catch (_: IllegalStateException) {
                        finish(TapResult.Unavailable("服务连接不可用"))
                    }
                }
            } ?: TapResult.TimedOut
            }
        }
    }
}
