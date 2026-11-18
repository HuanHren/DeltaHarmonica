package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenSize
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RootAccessibilitySettingsTest {
    private val component = "com.huanhren.deltaharmonica/com.huanhren.deltaharmonica.accessibility.TouchAccessibilityService"
    private class Runner(var result: RootCommandResult = RootCommandResult.Success("0")) : RootCommandRunner {
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, timeoutMs: Long): RootCommandResult {
            commands += command
            return result
        }
    }

    @Test fun requiresRootBeforeChangingSystemSettings() = runTest {
        val runner = Runner()
        val root = RootBackend(runner) { ScreenSize(100, 200) }
        assertTrue(root.enableAccessibility(null, component, false, true) is RootCommandResult.Failure)
        assertTrue(runner.commands.isEmpty())
    }

    @Test fun preservesOtherServicesAndAvoidsDuplicateComponent() {
        val short = "com.huanhren.deltaharmonica/.accessibility.TouchAccessibilityService"
        assertEquals("a.b/.Reader:$short", mergeAccessibilityServices("a.b/.Reader:$short", component))
        assertEquals("a.b/.Reader:$component", mergeAccessibilityServices("a.b/.Reader", component))
        assertEquals(component, mergeAccessibilityServices(null, component))
    }

    @Test fun quotesSystemValuesAndEnablesOnlyAfterRootCheck() = runTest {
        val runner = Runner()
        val root = RootBackend(runner) { ScreenSize(100, 200) }
        root.checkAccess()
        assertTrue(root.enableAccessibility("a.b/.R'eader", component, false, true) is RootCommandResult.Success)
        assertEquals(2, runner.commands.size)
        assertTrue(runner.commands.last().contains("a.b/.R'\\''eader:$component"))
        assertTrue(runner.commands.last().contains("accessibility_enabled 1"))
    }

    @Test fun failedEnableIsReported() = runTest {
        val runner = Runner()
        val root = RootBackend(runner) { ScreenSize(100, 200) }
        root.checkAccess()
        runner.result = RootCommandResult.Failure("denied")
        assertEquals(RootCommandResult.Failure("denied"), root.enableAccessibility(null, component, false, true))
    }

    @Test fun secondaryUserDoesNotChangeAnotherUsersSettings() = runTest {
        val runner = Runner()
        val root = RootBackend(runner) { ScreenSize(100, 200) }
        root.checkAccess()
        assertTrue(root.enableAccessibility("other/.Service", component, false, false) is RootCommandResult.Failure)
        assertEquals(listOf("id -u"), runner.commands)
    }
}
