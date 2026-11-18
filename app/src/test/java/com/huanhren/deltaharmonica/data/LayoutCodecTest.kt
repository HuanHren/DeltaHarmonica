package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.model.*
import org.junit.Assert.*
import org.junit.Test

class LayoutCodecTest {
    private val layout = InstrumentLayout("test", "Custom", "Other game", "Other instrument",
        LayoutOrientation.PORTRAIT, (1..12).map {
            TouchTarget("key-$it", "Key $it", NormalizedPoint(it / 13f, 0.5f))
        }, 100L, 200L)

    @Test fun roundTripPreservesMetadataAndArbitraryKeyCount() {
        assertEquals(layout, LayoutCodec.decode(LayoutCodec.encode(layout)))
    }

    @Test fun futureVersionCannotSilentlyOverwriteUserData() {
        val json = LayoutCodec.encode(layout).replace("\"schemaVersion\":2", "\"schemaVersion\":3")
        assertThrows(IllegalArgumentException::class.java) { LayoutCodec.decode(json) }
    }

    @Test fun malformedJsonIsReported() {
        assertThrows(IllegalArgumentException::class.java) { LayoutCodec.decode("broken") }
    }

    @Test fun duplicateTargetIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            layout.copy(targets = listOf(layout.targets.first(), layout.targets.first()))
        }
    }

    @Test fun outOfRangeStoredCoordinatesAreRejected() {
        val json = LayoutCodec.encode(layout).replace("\"y\":0.5", "\"y\":1.5")
        assertThrows(IllegalArgumentException::class.java) { LayoutCodec.decode(json) }
    }
}

