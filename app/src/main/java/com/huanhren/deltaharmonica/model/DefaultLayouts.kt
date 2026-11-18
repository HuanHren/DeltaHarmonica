package com.huanhren.deltaharmonica.model

import java.util.UUID
import kotlin.math.ceil
import kotlin.math.sqrt

object DefaultLayouts {
    fun controls() = listOf(
        TouchTarget("REGION_HIGH", "升调", NormalizedPoint(.45f, .42f), TargetType.REGION, mapOf("region" to "HIGH")),
        TouchTarget("REGION_NATURAL", "自然音", NormalizedPoint(.55f, .42f), TargetType.REGION, mapOf("region" to "NATURAL")),
        TouchTarget("REGION_LOW", "降调", NormalizedPoint(.65f, .42f), TargetType.REGION, mapOf("region" to "LOW")),
        TouchTarget("SEMITONE_TOGGLE", "半音", NormalizedPoint(.35f, .42f), TargetType.TOGGLE, mapOf("switch" to "semitone")),
    )
    fun create(now: Long = System.currentTimeMillis()): InstrumentLayout {
        val notes = (1..8).map { TouchTarget("NOTE_$it", "Key $it", NormalizedPoint(it / 9f, .65f), TargetType.NOTE, mapOf("keyIndex" to "${it - 1}")) }
        return InstrumentLayout(UUID.randomUUID().toString(), "口风琴布局", "三角洲行动", "口风琴", LayoutOrientation.LANDSCAPE,
            notes + controls(), now, now, profile = DefaultProfiles.delta())
    }
    fun arrange(targets: List<TouchTarget>): List<TouchTarget> {
        val columns = ceil(sqrt(targets.size * 2.0)).toInt().coerceAtLeast(1)
        val rows = ceil(targets.size.toDouble() / columns).toInt().coerceAtLeast(1)
        return targets.mapIndexed { index, target -> target.copy(position = NormalizedPoint(
            (index % columns + 1f) / (columns + 1f), .40f + .50f * (index / columns + 1f) / (rows + 1f))) }
    }
}

