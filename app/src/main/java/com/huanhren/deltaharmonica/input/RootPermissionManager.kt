package com.huanhren.deltaharmonica.input

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class RootAuthorizationState {
    UNKNOWN,
    NOT_ROOTED,
    NOT_AUTHORIZED,
    PREVIOUSLY_AUTHORIZED,
    AUTHORIZED,
    DENIED,
    REVOKED,
    MANAGER_UNAVAILABLE,
    RECHECK_REQUIRED,
    ERROR,
}

internal enum class RootManagerKind(val packageName: String, val label: String) {
    MAGISK("com.topjohnwu.magisk", "Magisk"),
    KERNEL_SU("me.weishu.kernelsu", "KernelSU"),
    APATCH("me.bmax.apatch", "APatch"),
}

internal enum class RootInputReadiness { UNKNOWN, READY, UNAVAILABLE }

internal data class RootManagerInstallation(val kind: RootManagerKind, val updatedAt: Long)
internal data class RootEnvironment(
    val suVisible: Boolean? = null,
    val manager: RootManagerInstallation? = null,
)

internal data class RootAuthorizationRecord(
    val environment: RootEnvironment,
    val authorization: RootAuthorizationState,
    val lastCheckedAt: Long,
    val lastAuthorizedAt: Long?,
    val input: RootInputReadiness,
    val failure: String?,
)

internal interface RootAuthorizationStore {
    suspend fun read(): RootAuthorizationRecord?
    suspend fun write(record: RootAuthorizationRecord)
}

internal data class RootPermissionSnapshot(
    val environment: RootEnvironment = RootEnvironment(),
    val authorization: RootAuthorizationState = RootAuthorizationState.UNKNOWN,
    val input: RootInputReadiness = RootInputReadiness.UNKNOWN,
    val lastAuthorizedAt: Long? = null,
    val failure: String? = null,
    val checking: Boolean = false,
) {
    val verified: Boolean
        get() = authorization == RootAuthorizationState.AUTHORIZED && input != RootInputReadiness.UNAVAILABLE
    val canAttemptUse: Boolean
        get() = when (authorization) {
            RootAuthorizationState.UNKNOWN,
            RootAuthorizationState.NOT_AUTHORIZED,
            RootAuthorizationState.PREVIOUSLY_AUTHORIZED,
            RootAuthorizationState.AUTHORIZED -> true
            else -> false
        }
}

