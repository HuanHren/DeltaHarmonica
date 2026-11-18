package com.huanhren.deltaharmonica.playback

internal enum class PlaybackInput {
    ROOT, ACCESSIBILITY;

    suspend fun focusedPackage(root: suspend () -> String?, accessibility: () -> String?): String? =
        when (this) {
            ROOT -> root() ?: accessibility()
            ACCESSIBILITY -> accessibility()
        }
}

internal suspend fun selectPlaybackInput(
    preferRoot: Boolean,
    accessibilityAvailable: () -> Boolean,
    authorizeRoot: suspend () -> Boolean,
): PlaybackInput? {
    if (preferRoot && authorizeRoot()) return PlaybackInput.ROOT
    return PlaybackInput.ACCESSIBILITY.takeIf { accessibilityAvailable() }
}
