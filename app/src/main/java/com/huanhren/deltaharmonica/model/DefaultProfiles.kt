package com.huanhren.deltaharmonica.model

object DefaultProfiles {
    fun delta() = InstrumentProfile("delta-melodica", "三角洲口风琴（音高待验证）", mapOf(
        Region.LOW to listOf(48, 50, 52, 53, 55, 57, 59, 60),
        Region.NATURAL to listOf(60, 62, 64, 65, 67, 69, 71, 72),
        Region.HIGH to listOf(72, 74, 76, 77, 79, 81, 83, 84),
    ), verified = false)
}
