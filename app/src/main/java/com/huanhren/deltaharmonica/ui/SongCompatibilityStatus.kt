package com.huanhren.deltaharmonica.ui

import android.content.Context
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.midi.SongCompatibility

internal fun songCompatibilityLabel(context: Context, compatibility: SongCompatibility): String =
    context.getString(compatibility.label())

internal fun SongCompatibility.label() = when (this) {
    SongCompatibility.FULLY_COMPATIBLE -> R.string.score_fully_compatible
    SongCompatibility.COMPATIBLE_WITH_ADAPTATION -> R.string.score_adapted
    SongCompatibility.PARTIALLY_COMPATIBLE -> R.string.score_partially_compatible
    SongCompatibility.INCOMPATIBLE -> R.string.score_incompatible
}
