package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.model.ScreenSize
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

class RootBackendTest {
    private class Runner(var result: RootCommandResult) : RootCommandRunner {
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, timeoutMs: Long): RootCommandResult {
            commands += command
            return result
        }
    }

    @Test fun rootIsNotAdvertisedAvailableBeforeExplicitCheck() = runTest {
        val runner = Runner(RootCommandResult.Success("0\n"))
        val backend = RootBackend(runner) { ScreenSize(100, 200) }
        assertFalse(backend.status().available)
        assertTrue(backend.tap(ScreenPoint(1f, 2f)) is TapResult.Unavailable)
        assertTrue(runner.commands.isEmpty())
    }

    @Test fun successfulProbeAndTapUseValidatedIntegerCoordinates() = runTest {
        val runner = Runner(RootCommandResult.Success("0\n"))
        val backend = RootBackend(runner) { ScreenSize(100, 200) }
        assertTrue(backend.checkAccess().available)
        assertEquals(TapResult.Completed, backend.tap(ScreenPoint(12.8f, 25.2f)))
        assertEquals(listOf("id -u", "input tap 13 25"), runner.commands)
    }

    @Test fun nonRootOrDeniedProbeRemainsUnavailable() = runTest {
        val runner = Runner(RootCommandResult.Success("2000\n"))
        val backend = RootBackend(runner) { ScreenSize(100, 200) }
        assertFalse(backend.checkAccess().available)
        runner.result = RootCommandResult.Failure("denied")
        assertFalse(backend.checkAccess().available)
        assertTrue(backend.tap(ScreenPoint(12f, 25f)) is TapResult.Unavailable)
        assertEquals(2, runner.commands.size)
    }

    @Test fun outOfBoundsIsRejectedWithoutExecutingCommand() = runTest {
        val runner = Runner(RootCommandResult.Success("0"))
        val backend = RootBackend(runner) { ScreenSize(100, 200) }
        backend.checkAccess()
        assertTrue(backend.tap(ScreenPoint(100f, 20f)) is TapResult.InvalidPoint)
        assertEquals(listOf("id -u"), runner.commands)
    }

    @Test fun commandFailureAndTimeoutAreReported() = runTest {
        val runner = Runner(RootCommandResult.Success("0"))
        val backend = RootBackend(runner) { ScreenSize(100, 200) }
        backend.checkAccess()
        runner.result = RootCommandResult.Failure("denied")
        assertTrue(backend.tap(ScreenPoint(12f, 25f)) is TapResult.Unavailable)
        assertFalse(backend.status().available)
        runner.result = RootCommandResult.Success("0")
        backend.checkAccess()
        runner.result = RootCommandResult.TimedOut
        assertEquals(TapResult.TimedOut, backend.tap(ScreenPoint(12f, 25f)))
    }
    @Test fun focusQueryCanProceedWhileInputCommandIsRunning() = runTest {
        val release = CompletableDeferred<Unit>()
        val runner = RootCommandRunner { command, _ ->
            when {
                command == "id -u" -> RootCommandResult.Success("0")
                command.startsWith("input tap") -> { release.await(); RootCommandResult.Success("") }
                else -> RootCommandResult.Success("mCurrentFocus=Window{123 u0 com.game.example/.Main}")
            }
        }
        val backend = RootBackend(runner) { ScreenSize(100, 200) }
        backend.checkAccess()
        val tap = async { backend.tap(ScreenPoint(12f,25f)) }
        runCurrent()
        assertEquals("com.game.example", backend.focusedPackage())
        assertEquals(TapResult.Busy, backend.tap(ScreenPoint(13f,25f)))
        release.complete(Unit)
        assertEquals(TapResult.Completed, tap.await())
    }
}
