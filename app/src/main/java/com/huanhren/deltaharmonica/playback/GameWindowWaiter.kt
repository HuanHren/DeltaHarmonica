package com.huanhren.deltaharmonica.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object GameWindowWaiter {
    suspend fun awaitGame(timeoutMs: Long, isGameActive: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!isGameActive()) delay(150)
            true
        } ?: false
}
