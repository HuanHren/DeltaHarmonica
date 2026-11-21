package com.huanhren.deltaharmonica.input

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.huanhren.deltaharmonica.model.ScreenSize

class FocusedWindowTest {
    @Test fun parsesOnlyActualFocusedActivity() {
        assertEquals("com.tencent.tmgp.dfm",FocusedWindow.parse("  mCurrentFocus=Window{fbd2235 u0 com.tencent.tmgp.dfm/com.epicgames.ue4.GameActivity}"))
        assertEquals("com.huanhren.deltaharmonica",FocusedWindow.parse("mCurrentFocus=Window{1 u0 com.huanhren.deltaharmonica/.MainActivity}"))
        assertNull(FocusedWindow.parse("mCurrentFocus=null"))
        assertNull(FocusedWindow.parse("notCurrentFocus u0 com.tencent.tmgp.dfm/Fake"))
    }
    @Test fun staleSampleNeverAuthorizesTouch() {
        assertTrue(FocusedWindow.isFreshGame("com.tencent.tmgp.dfm",1000,1499))
        assertFalse(FocusedWindow.isFreshGame("com.tencent.tmgp.dfm",1000,1601))
        assertFalse(FocusedWindow.isFreshGame("com.other",1000,1100))
        assertFalse(FocusedWindow.isFreshGame(null,1000,1100))
    }

    @Test fun missingProbeKeepsOriginalObservationButAnExplicitFocusLossStopsImmediately() {
        val game = FocusedWindow.parseProbe("mCurrentFocus=Window{abc u0 com.tencent.tmgp.dfm/.GameActivity}")
        val starting = FocusedWindow.Observation().updateRoot(game, 1000)
        val missing = starting.updateRoot(FocusedWindow.parseProbe("unexpected reply"), 1200)
        assertSame(starting, missing)
        assertTrue(missing.freshGame(1400))
        assertFalse(missing.freshGame(1601))
        val lost = missing.updateRoot(FocusedWindow.parseProbe("mCurrentFocus=null"), 1400)
        assertFalse(lost.freshGame(1400))
        assertNull(lost.window)
    }

    @Test fun timingOutAReadOnlyFocusProbeDoesNotDiscardRootAuthorization() = runBlocking {
        val backend = RootBackend(RootCommandRunner { command, _ ->
            if (command == "id -u") RootCommandResult.Success("0") else RootCommandResult.TimedOut
        }) { ScreenSize(2670, 1200) }
        assertTrue(backend.checkAccess().available)
        assertNull(backend.observeFocusedWindow())
        assertTrue(backend.status().available)
    }

    @Test fun samePackageWindowsHaveDifferentIdentities() {
        val first = "  mCurrentFocus=Window{abc u0 com.tencent.tmgp.dfm/.GameActivity}"
        val second = "  mCurrentFocus=Window{def u0 com.tencent.tmgp.dfm/.GameActivity}"
        val observed = FocusedWindow.parseIdentity(first)
        assertEquals(FocusedWindow.Identity("com.tencent.tmgp.dfm", "abc", 0), observed)
        assertNotEquals(observed, FocusedWindow.parseIdentity(second))
        assertNull(FocusedWindow.parseIdentity("$first\n$second"))
        assertEquals(observed, FocusedWindow.parseIdentity("$first\nmCurrentFocus=null"))
    }

    @Test fun aTitleOnlySystemWindowImmediatelyInvalidatesCachedGameFocus() {
        val game = "mCurrentFocus=Window{abc u0 com.tencent.tmgp.dfm/.GameActivity}"
        val shade = "mCurrentFocus=Window{32f73df u0 NotificationShade}"
        val starting = FocusedWindow.Observation().updateRoot(FocusedWindow.parseProbe(game), 1000)
        for (sample in listOf(shade, "$game\n$shade")) {
            val observed = starting.updateRoot(FocusedWindow.parseProbe(sample), 1010)
            assertFalse(observed.freshGame(1010))
            assertNull(observed.window)
        }
    }
    @Test fun rootFocusQueryIsReadOnlyAndRequiresAuthorization() = runBlocking {
        val commands=mutableListOf<String>()
        val runner=RootCommandRunner { command,_ ->
            commands.add(command)
            if(command == "id -u") RootCommandResult.Success("0")
            else RootCommandResult.Success("mCurrentFocus=Window{1 u0 com.tencent.tmgp.dfm/GameActivity}")
        }
        val backend=RootBackend(runner) { ScreenSize(2670,1200) }
        assertNull(backend.focusedPackage())
        assertTrue(commands.isEmpty())
        backend.checkAccess()
        assertEquals("com.tencent.tmgp.dfm",backend.focusedPackage())
        assertEquals(listOf("id -u","dumpsys window | grep 'mCurrentFocus='"),commands)
    }
}
