package com.huanhren.deltaharmonica.input

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RootRecoveryProbeTest {
    private val helper = RootHelperIdentity(77, 1234)
    private val record = RootReleaseRecord("session-a", helper)

    @Test fun helperThatStillOwnsItsPidBlocksEvenWhenDispatcherWouldBeIdle() = runTest {
        val probe = RootRecoveryProbe(RootCommandRunner { command, _ ->
            assertTrue(command.contains("/proc/77/stat"))
            RootCommandResult.Success(procStat(1234))
        })
        assertEquals(RootRecoveryResult.HELPER_ALIVE, probe.verify(record))
    }

    @Test fun reusedPidAndTwoIdleSnapshotsCanOpenRecoveryGate() = runTest {
        var snapshots = 0
        val probe = RootRecoveryProbe(RootCommandRunner { command, _ ->
            if (command.contains("/proc/77/stat")) RootCommandResult.Success(procStat(1235))
            else RootCommandResult.Success(idle.also { snapshots++ })
        })
        assertEquals(RootRecoveryResult.CONFIRMED, probe.verify(record))
        assertEquals(2, snapshots)
    }

    @Test fun pendingInputOrAnIncompleteDumpKeepsGateClosed() = runTest {
        for (dump in listOf(
            idle.replace("PendingEvent: <none>", "PendingEvent:"),
            idle.replace("InboundQueue: <empty>", "InboundQueue: length=1"),
            idle.replace("CommandQueue: <empty>", "CommandQueue: size=1"),
            idle.replace("Connections:", "Connections:\n    WaitQueue: length=2"),
            idle.replace("Connections:", "Connection entries omitted"),
            idle.substringBefore("DELTA_SNAPSHOT_END"),
            idle.substringBefore("CursorStatesByDisplay:"),
            idle.replace("<no displays touched>", "\n  0 : Windows: app"),
            "Input Dispatcher State at time of last ANR:\n$idle".replace("State:\n", "State from ANR:\n"),
        )) {
            val probe = RootRecoveryProbe(RootCommandRunner { command, _ ->
                RootCommandResult.Success(if (command.contains("/proc/77/stat")) "DELTA_GONE 77" else dump)
            })
            assertEquals(RootRecoveryResult.DISPATCHER_UNCONFIRMED, probe.verify(record))
        }
    }

    @Test fun legacyMarkerNeedsPositiveHelperInventoryAndIdleDispatcher() = runTest {
        var inventory = "DELTA_PRESENT"
        val probe = RootRecoveryProbe(RootCommandRunner { command, _ ->
            RootCommandResult.Success(if (command.contains("ps -Aw")) inventory else idle)
        })
        assertEquals(RootRecoveryResult.HELPER_ALIVE, probe.verify(RootReleaseRecord(null, null)))
        inventory = "DELTA_NONE"
        assertEquals(RootRecoveryResult.CONFIRMED, probe.verify(RootReleaseRecord(null, null)))
        inventory = ""
        assertEquals(RootRecoveryResult.HELPER_UNKNOWN, probe.verify(RootReleaseRecord(null, null)))
    }

    @Test fun malformedPidIdentityIsNotProofOfExit() = runTest {
        val probe = RootRecoveryProbe(RootCommandRunner { _, _ -> RootCommandResult.Success("77 (bad) Z") })
        assertEquals(RootRecoveryResult.HELPER_UNKNOWN, probe.verify(record))
    }

    private fun procStat(startTicks: Long): String =
        "77 (process with ) in name) S " + List(18) { "0" }.joinToString(" ") + " $startTicks 1 2"

    companion object {
        val idle = """
            Input Dispatcher State:
              TouchStatesByDisplay: <no displays touched>
              CursorStatesByDisplay: <no displays touched by cursor>
            Connections:
              PendingEvent: <none>
              InboundQueue: <empty>
              CommandQueue: <empty>
            DELTA_SNAPSHOT_END
        """.trimIndent()
    }
}