internal class RootPermissionManager(
    private val runner: RootCommandRunner,
    private val detect: suspend () -> RootEnvironment = { RootEnvironment() },
    private val store: RootAuthorizationStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val allowed: Boolean = true,
) {
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(RootPermissionSnapshot(authorization = if (allowed) {
        RootAuthorizationState.UNKNOWN
    } else RootAuthorizationState.MANAGER_UNAVAILABLE))
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var record: RootAuthorizationRecord? = null

    suspend fun initialize() = gate.withLock {
        if (!initialized) refreshLocked()
    }

    suspend fun refreshEnvironment() = gate.withLock { refreshLocked() }

    // Used after a Root action is selected, never from cold-start initialization.
    suspend fun ensureForUse(): RootPermissionSnapshot = gate.withLock {
        if (!initialized || mutableState.value.canAttemptUse) refreshLocked()
        val current = mutableState.value
        if (!allowed || current.verified || !current.canAttemptUse) return@withLock current
        val timeout = if (current.authorization == RootAuthorizationState.PREVIOUSLY_AUTHORIZED) {
            CACHED_PROBE_MS
        } else REQUEST_MS
        verifyLocked(timeout)
    }

    suspend fun request(): RootPermissionSnapshot = gate.withLock {
        if (!allowed) return@withLock mutableState.value
        refreshLocked()
        verifyLocked(REQUEST_MS)
    }

    suspend fun updateInput(readiness: RootInputReadiness, failure: String? = null) = gate.withLock {
        val saved = record ?: return@withLock
        if (!mutableState.value.verified || saved.input == readiness) return@withLock
        val authorization = if (readiness == RootInputReadiness.UNAVAILABLE) {
            RootAuthorizationState.RECHECK_REQUIRED
        } else RootAuthorizationState.AUTHORIZED
        persist(saved.copy(authorization = authorization, input = readiness, failure = failure))
    }

    suspend fun recordFailure(result: RootCommandResult) = gate.withLock {
        val saved = record ?: return@withLock
        if (!mutableState.value.verified || result is RootCommandResult.Success) return@withLock
        val state = if (result is RootCommandResult.Failure && result.authorizationDenied) {
            RootAuthorizationState.REVOKED
        } else RootAuthorizationState.RECHECK_REQUIRED
        persist(saved.copy(
            authorization = state,
            lastCheckedAt = now(),
            input = RootInputReadiness.UNAVAILABLE,
            failure = when (result) {
                is RootCommandResult.Failure -> result.reason
                RootCommandResult.TimedOut -> "Root 命令超时，请重新检查授权"
                is RootCommandResult.Success -> null
            },
        ))
    }

    private suspend fun refreshLocked() {
        if (!allowed) return
        if (!initialized) {
            record = try { store?.read() } catch (_: IOException) { null }
        }
        val environment = detect()
        val saved = record
        val current = mutableState.value
        val wasInitialized = initialized
        initialized = true
        val managerChanged = saved != null && saved.environment.manager != environment.manager
        val lostSu = saved?.environment?.suVisible == true && environment.suVisible == false
        if (saved != null && (managerChanged || lostSu)) {
            persist(saved.copy(
                environment = environment,
                authorization = RootAuthorizationState.RECHECK_REQUIRED,
                lastCheckedAt = now(),
                input = RootInputReadiness.UNKNOWN,
                failure = if (managerChanged) "检测到 Root 管理器变化，请重新检查授权"
                else "su 已不可见，请重新检查 Root 授权",
            ))
            return
        }
        if (wasInitialized && current.environment == environment) return
        val seen = saved?.authorization
        val authorization = when {
            wasInitialized && current.verified -> RootAuthorizationState.AUTHORIZED
            seen == RootAuthorizationState.AUTHORIZED -> RootAuthorizationState.PREVIOUSLY_AUTHORIZED
            seen == RootAuthorizationState.NOT_ROOTED && environment.suVisible == true -> RootAuthorizationState.NOT_AUTHORIZED
            seen != null -> seen
            environment.suVisible == false && environment.manager == null -> RootAuthorizationState.NOT_ROOTED
            else -> RootAuthorizationState.NOT_AUTHORIZED
        }
        mutableState.value = RootPermissionSnapshot(
            environment = environment,
            authorization = authorization,
            input = when (authorization) {
                RootAuthorizationState.AUTHORIZED -> current.input
                RootAuthorizationState.PREVIOUSLY_AUTHORIZED -> RootInputReadiness.UNKNOWN
                else -> saved?.input ?: RootInputReadiness.UNKNOWN
            },
            lastAuthorizedAt = saved?.lastAuthorizedAt,
            failure = saved?.failure,
        )
    }

    private suspend fun verifyLocked(timeoutMs: Long): RootPermissionSnapshot {
        mutableState.update { it.copy(checking = true) }
        try {
            val result = runner.run("id -u", timeoutMs)
            val environment = mutableState.value.environment
            val lastAuthorized = record?.lastAuthorizedAt
            val authorization = when (result) {
                is RootCommandResult.Success -> if (result.output.trim() == "0") {
                    RootAuthorizationState.AUTHORIZED
                } else RootAuthorizationState.ERROR
                is RootCommandResult.Failure -> when {
                    result.authorizationDenied -> if (lastAuthorized != null) RootAuthorizationState.REVOKED else RootAuthorizationState.DENIED
                    result.kind == RootCommandFailureKind.LAUNCH && environment.suVisible == false && environment.manager == null ->
                        RootAuthorizationState.NOT_ROOTED
                    result.kind == RootCommandFailureKind.LAUNCH -> RootAuthorizationState.MANAGER_UNAVAILABLE
                    else -> RootAuthorizationState.ERROR
                }
                RootCommandResult.TimedOut -> RootAuthorizationState.ERROR
            }
            val failure = when (result) {
                is RootCommandResult.Success -> if (authorization == RootAuthorizationState.AUTHORIZED) null else "su 未返回 Root UID；请检查授权"
                is RootCommandResult.Failure -> result.reason
                RootCommandResult.TimedOut -> "Root 授权检查超时；请检查授权提示"
            }
            val checkedAt = now()
            persist(RootAuthorizationRecord(
                environment = environment,
                authorization = authorization,
                lastCheckedAt = checkedAt,
                lastAuthorizedAt = if (authorization == RootAuthorizationState.AUTHORIZED) checkedAt else lastAuthorized,
                input = if (authorization == RootAuthorizationState.AUTHORIZED) RootInputReadiness.UNKNOWN else RootInputReadiness.UNAVAILABLE,
                failure = failure,
            ))
        } finally {
            mutableState.update { it.copy(checking = false) }
        }
        return mutableState.value
    }

    private suspend fun persist(next: RootAuthorizationRecord) {
        record = next
        mutableState.update {
            it.copy(
                environment = next.environment,
                authorization = next.authorization,
                input = next.input,
                lastAuthorizedAt = next.lastAuthorizedAt,
                failure = next.failure,
            )
        }
        try { store?.write(next) } catch (_: IOException) { /* In-memory verification remains usable. */ }
    }

    private companion object {
        const val REQUEST_MS = 30_000L
        const val CACHED_PROBE_MS = 4_000L
    }
}
