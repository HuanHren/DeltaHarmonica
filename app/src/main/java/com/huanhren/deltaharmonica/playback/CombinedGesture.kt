package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.input.MAX_GESTURE_DURATION_MS

data class CombinedStroke(val point: ScreenPoint, val startMs: Long, val durationMs: Long, val isNote: Boolean)
class CombinedGesture private constructor(val strokes: List<CombinedStroke>) {
    companion object {
        /** Controls start 40 ms before the note and overlap it; game ordering still requires device validation. */
        fun create(note: ScreenPoint, durationMs: Long, controls: List<ScreenPoint>, leadMs: Long = 40): CombinedGesture {
            require(durationMs in 1..MAX_GESTURE_DURATION_MS && controls.size <= 2)
            require(leadMs in 0..79)
            require((controls+note).distinct().size == controls.size+1)
            val delay=if (controls.isEmpty()) 0L else leadMs
            require(durationMs <= MAX_GESTURE_DURATION_MS-delay)
            return CombinedGesture(controls.map { CombinedStroke(it,0,80,false) } + CombinedStroke(note,delay,durationMs,true))
        }
    }
}
