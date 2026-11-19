package com.huanhren.deltaharmonica.ui

import org.junit.Assert.*
import org.junit.Test

class StudioAppearanceTest {
    @Test fun navigationAlwaysRetainsAnEntryAndPreservesOrder() {
        val configured = StudioAppearance(navigation = setOf(StudioTab.SETTINGS, StudioTab.HOME))
        assertEquals(listOf(StudioTab.HOME, StudioTab.SETTINGS), configured.tabs)
        val onlySettings = configured.toggle(StudioTab.HOME)
        assertEquals(listOf(StudioTab.SETTINGS), onlySettings.tabs)
        assertEquals(onlySettings, onlySettings.toggle(StudioTab.SETTINGS))
        assertEquals(listOf(StudioTab.LIBRARY), StudioAppearance(navigation = emptySet()).tabs)
        assertEquals(listOf(StudioTab.LIBRARY, StudioTab.PLAYER, StudioTab.SETTINGS), StudioAppearance().tabs)
    }

    @Test fun resizeKeepsFiniteBounds() {
        assertEquals(.8f, StudioAppearance().resize(.3f).scale)
        assertEquals(1.1f, StudioAppearance().resize(2f).scale)
        assertEquals(1f, StudioAppearance().resize(Float.NaN).scale)
        assertEquals(.905f, StudioAppearance().resize(.905f).scale)
    }

    @Test fun gestureChoosesOnlyVisibleSlotsAndHonorsRtl() {
        assertEquals(0, studioTabAt(-20f, 240f, 3, false))
        assertEquals(1, studioTabAt(90f, 240f, 3, false))
        assertEquals(2, studioTabAt(250f, 240f, 3, false))
        assertEquals(2, studioTabAt(20f, 240f, 3, true))
    }

    @Test fun visualCapabilitiesKeepBlurAndGlassIndependent() {
        val appearance = StudioAppearance(blur = true, glass = false)
        assertEquals(SurfaceEffects(true, false), appearance.effects(33, false))
        assertEquals(SurfaceEffects(false, false), appearance.effects(32, false))
        assertEquals(SurfaceEffects(false, false), appearance.effects(36, true))
        assertEquals(SurfaceEffects(false, true), appearance.copy(blur = false, glass = true).effects(36, false))
    }
}
