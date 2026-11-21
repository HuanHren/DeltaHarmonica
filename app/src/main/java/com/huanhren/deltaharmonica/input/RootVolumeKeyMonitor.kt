package com.huanhren.deltaharmonica.input

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Observes volume-down presses from built-in key drivers while a Root playback session is open. */
internal class RootVolumeKeyMonitor private constructor(private val readers: List<Reader>) {
    private data class Reader(val process: Process, val thread: Thread)

    fun close() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150)
        readers.forEach { it.process.requestTermination() }
        try {
            for (reader in readers) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) break
                reader.thread.join(remaining)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val deviceLine = Regex("""add device \d+: (/dev/input/event\d+)""")
        private val volumeDown = Regex("""\bEV_KEY\s+KEY_VOLUMEDOWN\s+DOWN\b""")
        private val builtInDrivers = setOf("pmic_resin", "gpio-keys", "mtk-kpd", "qpnp_pon")

        fun start(onPress: () -> Unit): RootVolumeKeyMonitor? {
            val listing = readDeviceListing() ?: return null
            val devices = findDevices(listing)
            val readers = devices.mapNotNull { device ->
                if (Thread.currentThread().isInterrupted) return@mapNotNull null
                runCatching {
                    ProcessBuilder("/system/bin/getevent", "-ql", device).redirectErrorStream(true).start()
                }.getOrNull()?.let { process ->
                    val reader = Thread({
                        runCatching {
                            process.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { if (volumeDown.containsMatchIn(it)) onPress() }
                            }
                        }
                    }, "DeltaRootVolume").apply { isDaemon = true; start() }
                    Reader(process, reader)
                }
            }
            return readers.takeIf { it.isNotEmpty() }?.let(::RootVolumeKeyMonitor)
        }

        private fun readDeviceListing(): String? = runCatching {
            val process = ProcessBuilder("/system/bin/getevent", "-lp").redirectErrorStream(true).start()
            val listing = AtomicReference<String?>()
            val reader = Thread({
                listing.set(runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrNull())
            }, "DeltaRootVolumeList").apply { isDaemon = true; start() }
            try {
                if (process.waitFor(600L, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
                    reader.join(100L)
                    listing.get()
                } else null
            } finally {
                if (process.isAlive || reader.isAlive) process.requestTermination()
            }
        }.getOrNull()

        internal fun findDevices(listing: String): List<String> = deviceLine.findAll(listing).mapNotNull { device ->
            val end = deviceLine.find(listing, device.range.last + 1)?.range?.first ?: listing.length
            val section = listing.substring(device.range.last + 1, end)
            val name = section.lineSequence().firstOrNull { it.trimStart().startsWith("name:") }
                ?.substringAfter('"')?.substringBefore('"')
            device.groupValues[1].takeIf { name in builtInDrivers && "KEY_VOLUMEDOWN" in section }
        }.toList()
    }
}
