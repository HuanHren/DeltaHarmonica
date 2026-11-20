package com.huanhren.deltaharmonica.input

import android.content.Context
import android.provider.Settings

/** An unacknowledged helper can outlive the app process. A device reboot clears dispatcher state. */
internal class RootReleaseGuard(context: Context) {
    private val preferences = context.getSharedPreferences("root_touch_release", Context.MODE_PRIVATE)
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun isPending(): Boolean {
        if (!preferences.contains("session_boot")) return false
        val recorded = preferences.getInt("session_boot", -1)
        return boot < 0 || recorded < 0 || boot == recorded
    }

    fun begin(): Boolean = preferences.edit().putInt("session_boot", boot).commit()
    fun confirm(): Boolean = preferences.edit().remove("session_boot").commit()
}
