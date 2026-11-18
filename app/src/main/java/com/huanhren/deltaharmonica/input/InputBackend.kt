package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint

enum class BackendAvailability { AVAILABLE, NOT_ENABLED, NOT_CONNECTED, NOT_IMPLEMENTED }

data class BackendStatus(val availability: BackendAvailability, val message: String) {
    val available: Boolean get() = availability == BackendAvailability.AVAILABLE
}

sealed interface TapResult {
    data object Completed : TapResult
    data object Cancelled : TapResult
    data object Rejected : TapResult
    data object TimedOut : TapResult
    data object Busy : TapResult
    data class Unavailable(val reason: String) : TapResult
    data class InvalidPoint(val reason: String) : TapResult
}

interface InputBackend {
    val name: String
    fun status(): BackendStatus
    /** Pixels in the current orientation of the default display. Completion is not a game acknowledgement. */
    suspend fun tap(point: ScreenPoint): TapResult
}
