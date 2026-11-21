package com.huanhren.deltaharmonica.input

internal data class RootDispatcherFailure(
    val operation: Long,
    val reason: RootDispatcherFault,
    val possiblePointerIds: Set<Int>,
)

/** Shared by the owner of the native calls and an independent helper thread. Never holds a lock across injection. */
internal class RootDispatcherWatchdog(
    private val now: () -> Long,
    private val nativeBudgetMs: Long = 1_200,
    private val stopBudgetMs: Long = 800,
    private val transportBudgetMs: Long = 1_000,
) {
    private var operation = 0L
    private var nativeStartedAt: Long? = null
    private var stopStartedAt: Long? = null
    private var stopCause: Exception? = null
    private var transportStartedAt: Long? = null
    private var possiblePointerIds = emptySet<Int>()
    private var failure: RootDispatcherFailure? = null

    @Synchronized
    fun start(id: Long): Boolean {
        if (failure != null || operation != 0L || transportStartedAt != null) return false
        operation = id
        return true
    }

    @Synchronized
    fun requestStop(cause: Exception) {
        if (operation == 0L || stopStartedAt != null) return
        stopStartedAt = now()
        stopCause = cause
    }

    @Synchronized
    fun endTransport() {
        if (transportStartedAt != null) return
        transportStartedAt = now()
        requestStop(RootTransportInterrupted())
    }

    @Synchronized
    fun transportExpired(): Boolean = transportStartedAt?.let { now() - it >= transportBudgetMs } == true

    @Synchronized
    fun canConfirmRelease(): Boolean = operation == 0L && nativeStartedAt == null && failure == null

    @Synchronized
    fun beginNative(kind: RootTouchKind, pointers: Set<Int>) {
        if (checkDeadline() != null) throw RootDispatchSuppressed(RootTransportInterrupted())
        if (kind == RootTouchKind.DOWN) stopCause?.let { throw RootDispatchSuppressed(it) }
        check(operation != 0L && nativeStartedAt == null)
        possiblePointerIds = possiblePointerIds + pointers
        nativeStartedAt = now()
    }

    @Synchronized
    fun endNative(kind: RootTouchKind, id: Int, accepted: Boolean, denied: Boolean): Boolean {
        if (checkDeadline() != null) return false
        nativeStartedAt = null
        if ((kind == RootTouchKind.UP && accepted) || (kind == RootTouchKind.DOWN && denied)) {
            possiblePointerIds = possiblePointerIds - id
        } else if (kind == RootTouchKind.CANCEL && accepted) {
            possiblePointerIds = emptySet()
        }
        return true
    }

    @Synchronized
    fun finish(id: Long): Boolean {
        if (checkDeadline() != null || operation != id) return false
        operation = 0
        stopStartedAt = null
        stopCause = null
        possiblePointerIds = emptySet()
        return true
    }

    @Synchronized
    fun fault(workerAlive: Boolean): RootDispatcherFailure? = checkDeadline() ?: if (workerAlive) null else {
        fail(RootDispatcherFault.WORKER_FAILED)
    }

    private fun checkDeadline(): RootDispatcherFailure? = failure ?: when {
        stopStartedAt?.let { now() - it >= stopBudgetMs } == true -> fail(RootDispatcherFault.STOP_TIMEOUT)
        nativeStartedAt?.let { now() - it >= nativeBudgetMs } == true -> fail(RootDispatcherFault.NATIVE_TIMEOUT)
        else -> null
    }

    private fun fail(reason: RootDispatcherFault): RootDispatcherFailure =
        RootDispatcherFailure(operation, reason, possiblePointerIds).also { failure = it }
}
