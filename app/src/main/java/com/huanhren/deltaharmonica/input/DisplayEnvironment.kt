package com.huanhren.deltaharmonica.input

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.huanhren.deltaharmonica.model.ScreenSize

data class DisplaySnapshot(val size: ScreenSize, val rotation: Int, val supported: Boolean)

@Suppress("DEPRECATION")
fun displaySnapshot(context: Context): DisplaySnapshot {
    val manager = context.getSystemService(WindowManager::class.java)
    val display = manager.defaultDisplay
    val size = if (Build.VERSION.SDK_INT >= 30) {
        manager.maximumWindowMetrics.bounds.let { ScreenSize(it.width(), it.height()) }
    } else {
        Point().also(display::getRealSize).let { ScreenSize(it.x, it.y) }
    }
    val activity = context as? Activity
    return DisplaySnapshot(size, display.rotation,
        display.displayId == Display.DEFAULT_DISPLAY &&
            (activity == null || (!activity.isInMultiWindowMode && !activity.isInPictureInPictureMode)))
}
