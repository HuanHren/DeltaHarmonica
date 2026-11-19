package com.huanhren.deltaharmonica.input

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.os.Process
import android.os.UserHandle
import com.huanhren.deltaharmonica.accessibility.AccessibilityConnection
import com.huanhren.deltaharmonica.accessibility.TouchAccessibilityService
import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.playback.CombinedGesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccessibilityBackend(context: Context) : InputBackend {
    private val appContext = context.applicationContext
    override val name = "Accessibility"
    val componentName: String = ComponentName(appContext, TouchAccessibilityService::class.java).flattenToString()
    val isSystemUser: Boolean get() = Process.myUserHandle() == UserHandle.getUserHandleForUid(0)
    fun configuredServices(): String? = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    internal fun focusedPackage(): String? = AccessibilityConnection.service?.focusedPackage()

    override fun status(): BackendStatus {
        val enabled = containsAccessibilityService(configuredServices(), componentName)
        return when {
            !enabled -> BackendStatus(BackendAvailability.NOT_ENABLED, "未启用 · 请在系统无障碍设置中开启")
            !AccessibilityConnection.connected.value -> BackendStatus(BackendAvailability.NOT_CONNECTED, "已启用，等待系统连接服务")
            else -> BackendStatus(BackendAvailability.AVAILABLE, "可用 · 无障碍服务已连接")
        }
    }

    override suspend fun tap(point: ScreenPoint): TapResult = withContext(Dispatchers.Main.immediate) {
        val state = status()
        if (!state.available) return@withContext TapResult.Unavailable(state.message)
        AccessibilityConnection.service?.tap(point) ?: TapResult.Unavailable("服务已断开")
    }
    suspend fun perform(action: NoteAction, onDispatch: (Long) -> Unit = {}, onCallback: (Long) -> Unit = {},
                        expectedPackage: String? = null): TapResult = withContext(Dispatchers.Main.immediate) {
        val state = status()
        if (!state.available) return@withContext TapResult.Unavailable(state.message)
        AccessibilityConnection.service?.tap(action.point, action.durationMs, onDispatch, onCallback, expectedPackage) ?: TapResult.Unavailable("服务已断开")
    }
    suspend fun performCombined(action: CombinedGesture, onDispatch: (Long) -> Unit = {}, onCallback: (Long) -> Unit = {},
                                expectedPackage: String? = null): TapResult = withContext(Dispatchers.Main.immediate) {
        val state = status()
        if (!state.available) return@withContext TapResult.Unavailable(state.message)
        AccessibilityConnection.service?.dispatchCombined(action, onDispatch, onCallback, expectedPackage) ?: TapResult.Unavailable("服务已断开")
    }

}
