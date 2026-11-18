package com.huanhren.deltaharmonica.input

import org.junit.Assert.assertEquals
import org.junit.Test

class RootVolumeKeyMonitorTest {
    @Test fun selectsThePhysicalXiaomiVolumeDriverWithoutOpeningWearableOrTouchDevices() {
        val listing = """
            add device 1: /dev/input/event10
              name:     "Xiaomi Smart Band 9 8095"
              events:
                KEY (0001): KEY_VOLUMEDOWN    KEY_VOLUMEUP
            add device 2: /dev/input/event7
              name:     "synaptics_tcm_touch"
            add device 3: /dev/input/event4
              name:     "pmic_resin"
              events:
                KEY (0001): KEY_VOLUMEDOWN
            add device 4: /dev/input/event0
              name:     "gpio-keys"
              events:
                KEY (0001): KEY_VOLUMEUP
        """.trimIndent()
        assertEquals(listOf("/dev/input/event4"), RootVolumeKeyMonitor.findDevices(listing))
    }
}
