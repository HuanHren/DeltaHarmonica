package com.huanhren.deltaharmonica.playback

/** Planning allowance for DOWN and UP transport. Runtime gaps use physical floors. */
internal const val ROOT_STROKE_DISPATCH_MARGIN_MS = 12L

/** Physical milliseconds. The Android candidate requires in-game audio calibration. */
data class RootPlaybackTiming(
    val minNoteHoldMs: Long = 45,
    val controlHoldMs: Long = 44,
    val controlSettleMs: Long = 36,
    val differentKeyGapMs: Long = 18,
    val sameKeyGapMs: Long = 42,
    val maxDownLatenessMs: Long = 40,
) {
    init {
        require(minNoteHoldMs in 1..500)
        require(controlHoldMs in 1..500 && controlSettleMs in 1..500)
        require(differentKeyGapMs in 1..500 && sameKeyGapMs in differentKeyGapMs..500)
        require(maxDownLatenessMs in 1..500)
    }

    val controlSlotMs: Long get() = controlHoldMs + controlSettleMs + ROOT_STROKE_DISPATCH_MARGIN_MS

    internal fun gapBeforeControls(sameKey: Boolean, setupMs: Long): Long =
        if (sameKey) maxOf(differentKeyGapMs, sameKeyGapMs - setupMs) else differentKeyGapMs

    internal fun plannedGap(sameKey: Boolean, setupMs: Long): Long =
        gapBeforeControls(sameKey, setupMs - (setupMs / controlSlotMs) * ROOT_STROKE_DISPATCH_MARGIN_MS) +
            ROOT_STROKE_DISPATCH_MARGIN_MS
}
