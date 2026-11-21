package com.huanhren.deltaharmonica.input

import android.util.Log
import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.model.ScreenSize
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

class RootBackend internal constructor(
    private val runner: RootCommandRunner,
    private val apkPath: String?,
    private val ownUid: Int,
    internal val authorization: RootPermissionManager,
    private val releaseGuard: RootReleaseJournal? = null,
    private val openConnection: suspend (String, Int, (() -> Unit)?, String) -> RootGestureConnection? =
        { apk, uid, volume, session -> RootGestureConnection.open(apk, uid, volume, session) },
    private val reportSession: (RootSessionState) -> Unit = { Log.i("DeltaRoot", it.diagnosticLine()) },
    private val waitForRecovery: suspend (Long) -> Unit = { delay(it) },
    private val screenSize: () -> ScreenSize,
) : InputBackend {
    constructor(
        runner: RootCommandRunner,
        apkPath: String? = null,
        ownUid: Int = 0,
        screenSize: () -> ScreenSize,
    ) : this(runner, apkPath, ownUid, RootPermissionManager(runner), screenSize = screenSize)

    override val name = "Root"
    private val mutex = Mutex()
    private val supervisor = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val supervisedSessions: Int get() = supervisor.coroutineContext[Job]?.children?.count() ?: 0
    private val recoveryProbe = RootRecoveryProbe(waitForRecovery) { command, timeout ->
        runner.run(command, timeout).also {
            if (it is RootCommandResult.Failure && (it.authorizationDenied || it.kind == RootCommandFailureKind.LAUNCH)) {
                authorization.recordFailure(it)
            }
        }
    }
    private var recoveryRecord = releaseGuard?.pending()
    private var automaticRetryRecord: RootReleaseRecord? = null
    private val mutableSessionState = MutableStateFlow(recoveryRecord?.let {
        RootSessionState(
            it.sessionId, RootSessionPhase.RECOVERY_REQUIRED, "LOCK_STALE", it.helper,
            dispatcherId = it.dispatcherId, lockState = RootPlaybackLockState.LOCK_STALE,
        )
    } ?: RootSessionState())
    internal val sessionState: StateFlow<RootSessionState> = mutableSessionState.asStateFlow()
    private val diagnostics = RootSessionDiagnostics().apply {
        if (recoveryRecord != null) transition(sessionState.value)
    }

    internal fun diagnosticReport(): String = diagnostics.export()
    internal fun recordDiagnostic(sessionId: String, summary: String, terminationReason: String? = null) {
        diagnostics.record(sessionId, summary)
        if (terminationReason != null) mutableSessionState.update {
            if (it.id == sessionId && it.terminationReason == null) it.copy(
                terminationReason = terminationReason,
                lastFailureReason = terminationReason.takeUnless(normalTerminations::contains) ?: it.lastFailureReason,
            ) else it
        }
    }
    internal val releaseUncertain: Boolean get() = sessionState.value.recoveryRequired
    @Volatile private var lease: RootLease? = null

    private class RootLease(val record: RootReleaseRecord, val connection: RootGestureConnection) {
        val closing = AtomicBoolean()
        val result get() = connection.ownerCleanup
    }

    @Volatile internal var preferred = false
    // A read-only focus probe must not be blocked by an in-flight input command.
    private val focusMutex = Mutex()
    override fun status(): BackendStatus {
        val state = authorization.state.value
        val session = sessionState.value
        return when {
            session.phase == RootSessionPhase.RECOVERING ->
                BackendStatus(BackendAvailability.NOT_CONNECTED, "正在恢复 Root 输入状态…")
            session.recoveryRequired ->
                BackendStatus(BackendAvailability.NOT_CONNECTED, "Root 触控释放待确认，可在设置中恢复 Root 输入状态")
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
        if (!mutex.tryLock()) return null
        if (!status().available || !inputDispatchMutex.tryLock()) {
            mutex.unlock()
            return null
        }
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        var connection: RootGestureConnection? = null
        var handedOff = false
        var startupDetail = "HELPER_START_FAILED"
        try {
            publish(RootSessionPhase.PREPARING, id, dispatcherStatus = "STARTING")
            val opened = openConnection(apk, targetUid, onVolumeDown, id) ?: return null
            connection = opened
            val record = RootReleaseRecord(id, opened.helperIdentity, createdAt, opened.dispatcherId)
            withContext(NonCancellable) {
                if (withContext(Dispatchers.IO) { releaseGuard?.begin(record) != false }) {
                    lease = RootLease(record, opened)
                } else {
                    startupDetail = "JOURNAL_FAILURE"
                }
            }
            val owner = lease ?: return null
            authorization.updateInput(RootInputReadiness.READY)
            currentCoroutineContext().ensureActive()
            publish(RootSessionPhase.ACTIVE, id, helper = record.helper, dispatcherId = record.dispatcherId,
                dispatcherStatus = opened.dispatcherStatus.name)
            supervise(owner)
            handedOff = true
            return opened
        } catch (cancelled: CancellationException) {
            startupDetail = "START_CANCELLED"
            throw cancelled
        } finally {
            if (!handedOff) {
                withContext(NonCancellable) {
                    val owner = lease
                    if (owner != null) {
                        closeTouch(owner.connection)
                    } else {
                        try {
                            connection?.close()
                        } finally {
                            try {
                                // A failed commit can still have changed SharedPreferences in memory.
                                recoveryRecord = withContext(Dispatchers.IO) { releaseGuard?.pending() }
                                val pending = recoveryRecord
                                if (pending == null) publish(RootSessionPhase.IDLE, id, startupDetail)
                                else publish(
                                    RootSessionPhase.RECOVERY_REQUIRED, pending.sessionId, startupDetail, pending.helper,
                                    dispatcherId = pending.dispatcherId, lockState = RootPlaybackLockState.LOCK_STALE,
                                )
                            } finally {
                                inputDispatchMutex.unlock()
                                mutex.unlock()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun supervise(owner: RootLease) {
        supervisor.launch {
            coroutineScope {
                val observation = launch {
                    owner.connection.dispatcherState.collect { phase ->
                        mutableSessionState.update {
                            if (lease === owner && it.id == owner.record.sessionId && it.phase == RootSessionPhase.ACTIVE) {
                                it.copy(dispatcherStatus = phase.name)
                            } else it
                        }
                    }
                }
                try {
                    select<Unit> {
                        owner.result.onAwait { }
                        owner.connection.dispatcherFault.onAwait { fault ->
                            closeTouch(owner.connection, fault.name)
                        }
                        owner.connection.terminalReceipt.onAwait {
                            closeTouch(owner.connection)
                        }
                    }
                } finally {
                    observation.cancelAndJoin()
                }
            }
        }
    }

    internal suspend fun closeTouch(connection: RootGestureConnection): RootCleanup = closeTouch(connection, null)

    private suspend fun closeTouch(connection: RootGestureConnection, fault: String?): RootCleanup = withContext(NonCancellable) {
        val owner = lease?.takeIf { it.connection === connection }
            ?: return@withContext if (connection.ownerCleanup.isCompleted) connection.ownerCleanup.await() else RootCleanup.NO_ACK
        if (!owner.closing.compareAndSet(false, true)) return@withContext owner.result.await()
        val record = owner.record
        var outcome = RootCleanup.NO_ACK
        var detail = "CLEANUP_FAILED"
        var attempts = 0
        var recovery: RootRecoveryResult? = null
        var termination = fault
        try {
            publish(RootSessionPhase.CLEANING, record.sessionId, helper = record.helper,
                dispatcherStatus = connection.dispatcherStatus.name, termination = termination)
            outcome = connection.close()
            if (termination == null && connection.dispatcherFault.isCompleted && !connection.dispatcherFault.isCancelled) {
                termination = connection.dispatcherFault.await().name
            }
            detail = outcome.name
            if (!outcome.confirmed && authorization.state.value.verified) {
                attempts++
                val evidence = withTimeoutOrNull(RECOVERY_DEADLINE_MS) { recoveryProbe.verify(record) }
                    ?: RootRecoveryResult.DEADLINE_EXCEEDED
                recovery = evidence
                detail = "${outcome.name} / ${evidence.name}"
                if (evidence == RootRecoveryResult.CONFIRMED) outcome = RootCleanup.OBSERVED_IDLE
            }
            if (outcome.confirmed && !clearJournal(record)) {
                outcome = RootCleanup.JOURNAL_FAILURE
                detail = outcome.name
                recovery = RootRecoveryResult.JOURNAL_FAILURE
            }
        } catch (_: Exception) {
            outcome = RootCleanup.NO_ACK
            detail = "CLEANUP_FAILED"
        } finally {
            try {
                recoveryRecord = record.takeUnless { outcome.confirmed }
                automaticRetryRecord = null
                publish(
                    if (outcome.confirmed) RootSessionPhase.IDLE else RootSessionPhase.RECOVERY_REQUIRED,
                    record.sessionId, detail, record.helper, attempts,
                    dispatcherStatus = connection.dispatcherStatus.name,
                    touches = if (outcome.confirmed) 0 else connection.uncertainPointerIds?.size,
                    termination = termination, cleanup = outcome, recoveryResult = recovery,
                )
            } finally {
                lease = null
                inputDispatchMutex.unlock()
                mutex.unlock()
                owner.result.complete(outcome)
            }
        }
        outcome
    }

    private suspend fun clearJournal(record: RootReleaseRecord): Boolean =
        withContext(Dispatchers.IO) { releaseGuard?.confirm(record) != false }

    private fun publish(
        phase: RootSessionPhase,
        id: String?,
        detail: String? = null,
        helper: RootHelperIdentity? = null,
        attempts: Int = 0,
        dispatcherId: String? = null,
        dispatcherStatus: String? = null,
        touches: Int? = null,
        lockState: RootPlaybackLockState = when (phase) {
            RootSessionPhase.IDLE -> RootPlaybackLockState.LOCK_FREE
            RootSessionPhase.PREPARING, RootSessionPhase.ACTIVE, RootSessionPhase.CLEANING -> RootPlaybackLockState.LOCK_HELD
            RootSessionPhase.RECOVERING -> RootPlaybackLockState.LOCK_RECOVERY_REQUIRED
            RootSessionPhase.RECOVERY_REQUIRED -> RootPlaybackLockState.LOCK_RELEASE_UNCONFIRMED
        },
        termination: String? = null,
        cleanup: RootCleanup? = null,
        recoveryResult: RootRecoveryResult? = null,
    ) {
        val previous = sessionState.value
        val before = previous.takeIf { it.id == id }
        val next = RootSessionState(
            id, phase, detail, helper, recoveryAttempts = attempts,
            dispatcherId = dispatcherId ?: before?.dispatcherId,
            dispatcherStatus = dispatcherStatus ?: before?.dispatcherStatus,
            activeTouchCount = touches, lockState = lockState,
            terminationReason = termination ?: before?.terminationReason,
            lastFailureReason = termination ?: cleanup?.takeUnless { it.confirmed }?.name
                ?: recoveryResult?.takeUnless { it == RootRecoveryResult.CONFIRMED }?.name ?: previous.lastFailureReason,
            cleanup = cleanup ?: before?.cleanup,
            recoveryResult = recoveryResult ?: previous.recoveryResult,
        )
        mutableSessionState.value = next
        diagnostics.transition(next)
        reportSession(next)
    }

    /** Called with the backend lock held. The shared dispatch lock covers the full proof interval. */
    private suspend fun verifyRecovery(record: RootReleaseRecord, explicit: Boolean) {
        if (!explicit && automaticRetryRecord == record) return
        if (!inputDispatchMutex.tryLock()) return
        if (!explicit) automaticRetryRecord = record
        var attempts = sessionState.value.recoveryAttempts
        var outcome = RootRecoveryResult.INTERRUPTED
        var lastProbe: RootRecoveryResult? = null
        try {
            outcome = withTimeoutOrNull(RECOVERY_DEADLINE_MS) {
                repeat(MAX_RECOVERY_PROBES) { index ->
                    if (index > 0) waitForRecovery(RECOVERY_RETRY_DELAY_MS)
                    if (attempts < Int.MAX_VALUE) attempts++
                    publish(RootSessionPhase.RECOVERING, record.sessionId, helper = record.helper, attempts = attempts)
                    val result = recoveryProbe.verify(record)
                    lastProbe = result
                    if (result != RootRecoveryResult.HELPER_ALIVE && result != RootRecoveryResult.DISPATCHER_UNRESPONSIVE) {
                        return@withTimeoutOrNull result
                    }
                }
                RootRecoveryResult.RETRY_LIMIT
            } ?: RootRecoveryResult.DEADLINE_EXCEEDED
            if (outcome == RootRecoveryResult.CONFIRMED) {
                // Couple the disk commit with the in-memory transition even if the caller cancelled.
                withContext(NonCancellable) {
                    if (clearJournal(record)) {
                        recoveryRecord = null
                        automaticRetryRecord = null
                        publish(RootSessionPhase.IDLE, record.sessionId, outcome.name, record.helper, attempts,
                            dispatcherStatus = "TERMINATED", touches = 0, recoveryResult = outcome)
                    } else {
                        outcome = RootRecoveryResult.JOURNAL_FAILURE
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            outcome = RootRecoveryResult.PROBE_FAILED
        } finally {
            try {
                if (recoveryRecord != null) {
                    val detail = if (outcome == RootRecoveryResult.RETRY_LIMIT) "${outcome.name} / ${lastProbe?.name}" else outcome.name
                    publish(RootSessionPhase.RECOVERY_REQUIRED, record.sessionId, detail, record.helper, attempts,
                        recoveryResult = outcome)
                }
            } finally { inputDispatchMutex.unlock() }
        }
    }

    internal fun validPoint(point: ScreenPoint): Boolean {
        val size = screenSize()
        return point.x in 0f..(size.width - 1f) && point.y in 0f..(size.height - 1f)
    }

    internal fun resultAfterRelease(cleanup: RootCleanup, dispatched: TapResult): TapResult =
        if (cleanup.confirmed && !releaseUncertain) dispatched else TapResult.Unavailable(status().message)

    /** Read-only focus query. Never synthesizes input or modifies system settings. */
    suspend fun focusedPackage(): String? =
        observeFocusedWindow(invalidateAuthorizationOnTimeout = true)?.window?.packageName

    internal suspend fun observeFocusedWindow(
        invalidateAuthorizationOnTimeout: Boolean = false,
    ): FocusedWindow.Probe? {
        if (!status().available || !focusMutex.tryLock()) return null
        return try {
            when (val result=runner.run("dumpsys window | grep 'mCurrentFocus='", 3000)) {
                is RootCommandResult.Success -> FocusedWindow.parseProbe(result.output)
                is RootCommandResult.Failure -> {
                    if (result.authorizationDenied || result.kind == RootCommandFailureKind.LAUNCH) {
                        authorization.recordFailure(result)
                    }
                    null
                }
                RootCommandResult.TimedOut -> {
                    if (invalidateAuthorizationOnTimeout) authorization.recordFailure(result)
                    null
                }
            }
        } finally { focusMutex.unlock() }
    }

    /** Explicit user action: may trigger the device's Superuser authorization UI. */
    suspend fun checkAccess(): BackendStatus = checkAccess(explicit = true, tryRecovery = false)

    /** A requested Root operation can validate a remembered grant once. */
    internal suspend fun checkAccessForUse(): BackendStatus = checkAccess(explicit = false, tryRecovery = true)

    internal suspend fun recover(): BackendStatus = checkAccess(explicit = true, tryRecovery = true)

    private suspend fun checkAccess(explicit: Boolean, tryRecovery: Boolean): BackendStatus {
        if (!mutex.tryLock()) return status()
        try {
            if (explicit) {
                if (!tryRecovery || !authorization.state.value.verified) authorization.request()
            } else {
                authorization.ensureForUse()
            }
            if (tryRecovery && authorization.state.value.verified) recoveryRecord?.let { verifyRecovery(it, explicit) }
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
            val cleanup: RootCleanup
            val dispatched = try {
                connection.play(com.huanhren.deltaharmonica.playback.CombinedGesture.create(point, 45, emptyList()))
            } finally { cleanup = closeTouch(connection) }
            return resultAfterRelease(cleanup, dispatched)
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

    private companion object {
        const val RECOVERY_DEADLINE_MS = 5_500L
        const val MAX_RECOVERY_PROBES = 3
        const val RECOVERY_RETRY_DELAY_MS = 150L
        val normalTerminations = setOf("COMPLETED", "USER_STOP", "VOLUME_KEY", "TRACK_CHANGE", "STOP_CONFIRMED")
    }
}
