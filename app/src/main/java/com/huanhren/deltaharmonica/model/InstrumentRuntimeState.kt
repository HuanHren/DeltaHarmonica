package com.huanhren.deltaharmonica.model

import com.huanhren.deltaharmonica.input.TapResult

enum class SyncStatus { UNKNOWN, APP_TRACKED, USER_CONFIRMED }
enum class ControlDecision { SEND, SKIP, NEEDS_SYNC }
/** Runtime observations only. Neither a successful gesture nor this model reads game state. */
data class InstrumentRuntimeState(
    val pitchRegion: Region? = null,
    val semitoneEnabled: Boolean? = null,
    val regionStatus: SyncStatus = SyncStatus.UNKNOWN,
    val semitoneStatus: SyncStatus = SyncStatus.UNKNOWN,
    val lastCompletedAction: String? = null,
    val lastFailureReason: String? = null,
) {
    val syncStatus: SyncStatus get() = when {
        regionStatus == SyncStatus.UNKNOWN || semitoneStatus == SyncStatus.UNKNOWN -> SyncStatus.UNKNOWN
        regionStatus == SyncStatus.APP_TRACKED || semitoneStatus == SyncStatus.APP_TRACKED -> SyncStatus.APP_TRACKED
        else -> SyncStatus.USER_CONFIRMED
    }
    fun confirmRegion(region: Region) = copy(pitchRegion = region, regionStatus = SyncStatus.USER_CONFIRMED, lastFailureReason = null)
    fun confirmSemitone(enabled: Boolean) = copy(semitoneEnabled = enabled, semitoneStatus = SyncStatus.USER_CONFIRMED, lastFailureReason = null)
    fun reset() = InstrumentRuntimeState()
    fun planRegion(region: Region) = if (pitchRegion == region) ControlDecision.SKIP else ControlDecision.SEND
    fun planSemitone(enabled: Boolean) = when { semitoneEnabled == null -> ControlDecision.NEEDS_SYNC; semitoneEnabled == enabled -> ControlDecision.SKIP; else -> ControlDecision.SEND }
    fun regionResult(region: Region, result: TapResult) = if (result == TapResult.Completed)
        copy(pitchRegion = region, regionStatus = SyncStatus.APP_TRACKED, lastCompletedAction = "音区 $region", lastFailureReason = null)
    else copy(pitchRegion = null, regionStatus = SyncStatus.UNKNOWN, lastFailureReason = "音区操作结果不确定：$result")
    fun semitoneResult(enabled: Boolean, result: TapResult) = if (result == TapResult.Completed)
        copy(semitoneEnabled = enabled, semitoneStatus = SyncStatus.APP_TRACKED, lastCompletedAction = "半音 $enabled", lastFailureReason = null)
    else copy(semitoneEnabled = null, semitoneStatus = SyncStatus.UNKNOWN, lastFailureReason = "半音操作结果不确定：$result")
}
