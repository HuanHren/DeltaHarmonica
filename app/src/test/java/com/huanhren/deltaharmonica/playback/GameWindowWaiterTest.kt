package com.huanhren.deltaharmonica.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GameWindowWaiterTest {
    @Test fun alreadyActiveGameStartsWithoutWaitingForAnotherEvent() = runBlocking {
        var checks=0
        assertTrue(GameWindowWaiter.awaitGame(500) { checks++; true })
        assertEquals(1,checks)
    }
    @Test fun unknownOrDifferentWindowDoesNotStart() = runBlocking {
        assertFalse(GameWindowWaiter.awaitGame(20) { false })
    }
}
