package com.huanhren.deltaharmonica.input

internal enum class RootSessionPhase {
    IDLE, PREPARING, ACTIVE, CLEANING, RECOVERY_REQUIRED, RECOVERING,
}

internal data class RootSessionState(
    val id: String? = null,
    val phase: RootSessionPhase = RootSessionPhase.IDLE,
    val detail: String? = null,
    val helper: RootHelperIdentity? = null,
    val changedAtMs: Long = System.currentTimeMillis(),
    val recoveryAttempts: Int = 0,
) {
    val recoveryRequired: Boolean
        get() = phase == RootSessionPhase.RECOVERY_REQUIRED || phase == RootSessionPhase.RECOVERING

    fun diagnosticLine(): String =
        "session=${id ?: "legacy"} phase=$phase helperPid=${helper?.pid}" +
            " helperStartTicks=${helper?.startTicks} timestampMs=$changedAtMs" +
            " recoveryAttempts=$recoveryAttempts detail=${detail ?: "-"}"
}

/** Keeps transition and summary data available even after logcat rolls over. */
internal class RootSessionDiagnostics {
    private val sessions = linkedMapOf<String, ArrayDeque<String>>()

    @Synchronized fun transition(state: RootSessionState) {
        val id = state.id ?: "legacy"
        val lines = sessions.getOrPut(id) {
            if (sessions.size >= 8) sessions.remove(sessions.keys.first())
            ArrayDeque()
        }
        append(lines, state.diagnosticLine())
    }

    @Synchronized fun record(id: String, summary: String) {
        sessions[id]?.let { append(it, summary) }
    }

    @Synchronized fun export(): String = sessions.entries.joinToString("\n\n") { (id, lines) ->
        "Root session $id\n" + lines.joinToString("\n")
    }

    private fun append(lines: ArrayDeque<String>, summary: String) {
        if (lines.size >= 24) lines.removeFirst()
        lines.addLast(summary.take(2_048))
    }
}
