package com.huanhren.deltaharmonica.input

/** Parses only the system's current-focus record, never a background task or title. */
object FocusedWindow {
    const val GAME_PACKAGE = "com.tencent.tmgp.dfm"
    private val focus = Regex("(?:^|\\s)mCurrentFocus=Window\\{[^\\n]*?\\bu\\d+\\s+([A-Za-z0-9_.]+)/", RegexOption.MULTILINE)
    fun parse(output: String): String? = focus.find(output)?.groupValues?.get(1)
    fun isFreshGame(packageName: String?, observedAtMs: Long, nowMs: Long): Boolean =
        packageName == GAME_PACKAGE && nowMs >= observedAtMs && nowMs - observedAtMs <= 600L
}
