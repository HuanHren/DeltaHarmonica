package com.huanhren.deltaharmonica.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackInputTest {
    @Test fun accessibilitySelectionDoesNotProbeRoot() = runTest {
        assertEquals(PlaybackInput.ACCESSIBILITY, selectPlaybackInput(false, { true }) { error("Unexpected su") })
    }

    @Test fun deniedRootCanUseAccessibilityForInputAndFocus() = runTest {
        val input = selectPlaybackInput(true, { true }) { false }
        assertEquals(PlaybackInput.ACCESSIBILITY, input)
        assertEquals("com.tencent.tmgp.dfm", input?.focusedPackage({ error("Unexpected su") }) { "com.tencent.tmgp.dfm" })
    }

    @Test fun rootFailureWithoutAnAuthorizedFallbackLeavesPlaybackBlocked() = runTest {
        assertNull(selectPlaybackInput(true, { false }) { false })
    }

    @Test fun explicitRootFocusOnAnotherAppIsNeverOverridden() = runTest {
        assertEquals("com.android.settings", PlaybackInput.ROOT.focusedPackage({ "com.android.settings" }) {
            error("Root already reports the actual focused package")
        })
    }
}
