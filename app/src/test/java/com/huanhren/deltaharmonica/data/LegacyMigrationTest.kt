package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.model.*
import org.junit.Assert.*
import org.junit.Test

class LegacyMigrationTest {
    private val legacy = InstrumentLayout("legacy", "口风琴布局", "三角洲行动", "口风琴", LayoutOrientation.LANDSCAPE,
        (1..8).map { TouchTarget("key-$it", "renamed $it", NormalizedPoint(it/10f, 0.5f)) }, 1, 2)
    private fun versionOne(layout: InstrumentLayout) = LayoutCodec.encode(layout).replace("\"schemaVersion\":2", "\"schemaVersion\":1")
    @Test fun legacyDefaultPreservesTargetsAndAddsControls() {
        val result = LayoutCodec.decode(versionOne(legacy))
        assertEquals(legacy.targets.map { it.id }, result.targets.take(8).map { it.id })
        assertEquals(legacy.targets.map { it.label }, result.targets.take(8).map { it.label })
        assertEquals(legacy.targets.map { it.position }, result.targets.take(8).map { it.position })
        assertEquals(12, result.targets.size)
        assertEquals(12, result.targets.map { it.id }.distinct().size)
        assertNull(result.referenceDisplay)
    }
    @Test fun customLayoutIsNotExpanded() {
        val custom = legacy.copy(game = "custom", targets = legacy.targets.take(3))
        assertEquals(custom.targets, LayoutCodec.decode(versionOne(custom)).targets)
    }
}
