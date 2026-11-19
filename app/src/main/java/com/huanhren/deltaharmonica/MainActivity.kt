package com.huanhren.deltaharmonica

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.ui.AppNavigation
import com.huanhren.deltaharmonica.ui.theme.DeltaTheme
import com.huanhren.deltaharmonica.ui.theme.StudioTheme
import com.huanhren.deltaharmonica.ui.withStudioLocale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(if (Build.VERSION.SDK_INT >= 33) newBase else newBase.withStudioLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DeltaHarmonicaApp
        val model = app.model
        enableEdgeToEdge()
        setContent {
            val appearance by app.preferences.appearance.collectAsStateWithLifecycle()
            val dark = appearance.theme.forcedDark ?: isSystemInDarkTheme()
            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            DeltaTheme(appearance) {
                val canvas = StudioTheme.colorScheme.background.toArgb()
                SideEffect { window.setBackgroundDrawable(ColorDrawable(canvas)) }
                AppNavigation(model, this)
            }
        }
    }
}
