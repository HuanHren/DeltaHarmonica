package com.huanhren.deltaharmonica.playback

import kotlin.math.abs

/** Bounded aggregate of InputManager DOWN/UP times after adjusting for pauses. */
internal class RootPlaybackMetrics(maxLatenessMs: Long) {
    private val lateness = IntArray(maxLatenessMs.toInt() + 1)
    private var count = 0
    private var latenessSum = 0L
    private var maxLate = 0L
    private var holdErrorSum = 0L
    private var maxHoldError = 0L
    private var firstDrift: Long? = null
    private var lastDrift: Long? = null

    fun record(planned: Long, down: Long, up: Long, holdMs: Long, planDriftMs: Long) {
        val late = down - planned
        val holdError = abs(up - down - holdMs)
        lateness[late.coerceIn(0L, lateness.lastIndex.toLong()).toInt()]++
        count++
        latenessSum += late
        maxLate = maxOf(maxLate, late)
        holdErrorSum += holdError
        maxHoldError = maxOf(maxHoldError, holdError)
        lastDrift = planDriftMs + late
        if (firstDrift == null) firstDrift = lastDrift
    }

    private fun percentile(percent: Int): Long {
        val rank = (count * percent + 99) / 100
        var seen = 0
        for (i in lateness.indices) {
            seen += lateness[i]
            if (seen >= rank) return i.toLong()
        }
        return maxLate
    }

    fun summary(): String {
        if (count == 0) return "timedNotes=0"
        return "timedNotes=$count meanLateMs=${latenessSum.toDouble() / count} maxLateMs=$maxLate " +
            "p95LateMs=${percentile(95)} p99LateMs=${percentile(99)} " +
            "meanAbsHoldErrorMs=${holdErrorSum.toDouble() / count} maxAbsHoldErrorMs=$maxHoldError " +
            "onsetDriftMs=${requireNotNull(lastDrift) - requireNotNull(firstDrift)}"
    }
}
