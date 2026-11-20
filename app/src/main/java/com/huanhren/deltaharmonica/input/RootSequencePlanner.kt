package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.playback.PlaybackPlan
import com.huanhren.deltaharmonica.playback.ROOT_STROKE_DISPATCH_MARGIN_MS

/** Builds the whole input timeline before the first finger goes down. */
internal object RootSequencePlanner {
    fun build(
        plan: PlaybackPlan,
        points: Map<String, ScreenPoint>,
        checkActive: () -> Unit = {},
    ): List<RootSequenceStep> {
        val timing = requireNotNull(plan.rootTiming)
        require(plan.fatalErrors.isEmpty() && plan.events.isNotEmpty())
        return plan.events.mapIndexed { index, item ->
            if (index % 1024 == 0) checkActive()
            val note = requireNotNull(points[item.event.noteKeyId]) { "音键校准缺失" }
            val controls = buildList<ScreenPoint> {
                if (item.regionSwitch) add(requireNotNull(points["REGION_${item.event.pitchRegion.name}"]) { "音区校准缺失" })
                if (item.semitoneSwitch) add(requireNotNull(points["SEMITONE_TOGGLE"]) { "半音校准缺失" })
            }
            require((controls + note).toSet().size == controls.size + 1) { "控制点和音键重合" }
            val setup = controls.size * timing.controlSlotMs
            val strokes = controls.mapIndexed { controlIndex, point ->
                RootStroke(point.x, point.y, controlIndex * timing.controlSlotMs, timing.controlHoldMs)
            } + RootStroke(note.x, note.y, setup, item.predictedEndMs - item.predictedStartMs)
            val gap = if (index == 0) 0L else timing.gapBeforeControls(
                plan.events[index - 1].event.noteKeyId == item.event.noteKeyId,
                setup - controls.size * ROOT_STROKE_DISPATCH_MARGIN_MS,
            )
            RootSequenceStep(item.predictedStartMs, strokes, gap, timing.maxDownLatenessMs)
        }
    }
}
