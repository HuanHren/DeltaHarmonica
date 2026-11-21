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
    private val focusRecord = Regex(
        """^[ \t]*mCurrentFocus=Window\{([0-9A-Fa-f]+)[ \t]+u(\d+)[ \t]+([^\r\n}]+)\}[ \t]*$""",
        RegexOption.MULTILINE,
    )
    private val component = Regex("""([A-Za-z0-9_.]+)/[^\r\n}]+""")
    private val noFocus = Regex("""^[ \t]*mCurrentFocus=null[ \t]*$""", RegexOption.MULTILINE)

    internal fun parseProbe(output: String): Probe? {
        val records = focusRecord.findAll(output).take(2).toList()
        if (records.size > 1) return Probe(null)
        val record = records.singleOrNull() ?: return if (noFocus.containsMatchIn(output)) Probe(null) else null
        val (token, user, title) = record.destructured
        val packageName = component.matchEntire(title)?.groupValues?.get(1) ?: return Probe(null)
        return Probe(Identity(packageName, token.lowercase(), user.toIntOrNull() ?: return Probe(null)))
    }

    internal fun parseIdentity(output: String): Identity? = parseProbe(output)?.window
    fun parse(output: String): String? = parseIdentity(output)?.packageName
    fun isFreshGame(packageName: String?, observedAtMs: Long, nowMs: Long): Boolean =
        packageName == GAME_PACKAGE && nowMs >= observedAtMs && nowMs - observedAtMs <= 600L
}
