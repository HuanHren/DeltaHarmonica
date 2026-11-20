package com.huanhren.deltaharmonica.overlay

import android.app.ActivityManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.huanhren.deltaharmonica.ui.StudioAppearance
import com.huanhren.deltaharmonica.ui.theme.ColorTokens
import kotlin.math.roundToInt

internal class OverlayStyle(val context: Context, appearance: StudioAppearance) {
    private val dark = appearance.theme.forcedDark
        ?: (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    private val scale = appearance.scale
    val motion = !appearance.reducedEffects && android.animation.ValueAnimator.areAnimatorsEnabled()
    private val effects = appearance.effects(
        Build.VERSION.SDK_INT,
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
    )
    private val tokens = ColorTokens
    private val canvas = if (dark) tokens.DarkCanvas else tokens.LightCanvas
    private val surface = if (dark) tokens.DarkCard else tokens.LightCard
    private val seed = appearance.seed?.let { Color(it.hex) }
    private val accentColor = when {
        seed != null -> lerp(seed, if (dark) Color.White else Color.Black, if (dark) .49f else .20f)
        appearance.theme.monet && Build.VERSION.SDK_INT >= 31 -> Color(context.getColor(
            if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_700,
        ))
        dark -> tokens.DarkAccent
        else -> tokens.LightAccent
    }
    private val ink = (if (dark) tokens.DarkInk else tokens.LightInk).toArgb()
    private val muted = (if (dark) tokens.DarkMuted else tokens.LightMuted).toArgb()
    val foreground get() = ink
    val secondaryForeground get() = muted
    val accent = accentColor.toArgb()
    val track = lerp(canvas, accentColor, if (dark) .21f else .16f).toArgb()
    private val tint = lerp(surface, accentColor, if (dark) .26f else .16f).toArgb()
    private val onAccent = (if (dark) tokens.DarkCanvas else Color.White).toArgb()
    private val stopColor = (if (dark) Color(0xFFFFB7B4) else Color(0xFFA52928)).toArgb()
    val touchSize get() = maxOf(dp(48), deviceDp(48))

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density * scale).roundToInt()

    fun deviceDp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    // A View added with WindowManager has no Window to set a local background-blur radius on.
    // Keep the highlight confined to the panel without dimming or blurring the game window.
    fun panelBackground(capsule: Boolean = false): GradientDrawable {
        val radius = if (capsule) 100 else 24
        if (!effects.glass) return rounded(surface.toArgb(), radius)
        val highlight = lerp(surface, accentColor, if (dark) .16f else .07f).copy(alpha = .97f)
        val base = surface.copy(alpha = .98f)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(highlight.toArgb(), base.toArgb())).apply {
            cornerRadius = dp(radius).toFloat()
            val rim = lerp(surface, if (dark) Color.White else accentColor, if (dark) .26f else .18f)
            setStroke(dp(1).coerceAtLeast(1), rim.toArgb())
        }
    }

    fun text(value: String, size: Float = 13f, strong: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size * scale
        setTextColor(if (strong) ink else muted)
        if (strong) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun button(
        label: String,
        selected: Boolean = false,
        primary: Boolean = false,
        stop: Boolean = false,
        capsule: Boolean = false,
        size: Float = 13f,
        action: () -> Unit,
    ) = Button(context).apply {
        text = label
        textSize = size * scale
        isAllCaps = false
        isSelected = selected
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = 0
        minWidth = 0
        minimumHeight = touchSize
        minimumWidth = 0

        val fill = when {
            stop && primary -> stopColor
            primary -> accent
            selected -> tint
            else -> canvas.toArgb()
        }
        val radius = if (capsule) 100 else 15
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        setTextColor(ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(muted, if (primary) onAccent else ink)))
        backgroundTintList = null
        background = StateListDrawable().apply {
            addState(disabled, rounded(if (selected) tint else surface.toArgb(), radius))
            addState(intArrayOf(), RippleDrawable(
                ColorStateList.valueOf((accent and 0x00ffffff) or 0x33000000),
                rounded(fill, radius), null,
            ))
        }
        setPadding(dp(5), dp(2), dp(5), dp(2))
        setOnClickListener { action() }
    }
}
