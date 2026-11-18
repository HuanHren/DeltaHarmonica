package com.huanhren.deltaharmonica.ui

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.huanhren.deltaharmonica.input.DisplaySnapshot
import com.huanhren.deltaharmonica.input.displaySnapshot
import com.huanhren.deltaharmonica.model.ScreenPoint

data class CanvasGeometry(val display: DisplaySnapshot, val origin: ScreenPoint, val size: IntSize)

@Composable
fun ImmersiveScreen(activity: Activity) {
    DisposableEffect(activity) {
        val window = activity.window
        val originalAttributes = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = originalAttributes.layoutInDisplayCutoutMode
            }
        }
    }
}

@Composable
fun ScreenCanvas(activity: Activity, modifier: Modifier = Modifier,
                 content: @Composable BoxScope.(CanvasGeometry?) -> Unit) {
    // Reading configuration ensures rotation/multi-window changes re-read the display metrics.
    val configuration = LocalConfiguration.current
    val display = remember(configuration, activity) { displaySnapshot(activity) }
    var geometry by remember(display) { mutableStateOf<CanvasGeometry?>(null) }
    Box(modifier.fillMaxSize().background(Color(0xFF111C2C)).onGloballyPositioned {
        val origin = it.localToScreen(Offset.Zero)
        if (origin.x.isFinite() && origin.y.isFinite())
            geometry = CanvasGeometry(displaySnapshot(activity), ScreenPoint(origin.x, origin.y), it.size)
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val gridColor = Color.White.copy(alpha = 0.07f)
            for (i in 1..9) {
                drawLine(gridColor, Offset(size.width * i / 10f, 0f), Offset(size.width * i / 10f, size.height))
                drawLine(gridColor, Offset(0f, size.height * i / 10f), Offset(size.width, size.height * i / 10f))
            }
        }
        content(geometry)
    }
}
