package com.huanhren.deltaharmonica.input

internal enum class RootCleanup(val confirmed: Boolean) {
    ACKNOWLEDGED(true), REJECTED(false), NO_ACK(false), OBSERVED_IDLE(true), JOURNAL_FAILURE(false),
}

private val emptyDisplay = Regex("""\d+\s*:\s*Windows:\s*<none>""")

internal fun dispatcherHasNoTouches(lines: List<String>): Boolean {
    if (lines.size < 2 || !lines.last().startsWith("CursorStatesByDisplay:")) return false
    val header = lines.first()
    if (header == "TouchStatesByDisplay: <no displays touched>") return lines.size == 2
    if (header != "TouchStatesByDisplay:" || lines.size < 3) return false
    return lines.subList(1, lines.lastIndex).all(emptyDisplay::matches)
}
