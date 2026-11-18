package com.huanhren.deltaharmonica.model

import kotlinx.serialization.Serializable

@Serializable enum class LayoutOrientation { PORTRAIT, LANDSCAPE }
@Serializable enum class CoordinateSpace { FULL_DISPLAY }
@Serializable enum class TargetType { NOTE, REGION, TOGGLE, OTHER }
@Serializable data class ReferenceDisplay(val width: Int, val height: Int) {
    init { require(width > 1 && height > 1) }
}
@Serializable data class TouchTarget(
    val id: String, val label: String, val position: NormalizedPoint,
    val type: TargetType = TargetType.OTHER, val attributes: Map<String, String> = emptyMap(),
) { init { require(id.isNotBlank() && label.isNotBlank()) } }

/** Coordinates refer to the complete default display in the recorded orientation. */
@Serializable data class InstrumentLayout(
    val id: String, val name: String, val game: String, val instrument: String,
    val orientation: LayoutOrientation, val targets: List<TouchTarget>,
    val createdAt: Long, val updatedAt: Long, val coordinateSpace: CoordinateSpace = CoordinateSpace.FULL_DISPLAY,
    val referenceDisplay: ReferenceDisplay? = null, val profile: InstrumentProfile? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(targets.isNotEmpty() && targets.map { it.id }.distinct().size == targets.size)
        require(createdAt >= 0 && updatedAt >= createdAt)
        require(referenceDisplay == null || (referenceDisplay.width >= referenceDisplay.height) == (orientation == LayoutOrientation.LANDSCAPE))
    }
}
