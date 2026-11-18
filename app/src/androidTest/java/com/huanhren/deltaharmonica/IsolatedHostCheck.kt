package com.huanhren.deltaharmonica

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.os.ParcelFileDescriptor
import android.os.SystemClock

/** Defense in depth; installation preflight is still required before instrumentation starts. */
fun requireIsolatedTestHost() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.targetContext.packageName == "com.huanhren.deltaharmonica.instrumented") { "Refusing non-isolated target" }
    check(instrumentation.context.packageName == "com.huanhren.deltaharmonica.instrumented.test") { "Refusing non-isolated test APK" }
}

// MIUI can stall the relay activity used by ActivityScenario. Start only the
// explicit isolated component and observe it with the normal lifecycle monitor.
internal fun startIsolatedMainActivity() {
    requireIsolatedTestHost()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val command = "am start --user 0 -n com.huanhren.deltaharmonica.instrumented/com.huanhren.deltaharmonica.MainActivity"
    val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    check(!output.contains("Error:") && output.contains("com.huanhren.deltaharmonica.instrumented/")) { output }
    val deadline = SystemClock.elapsedRealtime() + 10_000
    do {
        var started = false
        instrumentation.runOnMainSync {
            started = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it is MainActivity && it.packageName == "com.huanhren.deltaharmonica.instrumented" }
        }
        if (started) return
        SystemClock.sleep(100)
    } while (SystemClock.elapsedRealtime() < deadline)
    error("Isolated MainActivity did not resume within 10 seconds")
}

/** Call on the main thread. */
internal fun resumedIsolatedActivity(): MainActivity =
    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .filterIsInstance<MainActivity>().single().also {
            check(it.packageName == "com.huanhren.deltaharmonica.instrumented")
        }

internal fun closeIsolatedActivities() {
    requireIsolatedTestHost()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
            .filterIsInstance<MainActivity>().distinct().filter { !it.isFinishing && !it.isDestroyed }
            .forEach { it.finish() }
    }
}

internal inline fun <T> withIsolatedActivity(block: () -> T): T {
    try {
        startIsolatedMainActivity()
        return block()
    } finally {
        closeIsolatedActivities()
    }
}
