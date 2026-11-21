package com.huanhren.deltaharmonica.input

import kotlinx.coroutines.delay

internal enum class RootRecoveryResult {
    CONFIRMED, HELPER_ALIVE, HELPER_UNKNOWN, DISPATCHER_UNCONFIRMED, JOURNAL_FAILURE,
}

/** Only reads process and dispatcher state. Never sends a gesture to the current window. */
internal class RootRecoveryProbe(private val runner: RootCommandRunner) {
    suspend fun verify(record: RootReleaseRecord): RootRecoveryResult {
        repeat(2) { sample ->
            if (sample != 0) delay(100)
            val helper = record.helper
            val exit = when {
                helper != null -> readProcessExit(helper)
                record.sessionId == null -> readLegacyExit()
                else -> RootRecoveryResult.HELPER_UNKNOWN
            }
            if (exit != RootRecoveryResult.CONFIRMED) return exit
            val snapshot = read(dispatcherCommand)
            if (snapshot == null || !dispatcherIsQuiescent(snapshot)) {
                return RootRecoveryResult.DISPATCHER_UNCONFIRMED
            }
        }
        return RootRecoveryResult.CONFIRMED
    }

    private suspend fun readProcessExit(helper: RootHelperIdentity): RootRecoveryResult {
        val path = "/proc/${helper.pid}"
        val response = read("""
            $rootCheck
            if stat=$(cat $path/stat 2>/dev/null); then
                printf '%s\n' "${'$'}stat"
            elif [ ! -d $path ]; then
                printf 'DELTA_GONE %s\n' '${helper.pid}'
            else
                exit 1
            fi
        """.trimIndent()) ?: return RootRecoveryResult.HELPER_UNKNOWN
        if (response == "DELTA_GONE ${helper.pid}") return RootRecoveryResult.CONFIRMED
        val observed = RootSequenceProtocol.decodeProcessStat(response)
            ?.takeIf { it.pid == helper.pid } ?: return RootRecoveryResult.HELPER_UNKNOWN
        return if (observed == helper) RootRecoveryResult.HELPER_ALIVE else RootRecoveryResult.CONFIRMED
    }

    private suspend fun readLegacyExit(): RootRecoveryResult = when (read(legacyCommand)) {
        "DELTA_NONE" -> RootRecoveryResult.CONFIRMED
        "DELTA_PRESENT" -> RootRecoveryResult.HELPER_ALIVE
        else -> RootRecoveryResult.HELPER_UNKNOWN
    }

    private suspend fun read(command: String): String? =
        (runner.run(command, 1_200) as? RootCommandResult.Success)?.output?.trim()

    private companion object {
        val rootCheck = """[ "$(id -u)" = 0 ] && [ -r /proc/1/stat ] || exit 1"""

        // app_process retains its argv when --nice-name is absent. Split the class name
        // in the shell command so the recovery process itself does not match the scan.
        val legacyCommand = """
            $rootCheck
            snapshot=$(ps -Aw -o PID,CMDLINE) || exit 1
            printf '%s\n' "${'$'}snapshot" | awk '
                NR == 1 { if ($1 != "PID" || $2 != "CMDLINE") exit 2; next }
                $1 == "1" && NF >= 2 { init = 1 }
                index($0, "com.huanhren.deltaharmonica.input.RootTouch" "Main") { found = 1 }
                END {
                    if (!init) exit 2
                    if (found) print "DELTA_PRESENT"; else print "DELTA_NONE"
                }'
        """.trimIndent()

        val dispatcherCommand = """
            snapshot=$(dumpsys input) || exit 1
            printf '%s\n' "${'$'}snapshot" | awk '
                /^Input Dispatcher State at time of last ANR:/ { exit }
                /^Input Dispatcher State:$/ { live = 1; print; next }
                !live { next }
                /^[[:space:]]+TouchStatesByDisplay:/ { touches = 1 }
                touches { print }
                /^[[:space:]]+CursorStatesByDisplay:/ { touches = 0; next }
                /^[[:space:]]*Connections:/ { print }
                !touches && /^[[:space:]]+(PendingEvent|InboundQueue|CommandQueue|OutboundQueue|WaitQueue):/ { print }
                END { if (live && !touches) print "DELTA_SNAPSHOT_END" }
            '
        """.trimIndent()
    }
}

internal fun dispatcherIsQuiescent(output: String): Boolean {
    val lines = output.trim().lines().map(String::trim)
    if (lines.first() != "Input Dispatcher State:" || lines.last() != "DELTA_SNAPSHOT_END" ||
        lines.count { it == lines.first() } != 1
    ) return false
    val connections = lines.singleOrNull { it.startsWith("Connections:") }
    if (connections != "Connections:" && connections != "Connections: <none>") return false
    for (entry in listOf("PendingEvent: <none>", "InboundQueue: <empty>", "CommandQueue: <empty>")) {
        if (lines.filter { it.startsWith(entry.substringBefore(' ')) } != listOf(entry)) return false
    }
    if (lines.any {
        (it.startsWith("OutboundQueue:") || it.startsWith("WaitQueue:")) &&
            it.substringAfter(':').trim() !in listOf("<empty>", "length=0")
    }) return false
    val touch = lines.indices.filter { lines[it].startsWith("TouchStatesByDisplay:") }.singleOrNull() ?: return false
    val cursor = lines.indices.filter { lines[it].startsWith("CursorStatesByDisplay:") }.singleOrNull() ?: return false
    return touch < cursor && dispatcherHasNoTouches(lines.subList(touch, cursor + 1))
}
