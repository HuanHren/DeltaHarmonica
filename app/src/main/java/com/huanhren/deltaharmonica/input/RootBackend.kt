package com.huanhren.deltaharmonica.input

import android.util.Log
import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.model.ScreenSize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class RootBackend internal constructor(
    private val runner: RootCommandRunner,
    private val apkPath: String?,
    private val ownUid: Int,
    internal val authorization: RootPermissionManager,
    private val screenSize: () -> ScreenSize,
) : InputBackend {
    constructor(
        runner: RootCommandRunner,
        apkPath: String? = null,
        ownUid: Int = 0,
        screenSize: () -> ScreenSize,
    ) : this(runner, apkPath, ownUid, RootPermissionManager(runner), screenSize)

    override val name = "Root"
    private val mutex = Mutex()
    @Volatile internal var releaseUncertain = false
        private set
    @Volatile internal var preferred = false
    // A read-only focus probe must not be blocked by an in-flight input command.
    private val focusMutex = Mutex()
    override fun status(): BackendStatus {
        val state = authorization.state.value
        return when {
            releaseUncertain -> BackendStatus(BackendAvailability.NOT_CONNECTED, "上次 Root 触摸释放待确认，请检查游戏后重启应用")
            state.checking -> BackendStatus(BackendAvailability.NOT_CONNECTED, "正在检查 Root 授权…")
            state.verified -> BackendStatus(BackendAvailability.AVAILABLE, "可用 · 已获得 Root 授权")
            else -> BackendStatus(BackendAvailability.NOT_ENABLED, state.failure ?: when (state.authorization) {
                RootAuthorizationState.NOT_ROOTED -> "未检测到可用的 Root 环境"
                RootAuthorizationState.PREVIOUSLY_AUTHORIZED -> "上次已授权，使用时验证"
                RootAuthorizationState.MANAGER_UNAVAILABLE -> "Root 管理器或 su 不可用"
                else -> "尚未检查 Root 授权"
            })
        }
    }

    internal suspend fun openTouch(targetUid: Int, onVolumeDown: (() -> Unit)? = null): RootGestureConnection? {
        val apk = apkPath ?: return null
        if (!status().available || !mutex.tryLock()) return null
        if (!inputDispatchMutex.tryLock()) {
            mutex.unlock()
            return null
        }
        var connection: RootGestureConnection? = null
        var handedOff = false
        try {
            connection = RootGestureConnection.open(apk, targetUid, onVolumeDown)
            Log.i("DeltaRoot", "touch helper ready=${connection != null} targetUid=$targetUid")
            if (connection == null) {
                authorization.updateInput(RootInputReadiness.UNAVAILABLE, "Root 触摸初始化失败，请重新检查授权")
            } else {
                authorization.updateInput(RootInputReadiness.READY)
                handedOff = true
            }
            return connection
        } finally {
            if (!handedOff) {
                val failed = connection
                if (failed == null) {
                    inputDispatchMutex.unlock()
                    mutex.unlock()
                } else {
                    closeTouch(failed)
                }
            }
        }
    }

    internal suspend fun closeTouch(connection: RootGestureConnection): RootCleanup = withContext(NonCancellable) {
        try {
            val outcome = confirmRootCleanup(connection.close()) {
                if (!authorization.state.value.verified) {
                    RootCommandResult.Failure("Root 授权失效，无法查询触摸释放状态")
                } else {
                    runner.run("dumpsys input | sed -n '/^  TouchStatesByDisplay:/,/^  CursorStatesByDisplay:/p'", 1_000).also { result ->
                        if (result is RootCommandResult.TimedOut ||
                            (result is RootCommandResult.Failure &&
                                (result.authorizationDenied || result.kind == RootCommandFailureKind.LAUNCH))
                        ) authorization.recordFailure(result)
                    }
                }
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
        if (!status().available || !focusMutex.tryLock()) return null
        return try {
            when (val result=runner.run("dumpsys window | grep 'mCurrentFocus='", 3000)) {
                is RootCommandResult.Success -> FocusedWindow.parse(result.output)
                is RootCommandResult.Failure -> {
                    if (result.authorizationDenied || result.kind == RootCommandFailureKind.LAUNCH) {
                        authorization.recordFailure(result)
                    }
                    null
                }
                RootCommandResult.TimedOut -> {
                    authorization.recordFailure(result)
                    null
                }
            }
        } finally { focusMutex.unlock() }
    }

    /** Explicit user action: may trigger the device's Superuser authorization UI. */
    suspend fun checkAccess(): BackendStatus = checkAccess(explicit = true)

    /** A requested Root operation can validate a remembered grant once. */
    internal suspend fun checkAccessForUse(): BackendStatus = checkAccess(explicit = false)

    private suspend fun checkAccess(explicit: Boolean): BackendStatus {
        if (releaseUncertain || !mutex.tryLock()) return status()
        try {
            if (explicit) authorization.request() else authorization.ensureForUse()
            return status()
        } finally {
            mutex.unlock()
        }
    }

    /** Enable only the requested app service while keeping other configured services intact. */
    suspend fun enableAccessibility(existing: String?, component: String, reconnect: Boolean,
                                    isSystemUser: Boolean): RootCommandResult {
        val status = status()
        if (!status.available) return RootCommandResult.Failure(status.message)
        if (!isSystemUser) return RootCommandResult.Failure("当前 Android 用户或工作资料夹暂不支持通过 Root 启用服务")
        if (!mutex.tryLock()) return RootCommandResult.Failure("Root 正忙，请稍后重试")
        try {
            val merged = mergeAccessibilityServices(existing, component)
            val reset = if (reconnect && containsAccessibilityService(existing, component))
                "settings --user 0 put secure enabled_accessibility_services ${shellQuote(withoutAccessibilityService(existing, component))} && "
                else ""
            val command = reset + "settings --user 0 put secure enabled_accessibility_services ${shellQuote(merged)} && " +
                "settings --user 0 put secure accessibility_enabled 1"
            val result = runner.run(command, 10_000)
            if (result is RootCommandResult.Failure && (result.authorizationDenied || result.kind == RootCommandFailureKind.LAUNCH)) {
                authorization.recordFailure(result)
            }
            return result
        } finally { mutex.unlock() }
    }

    override suspend fun tap(point: ScreenPoint): TapResult {
        val status = status()
        if (!status.available) return TapResult.Unavailable(status.message)
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
                is RootCommandResult.Success -> {
                    authorization.updateInput(RootInputReadiness.READY)
                    TapResult.Completed
                }
                is RootCommandResult.Failure -> {
                    authorization.recordFailure(result)
                    TapResult.Unavailable(result.reason)
                }
                RootCommandResult.TimedOut -> {
                    authorization.recordFailure(result)
                    TapResult.TimedOut
                }
            }
        } finally { mutex.unlock() }
    }
}
