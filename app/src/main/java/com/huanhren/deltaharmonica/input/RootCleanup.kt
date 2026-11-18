package com.huanhren.deltaharmonica.input

import kotlinx.coroutines.delay

internal enum class RootCleanup(val confirmed: Boolean) {
    ACKNOWLEDGED(true), REJECTED(false), NO_ACK(false), OBSERVED_IDLE(true),
}

/** An idle dispatcher is useful only after the helper has finished all its work. */
internal suspend fun confirmRootCleanup(
    outcome: RootCleanup,
    readDispatcher: suspend () -> RootCommandResult,
): RootCleanup {
    if (outcome != RootCleanup.REJECTED) return outcome
    repeat(3) { attempt ->
        if (attempt > 0) delay(100)
        val result = readDispatcher()
        if (result is RootCommandResult.Success && dispatcherHasNoTouches(result.output)) {
            return RootCleanup.OBSERVED_IDLE
        }
    }
    return outcome
}

private val emptyDisplay = Regex("""\d+\s*:\s*Windows:\s*<none>""")

private fun dispatcherHasNoTouches(output: String): Boolean {
    val lines = output.trim().lines().map(String::trim)
    val header = lines.first()
    val hasCursorFooter = lines.last().startsWith("CursorStatesByDisplay:")
    if (header == "TouchStatesByDisplay: <no displays touched>") {
        return lines.size == 1 || (lines.size == 2 && hasCursorFooter)
    }
    if (header != "TouchStatesByDisplay:" || !hasCursorFooter || lines.size < 3) return false
    return lines.subList(1, lines.lastIndex).all(emptyDisplay::matches)
}
