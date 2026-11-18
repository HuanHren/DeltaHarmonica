package com.huanhren.deltaharmonica.input

enum class RootTouchState { IDLE, PRESSED, HOLDING, RELEASE_REQUESTED, RELEASED, UNKNOWN }

/**
 * Fail-closed state model for a future Root driver. Not connected to input injection.
 * PRESSED/HOLDING describe requests, not independently observed hardware events.
 * releaseObserved must only be called by a validated release observer, never on shell exit.
 */
internal class RootTouchSession {
    var state: RootTouchState = RootTouchState.IDLE
        private set
    var actionId: Long = 0
        private set
    var action: NoteAction? = null
        private set
    private var stopped = false

    @Synchronized fun begin(next: NoteAction): Boolean {
        if (stopped || (state != RootTouchState.IDLE && state != RootTouchState.RELEASED)) return false
        actionId++
        action = next
        state = RootTouchState.PRESSED
        return true
    }

    @Synchronized fun holding() {
        if (state == RootTouchState.PRESSED) state = RootTouchState.HOLDING
    }

    @Synchronized fun requestRelease() {
        if (state == RootTouchState.PRESSED || state == RootTouchState.HOLDING) state = RootTouchState.RELEASE_REQUESTED
    }

    @Synchronized fun requestStop() {
        stopped = true
        requestRelease()
    }

    /** Unverified command completion, timeout, failure and unverified cleanup are all uncertain. */
    @Synchronized fun commandFinished(id: Long) {
        if (id == actionId) cleanupUnconfirmed()
    }

    @Synchronized fun cleanupUnconfirmed() {
        if (state == RootTouchState.PRESSED || state == RootTouchState.HOLDING || state == RootTouchState.RELEASE_REQUESTED)
            state = RootTouchState.UNKNOWN
    }

    @Synchronized fun releaseObserved(id: Long): Boolean {
        if (id != actionId || state != RootTouchState.RELEASE_REQUESTED) return false
        state = RootTouchState.RELEASED
        return true
    }
}
