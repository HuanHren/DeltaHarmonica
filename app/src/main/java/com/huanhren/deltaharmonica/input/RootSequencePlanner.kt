package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.playback.CombinedGesture
import com.huanhren.deltaharmonica.playback.PlaybackPlan

/** Builds the whole input timeline before the first finger goes down. */
internal object RootSequencePlanner {
    fun build(plan: PlaybackPlan, points: Map<String, ScreenPoint>): List<RootSequenceStep> {
        require(plan.combined) { "连续 Root 演奏需要组合触控计划" }
        require(plan.fatalErrors.isEmpty() && plan.events.isNotEmpty())
        return plan.events.map { item ->
            val note = requireNotNull(points[item.event.noteKeyId]) { "音键校准缺失" }
            val controls = buildList {
                if (item.regionSwitch) add(requireNotNull(points["REGION_${item.event.pitchRegion.name}"]) { "音区校准缺失" })
                if (item.semitoneSwitch) add(requireNotNull(points["SEMITONE_TOGGLE"]) { "半音校准缺失" })
            }
            val duration = item.predictedEndMs - item.predictedStartMs
            val gesture = CombinedGesture.create(note, duration, controls, plan.combinedLeadMs)
            RootSequenceStep(item.predictedStartMs, gesture.strokes.map {
                RootStroke(it.point.x, it.point.y, it.startMs, it.durationMs)
            })
        }
    }
}
