package com.huanhren.deltaharmonica.model

import kotlinx.serialization.Serializable

@Serializable enum class Region { LOW, NATURAL, HIGH }
@Serializable data class InstrumentState(val region: Region = Region.NATURAL, val semitoneEnabled: Boolean = false) {
    fun withRegion(value: Region) = copy(region = value)
    fun withSemitone(value: Boolean) = copy(semitoneEnabled = value)
}

/** MIDI note numbers; false means the mapping is a candidate, not game-verified. */
@Serializable data class InstrumentProfile(val id: String, val name: String, val pitches: Map<Region, List<Int>>, val verified: Boolean = false) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(pitches.isNotEmpty() && pitches.values.all { it.isNotEmpty() && it.all { pitch -> pitch in 0..127 } })
    }
    fun pitch(state: InstrumentState, keyIndex: Int): Int {
        val base = pitches.getValue(state.region)[keyIndex]
        return (base + if (state.semitoneEnabled) 1 else 0).also { require(it <= 127) }
    }
}
