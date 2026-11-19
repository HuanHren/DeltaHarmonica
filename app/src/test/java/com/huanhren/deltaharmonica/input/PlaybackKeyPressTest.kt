package com.huanhren.deltaharmonica.input

import org.junit.Assert.*
import org.junit.Test

class PlaybackKeyPressTest {
    @Test fun acceptedPressConsumesRepeatsAndReleaseWithoutInvokingAnotherCommand() {
        val key = PlaybackKeyPress()
        var commands = 0
        assertTrue(key.down(1, 10L, repeated = false) { commands++; true })
        assertTrue(key.down(1, 10L, repeated = true) { commands++; false })
        assertTrue(key.up(1, 10L))
        assertFalse(key.up(1, 10L))
        assertEquals(1, commands)
    }

    @Test fun overlappingDevicesKeepSeparateVolumeKeySequences() {
        val key = PlaybackKeyPress()
        assertTrue(key.down(1, 10L, repeated = false) { true })
        assertFalse(key.down(2, 12L, repeated = false) { false })
        assertFalse(key.up(2, 12L))
        assertFalse(key.up(1, 9L))
        assertTrue(key.up(1, 10L))
    }

    @Test fun unhandledPressLeavesSystemVolumeKeysUnchanged() {
        val key = PlaybackKeyPress()
        assertFalse(key.down(1, 10L, repeated = false) { false })
        assertFalse(key.down(1, 10L, repeated = true) { error("A repeat must not start a playback command") })
        assertFalse(key.up(1, 10L))
    }
}
