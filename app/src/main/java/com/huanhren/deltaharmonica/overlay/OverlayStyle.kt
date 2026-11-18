package com.huanhren.deltaharmonica.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.huanhren.deltaharmonica.ui.theme.ColorTokens
import kotlin.math.roundToInt

internal class OverlayStyle(private val context: Context, dark: Boolean) {
    private val tokens = ColorTokens
    val ink = (if (dark) tokens.DarkInk else tokens.LightInk).toArgb()
    val muted = (if (dark) tokens.DarkMuted else tokens.LightMuted).toArgb()
    val accent = (if (dark) tokens.DarkAccent else tokens.LightAccent).toArgb()
    private val tint = (if (dark) tokens.DarkTint else tokens.LightTint).toArgb()
    private val onTint = (if (dark) tokens.OnDarkTint else tokens.OnLightTint).toArgb()
    private val canvas = (if (dark) tokens.DarkCanvas else tokens.LightCanvas).toArgb()
    val panel = (if (dark) tokens.OverlayPanel else tokens.LightCard.copy(alpha = .96f)).toArgb()

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun text(value: String, size: Float = 13f, strong: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (strong) ink else muted)
        if (strong) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun button(label: String, selected: Boolean = false, primary: Boolean = false, action: () -> Unit) =
        Button(context).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            stateListAnimator = null
            minHeight = 0
            minimumHeight = dp(40)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(muted, when { primary -> canvas; selected -> onTint; else -> ink }),
            ))
            background = RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x33000000),
                rounded(when { primary -> accent; selected -> tint; else -> canvas }), null)
            setOnClickListener { action() }
        }
}
