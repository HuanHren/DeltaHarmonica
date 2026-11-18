package com.huanhren.deltaharmonica

import android.app.UiAutomation
import androidx.test.runner.AndroidJUnitRunner

/** UiAutomation otherwise disconnects the very service this suite needs to exercise. */
class AccessibilityTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        check(targetContext.packageName == "com.huanhren.deltaharmonica.instrumented") { "Refusing non-isolated instrumentation target" }
        check(context.packageName == "com.huanhren.deltaharmonica.instrumented.test") { "Refusing non-isolated instrumentation APK" }
        super.onStart()
    }
    override fun getUiAutomation(): UiAutomation = getUiAutomation(0)
    override fun getUiAutomation(flags: Int): UiAutomation =
        super.getUiAutomation(flags or UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
}
