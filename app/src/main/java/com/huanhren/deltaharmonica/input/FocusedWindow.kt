package com.huanhren.deltaharmonica.input

/** Parses only the system's current-focus record, never a background task or title. */
object FocusedWindow {
    const val GAME_PACKAGE = "com.tencent.tmgp.dfm"
    internal data class Identity(val packageName: String, val token: String, val userId: Int)
    internal data class Probe(val window: Identity?)
    internal data class Observation(
        val packageName: String? = null,
        val window: Identity? = null,
        val observedAtMs: Long = 0L,
    ) {
        fun freshGame(nowMs: Long) = isFreshGame(packageName, observedAtMs, nowMs)

        fun updateRoot(probe: Probe?, nowMs: Long): Observation =
            if (probe == null) this else Observation(probe.window?.packageName, probe.window, nowMs)
    }
    private val focus = Regex(
        """^[ \t]*mCurrentFocus=Window\{([0-9A-Fa-f]+)[ \t]+u(\d+)[ \t]+([A-Za-z0-9_.]+)/[^\r\n}]+\}[ \t]*$""",
        RegexOption.MULTILINE,
    )
    private val noFocus = Regex("""^[ \t]*mCurrentFocus=null[ \t]*$""", RegexOption.MULTILINE)

    internal fun parseProbe(output: String): Probe? {
        val matches = focus.findAll(output).take(2).toList()
        if (matches.size > 1) return Probe(null)
        val match = matches.singleOrNull() ?: return if (noFocus.containsMatchIn(output)) Probe(null) else null
        val (token, user, packageName) = match.destructured
        return Probe(Identity(packageName, token.lowercase(), user.toIntOrNull() ?: return null))
    }

    internal fun parseIdentity(output: String): Identity? = parseProbe(output)?.window
    fun parse(output: String): String? = parseIdentity(output)?.packageName
    fun isFreshGame(packageName: String?, observedAtMs: Long, nowMs: Long): Boolean =
        packageName == GAME_PACKAGE && nowMs >= observedAtMs && nowMs - observedAtMs <= 600L
}
