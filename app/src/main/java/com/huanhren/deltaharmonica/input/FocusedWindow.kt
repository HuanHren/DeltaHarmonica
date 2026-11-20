package com.huanhren.deltaharmonica.input

/** Parses only the system's current-focus record, never a background task or title. */
object FocusedWindow {
    const val GAME_PACKAGE = "com.tencent.tmgp.dfm"
    internal data class Identity(val packageName: String, val token: String, val userId: Int)
    private val focus = Regex(
        """^[ \t]*mCurrentFocus=Window\{([0-9A-Fa-f]+)[ \t]+u(\d+)[ \t]+([A-Za-z0-9_.]+)/[^\r\n}]+\}[ \t]*$""",
        RegexOption.MULTILINE,
    )

    internal fun parseIdentity(output: String): Identity? {
        val match = focus.findAll(output).singleOrNull() ?: return null
        val (token, user, packageName) = match.destructured
        return Identity(packageName, token.lowercase(), user.toIntOrNull() ?: return null)
    }

    fun parse(output: String): String? = parseIdentity(output)?.packageName
    fun isFreshGame(packageName: String?, observedAtMs: Long, nowMs: Long): Boolean =
        packageName == GAME_PACKAGE && nowMs >= observedAtMs && nowMs - observedAtMs <= 600L
}
