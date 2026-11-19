package com.huanhren.deltaharmonica.input

/** Android must receive both transitions for a volume key that the playback command did not handle. */
internal class PlaybackKeyPress {
    private val consumedDownTimes = mutableMapOf<Int, Long>()

    fun down(deviceId: Int, downTime: Long, repeated: Boolean, onPress: () -> Boolean): Boolean {
        if (!repeated) {
            consumedDownTimes.remove(deviceId)
            if (onPress()) consumedDownTimes[deviceId] = downTime
        }
        return consumedDownTimes[deviceId] == downTime
    }

    fun up(deviceId: Int, downTime: Long): Boolean {
        if (consumedDownTimes[deviceId] != downTime) return false
        consumedDownTimes.remove(deviceId)
        return true
    }
}
