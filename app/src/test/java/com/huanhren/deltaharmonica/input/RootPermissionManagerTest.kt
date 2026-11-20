package com.huanhren.deltaharmonica.input

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPermissionManagerTest {
    private class Store : RootAuthorizationStore {
        var record: RootAuthorizationRecord? = null
        override suspend fun read() = record
        override suspend fun write(record: RootAuthorizationRecord) { this.record = record }
    }

    private class Fixture {
        val store = Store()
        val calls = mutableListOf<Pair<String, Long>>()
        var environment = RootEnvironment(true, RootManagerInstallation(RootManagerKind.MAGISK, 1L))
        var outcome: RootCommandResult = RootCommandResult.Success("0")
        fun manager(allowed: Boolean = true) = RootPermissionManager(
            runner = RootCommandRunner { command, timeout ->
                calls += command to timeout
                outcome
            },
            detect = { environment },
            store = store,
            now = { 42L },
            allowed = allowed,
        )
    }

    @Test fun coldStartsNeverRunSuAndFirstRootUseVerifiesTheCachedGrant() = runTest {
        val fixture = Fixture()
        val first = fixture.manager()
        first.initialize()
        assertEquals(RootAuthorizationState.NOT_AUTHORIZED, first.state.value.authorization)
        assertTrue(fixture.calls.isEmpty())
        assertTrue(first.ensureForUse().verified)
        assertEquals(42L, fixture.store.record?.lastAuthorizedAt)

        val second = fixture.manager()
        second.initialize()
        assertEquals(RootAuthorizationState.PREVIOUSLY_AUTHORIZED, second.state.value.authorization)
        assertFalse(second.state.value.verified)
        assertEquals(1, fixture.calls.size)
        assertTrue(second.ensureForUse().verified)
        assertTrue(second.ensureForUse().verified)
        assertEquals(listOf("id -u" to 30_000L, "id -u" to 4_000L), fixture.calls)
    }

    @Test fun deniedAuthorizationIsNotRepeatedByPlaybackOrAProcessRestart() = runTest {
        val fixture = Fixture().apply { outcome = RootCommandResult.Failure("Permission denied") }
        assertEquals(RootAuthorizationState.DENIED, fixture.manager().ensureForUse().authorization)

        val restarted = fixture.manager()
        restarted.initialize()
        assertEquals(RootAuthorizationState.DENIED, restarted.ensureForUse().authorization)
        assertEquals(1, fixture.calls.size)

        fixture.outcome = RootCommandResult.Success("0")
        assertTrue(restarted.request().verified)
        assertEquals(2, fixture.calls.size)
    }

    @Test fun usageDenialInvalidatesTheRememberedGrant() = runTest {
        val fixture = Fixture()
        val running = fixture.manager()
        assertTrue(running.request().verified)
        running.updateInput(RootInputReadiness.READY)
        assertEquals(RootInputReadiness.READY, fixture.store.record?.input)

        running.recordFailure(RootCommandResult.Failure("Permission denied"))
        assertEquals(RootAuthorizationState.REVOKED, running.state.value.authorization)
        assertFalse(running.state.value.verified)
        assertEquals(RootInputReadiness.UNAVAILABLE, running.state.value.input)
        assertEquals(42L, fixture.store.record?.lastAuthorizedAt)
        assertEquals(RootAuthorizationState.REVOKED, fixture.manager().ensureForUse().authorization)
        assertEquals(1, fixture.calls.size)
    }

    @Test fun managerUpdateInvalidatesCachedAuthorizationWithoutLaunchingSu() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.manager().request().verified)
        fixture.environment = fixture.environment.copy(manager = RootManagerInstallation(RootManagerKind.MAGISK, 2L))
        val restarted = fixture.manager()

        restarted.initialize()
        assertEquals(RootAuthorizationState.RECHECK_REQUIRED, restarted.ensureForUse().authorization)
        assertEquals(1, fixture.calls.size)
        assertEquals(2L, fixture.store.record?.environment?.manager?.updatedAt)
    }

    @Test fun rootNotDetectedAndIsolatedHostDoNotRunSuAutomatically() = runTest {
        val fixture = Fixture().apply { environment = RootEnvironment(false) }
        assertEquals(RootAuthorizationState.NOT_ROOTED, fixture.manager().ensureForUse().authorization)
        assertEquals(RootAuthorizationState.MANAGER_UNAVAILABLE, fixture.manager(allowed = false).request().authorization)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun disappearedSuInvalidatesTheCurrentGrantWithoutRetrying() = runTest {
        val fixture = Fixture()
        val manager = fixture.manager()
        assertTrue(manager.request().verified)
        fixture.environment = fixture.environment.copy(suVisible = false)

        manager.refreshEnvironment()
        assertEquals(RootAuthorizationState.RECHECK_REQUIRED, manager.ensureForUse().authorization)
        assertEquals(1, fixture.calls.size)
    }

    @Test fun failingHelperPreservesGrantHistoryButRequiresExplicitRetry() = runTest {
        val fixture = Fixture()
        val manager = fixture.manager()
        manager.request()
        manager.updateInput(RootInputReadiness.UNAVAILABLE, "helper unavailable")

        assertEquals(RootAuthorizationState.RECHECK_REQUIRED, manager.state.value.authorization)
        assertEquals(42L, manager.state.value.lastAuthorizedAt)
        assertFalse(manager.ensureForUse().verified)
        assertFalse(fixture.manager().ensureForUse().verified)
        assertEquals(1, fixture.calls.size)
    }

    @Test fun nonRootUidAndTimeoutAreTrackedSeparatelyFromDenial() = runTest {
        val fixture = Fixture().apply { outcome = RootCommandResult.Success("2000") }
        val manager = fixture.manager()
        assertEquals(RootAuthorizationState.ERROR, manager.request().authorization)
        assertFalse(manager.state.value.verified)

        fixture.outcome = RootCommandResult.TimedOut
        assertEquals(RootAuthorizationState.ERROR, manager.request().authorization)
        assertEquals(RootInputReadiness.UNAVAILABLE, fixture.store.record?.input)
    }
}
