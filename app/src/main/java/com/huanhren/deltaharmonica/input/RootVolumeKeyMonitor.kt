package com.huanhren.deltaharmonica.input

/** Observes volume-down presses from built-in key drivers while a Root playback session is open. */
internal class RootVolumeKeyMonitor private constructor(private val processes: List<Process>) {
    fun close() = processes.forEach { runCatching { it.destroy() } }

    companion object {
        private val deviceLine = Regex("""add device \d+: (/dev/input/event\d+)""")
        private val volumeDown = Regex("""\bEV_KEY\s+KEY_VOLUMEDOWN\s+DOWN\b""")
        private val builtInDrivers = setOf("pmic_resin", "gpio-keys", "mtk-kpd", "qpnp_pon")

        fun start(onPress: () -> Unit): RootVolumeKeyMonitor? {
            val listing = runCatching {
                ProcessBuilder("/system/bin/getevent", "-lp").redirectErrorStream(true).start()
                    .inputStream.bufferedReader().use { it.readText() }
            }.getOrNull() ?: return null
            val devices = findDevices(listing)
            val readers = devices.mapNotNull { device ->
                runCatching {
                    ProcessBuilder("/system/bin/getevent", "-ql", device).redirectErrorStream(true).start()
                }.getOrNull()?.also { process ->
                    Thread {
                        runCatching {
                            process.inputStream.bufferedReader().forEachLine {
                                if (volumeDown.containsMatchIn(it)) onPress()
                            }
                        }
                    }.apply { isDaemon = true; start() }
                }
            }
            return readers.takeIf { it.isNotEmpty() }?.let(::RootVolumeKeyMonitor)
        }

        internal fun findDevices(listing: String): List<String> = deviceLine.findAll(listing).mapNotNull { device ->
            val end = deviceLine.find(listing, device.range.last + 1)?.range?.first ?: listing.length
            val section = listing.substring(device.range.last + 1, end)
            val name = section.lineSequence().firstOrNull { it.trimStart().startsWith("name:") }
                ?.substringAfter('"')?.substringBefore('"')
            device.groupValues[1].takeIf { name in builtInDrivers && "KEY_VOLUMEDOWN" in section }
        }.toList()
    }
}
