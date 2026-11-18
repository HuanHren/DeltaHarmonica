package com.huanhren.deltaharmonica.input

import android.util.Log
import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.model.ScreenSize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class RootBackend(
    private val runner: RootCommandRunner,
    private val apkPath: String? = null,
    private val ownUid: Int = 0,
    private val screenSize: () -> ScreenSize,
) : InputBackend {
    override val name = "Root"
    @Volatile private var currentStatus = BackendStatus(BackendAvailability.NOT_ENABLED, "尚未检查 Root 授权")
    private val mutex = Mutex()
    @Volatile internal var releaseUncertain = false
        private set
    internal var preferred = true
    // A read-only focus probe must not be blocked by an in-flight input command.
    private val focusMutex = Mutex()
    override fun status(): BackendStatus = if (releaseUncertain) {
        BackendStatus(BackendAvailability.NOT_CONNECTED, "上次 Root 触摸释放待确认，请检查游戏后重启应用")
    } else currentStatus

    internal suspend fun openTouch(targetUid: Int, onVolumeDown: (() -> Unit)? = null): RootGestureConnection? {
        val apk = apkPath ?: return null
        if (!currentStatus.available || releaseUncertain || !mutex.tryLock()) return null
        if (!inputDispatchMutex.tryLock()) {
            mutex.unlock()
            return null
        }
        var connection: RootGestureConnection? = null
        try {
            connection = RootGestureConnection.open(apk, targetUid, onVolumeDown)
            Log.i("DeltaRoot", "touch helper ready=${connection != null} targetUid=$targetUid")
            return connection
        } finally {
            if (connection == null) {
                inputDispatchMutex.unlock()
                mutex.unlock()
            }
        }
    }

    internal suspend fun closeTouch(connection: RootGestureConnection): RootCleanup = withContext(NonCancellable) {
        try {
            val outcome = confirmRootCleanup(connection.close()) {
                runner.run("dumpsys input | sed -n '/^  TouchStatesByDisplay:/,/^  CursorStatesByDisplay:/p'", 1_000)
            }
            releaseUncertain = !outcome.confirmed
            if (releaseUncertain) Log.e("DeltaRoot", "helper did not confirm input cleanup")
            if (outcome == RootCleanup.OBSERVED_IDLE) Log.i("DeltaRoot", "input dispatcher idle after helper closed")
            outcome
        } finally {
            inputDispatchMutex.unlock()
            mutex.unlock()
        }
    }

    internal fun validPoint(point: ScreenPoint): Boolean {
        val size = screenSize()
        return point.x in 0f..(size.width - 1f) && point.y in 0f..(size.height - 1f)
    }

    /** Read-only focus query. Never synthesizes input or modifies system settings. */
    suspend fun focusedPackage(): String? {
        if (!currentStatus.available || !focusMutex.tryLock()) return null
        return try {
            when (val result=runner.run("dumpsys window | grep 'mCurrentFocus='", 3000)) {
                is RootCommandResult.Success -> FocusedWindow.parse(result.output)
                else -> null
            }
        } finally { focusMutex.unlock() }
    }

    /** Explicit user action: may trigger the device's Superuser authorization UI. */
    suspend fun checkAccess(): BackendStatus {
        if (!mutex.tryLock()) return currentStatus
        try {
            currentStatus = BackendStatus(BackendAvailability.NOT_CONNECTED, "正在检查 Root 授权…")
            currentStatus = when (val result = runner.run("id -u", 30_000)) {
                is RootCommandResult.Success -> if (result.output.trim() == "0")
                    BackendStatus(BackendAvailability.AVAILABLE, "可用 · 已获得 Root 授权")
                else BackendStatus(BackendAvailability.NOT_ENABLED, "su 未返回 Root UID；请检查授权")
                is RootCommandResult.Failure -> BackendStatus(BackendAvailability.NOT_ENABLED, result.reason)
                RootCommandResult.TimedOut -> BackendStatus(BackendAvailability.NOT_ENABLED, "Root 授权检查超时；请检查授权提示")
            }
            return currentStatus
        } finally {
            if (currentStatus.availability == BackendAvailability.NOT_CONNECTED)
                currentStatus = BackendStatus(BackendAvailability.NOT_ENABLED, "Root 授权检查已取消")
            mutex.unlock()
        }
    }

    /** Enable only the requested app service while keeping other configured services intact. */
    suspend fun enableAccessibility(existing: String?, component: String, reconnect: Boolean,
                                    isSystemUser: Boolean): RootCommandResult {
        if (!currentStatus.available) return RootCommandResult.Failure(currentStatus.message)
        if (!isSystemUser) return RootCommandResult.Failure("当前 Android 用户或工作资料夹暂不支持通过 Root 启用服务")
        if (!mutex.tryLock()) return RootCommandResult.Failure("Root 正忙，请稍后重试")
        try {
            val merged = mergeAccessibilityServices(existing, component)
            val reset = if (reconnect && containsAccessibilityService(existing, component))
                "settings --user 0 put secure enabled_accessibility_services ${shellQuote(withoutAccessibilityService(existing, component))} && "
                else ""
            val command = reset + "settings --user 0 put secure enabled_accessibility_services ${shellQuote(merged)} && " +
                "settings --user 0 put secure accessibility_enabled 1"
            return when (val result = runner.run(command, 10_000)) {
                is RootCommandResult.Failure -> {
                    currentStatus = BackendStatus(BackendAvailability.NOT_ENABLED, result.reason)
                    result
                }
                else -> result
            }
        } finally { mutex.unlock() }
    }

    override suspend fun tap(point: ScreenPoint): TapResult {
        if (!currentStatus.available) return TapResult.Unavailable(currentStatus.message)
        if (!validPoint(point))
            return TapResult.InvalidPoint("坐标不在默认屏幕内")
        if (apkPath != null) {
            val connection = openTouch(ownUid) ?: return TapResult.Unavailable("Root 触摸初始化失败或正在使用")
            return try {
                connection.play(com.huanhren.deltaharmonica.playback.CombinedGesture.create(point, 45, emptyList()))
            } finally { closeTouch(connection) }
        }
        if (!mutex.tryLock()) return TapResult.Busy
        try {
            return when (val result = runner.run("input tap ${point.x.roundToInt()} ${point.y.roundToInt()}", 5_000)) {
                is RootCommandResult.Success -> TapResult.Completed
                is RootCommandResult.Failure -> {
                    currentStatus = BackendStatus(BackendAvailability.NOT_ENABLED, result.reason)
                    TapResult.Unavailable(result.reason)
                }
                RootCommandResult.TimedOut -> {
                    currentStatus = BackendStatus(BackendAvailability.NOT_ENABLED, "Root 点击超时，请重新检查授权")
                    TapResult.TimedOut
                }
            }
        } finally { mutex.unlock() }
    }
}
