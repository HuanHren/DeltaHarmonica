package com.huanhren.deltaharmonica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.ui.AppNavigation
import com.huanhren.deltaharmonica.ui.theme.DeltaTheme
import com.huanhren.deltaharmonica.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DeltaHarmonicaApp
        val model = app.model
        enableEdgeToEdge()
        setContent {
            val mode by app.preferences.theme.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && systemDark
            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            DeltaTheme(mode) { AppNavigation(model, this) }
        }
    }
}
