// SPDX-License-Identifier: MIT
package com.glasshole.glassee2

import android.util.Log
import java.io.File

/**
 * Lightweight system-resource sampler — CPU usage %, RAM usage %,
 * hottest thermal-zone temperature in Celsius. Used by the Home
 * time card's debug-mode stats overlay.
 *
 * All three measurements come from `/proc` + `/sys` reads with no
 * permissions required. Each [sample] call costs roughly:
 *  - CPU: 1 small /proc/stat read + delta against last sample
 *  - RAM: 1 small /proc/meminfo read
 *  - Temp: scan /sys/class/thermal/thermal_zone* once, then
 *          re-read whichever zone was hottest from then on
 *
 * Designed to be called every 1-2 seconds without measurable battery
 * impact.
 */
class SystemStats {

    /** One snapshot. CPU % is null on the very first call (we need a
     *  baseline). Temp may be null on devices with no thermal zones. */
    data class Snapshot(
        val cpuPercent: Int?,
        val ramPercent: Int?,
        val tempCelsius: Float?,
    )

    private var lastTotal: Long = 0
    private var lastIdle: Long = 0
    private var hottestZonePath: String? = null

    fun sample(): Snapshot {
        return Snapshot(
            cpuPercent = sampleCpu(),
            ramPercent = sampleRam(),
            tempCelsius = sampleTemp(),
        )
    }

    /** /proc/stat line 1:
     *      cpu  user nice system idle iowait irq softirq steal ...
     *  Total = sum of all fields, idle bucket = idle + iowait.
     *  Usage % = (totalDelta - idleDelta) / totalDelta * 100.
     *  Returns null until we have two samples to compare. */
    private fun sampleCpu(): Int? {
        try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() }
                ?: return null
            val parts = line.split(Regex("\\s+"))
            // parts[0] = "cpu", parts[1..] = jiffy counters.
            if (parts.size < 5) return null
            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
            val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
            val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L
            val total = user + nice + system + idle + iowait + irq + softirq
            val idleAll = idle + iowait

            val prevTotal = lastTotal
            val prevIdle = lastIdle
            lastTotal = total
            lastIdle = idleAll

            if (prevTotal == 0L) return null  // first call — no delta yet
            val totalDelta = total - prevTotal
            val idleDelta = idleAll - prevIdle
            if (totalDelta <= 0) return null
            val busy = totalDelta - idleDelta
            return ((busy * 100.0) / totalDelta).toInt().coerceIn(0, 100)
        } catch (e: Throwable) {
            Log.w(TAG, "CPU sample failed: ${e.message}")
            return null
        }
    }

    /** /proc/meminfo: pull MemTotal + MemAvailable (or MemFree fallback). */
    private fun sampleRam(): Int? {
        try {
            var total = 0L
            var available = -1L
            var free = 0L
            File("/proc/meminfo").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    when {
                        line.startsWith("MemTotal:") ->
                            total = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                        line.startsWith("MemAvailable:") ->
                            available = line.filter { it.isDigit() }.toLongOrNull() ?: -1L
                        line.startsWith("MemFree:") ->
                            free = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    }
                }
            }
            if (total <= 0L) return null
            // Prefer MemAvailable (kernel ≥ 3.14); fall back to MemFree.
            val freeish = if (available >= 0) available else free
            val used = total - freeish
            return ((used * 100.0) / total).toInt().coerceIn(0, 100)
        } catch (e: Throwable) {
            Log.w(TAG, "RAM sample failed: ${e.message}")
            return null
        }
    }

    /** Glass thermals live in `/sys/class/thermal/thermal_zone{0..N}/temp`.
     *  Values are millidegrees Celsius. We pick the hottest zone on the
     *  first call (typically CPU / SoC) and stick to it afterward, since
     *  scanning every call would cost more than reading one file. */
    private fun sampleTemp(): Float? {
        val path = hottestZonePath ?: scanForHottestZone()?.also { hottestZonePath = it }
            ?: return null
        return try {
            val v = File(path).bufferedReader().use { it.readLine() }
                ?.trim()?.toLongOrNull() ?: return null
            v / 1000.0f
        } catch (_: Throwable) {
            // Zone disappeared (e.g. driver reload) — rescan next time.
            hottestZonePath = null
            null
        }
    }

    private fun scanForHottestZone(): String? {
        // 1. Standard `/sys/class/thermal/thermal_zone*/temp` — present
        //    on EE1, EE2, and most modern Android devices. Pick the
        //    hottest zone (SoC tends to be highest).
        var bestPath: String? = null
        var bestTemp = Long.MIN_VALUE
        try {
            val root = File("/sys/class/thermal")
            if (root.isDirectory) {
                for (dir in root.listFiles().orEmpty()) {
                    if (!dir.name.startsWith("thermal_zone")) continue
                    val tempFile = File(dir, "temp")
                    if (!tempFile.canRead()) continue
                    val v = try {
                        tempFile.bufferedReader().use { it.readLine() }
                            ?.trim()?.toLongOrNull()
                    } catch (_: Throwable) { null } ?: continue
                    if (v > bestTemp) {
                        bestTemp = v
                        bestPath = tempFile.absolutePath
                    }
                }
            }
        } catch (_: Throwable) {
            // listFiles can throw on some restricted dirs.
        }
        if (bestPath != null) return bestPath

        // 2. Glass XE fallback — TI OMAP4 doesn't expose the standard
        //    thermal class. The "Notle PCB sensor" is the official
        //    Glass-XE main thermal probe (Notle = Glass codename).
        //    Reports millidegrees C, readable without root.
        for (candidate in XE_THERMAL_CANDIDATES) {
            val f = File(candidate)
            if (f.canRead()) {
                try {
                    f.bufferedReader().use { it.readLine() }
                        ?.trim()?.toLongOrNull() ?: continue
                    return candidate
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    /** XE-specific thermal sensor paths, tried in order. */
    private val XE_THERMAL_CANDIDATES = listOf(
        "/sys/devices/platform/notle_pcb_sensor.0/temperature",
        "/sys/devices/platform/omap_temp_sensor.0/temperature",
    )

    companion object {
        private const val TAG = "SystemStats"

        /** Render a snapshot into a compact one-liner for the time card.
         *  Format: `CPU 42% • RAM 73% • 47°C` — bullets are middle dot
         *  U+2022 which the Glass system font handles cleanly. */
        fun format(snapshot: Snapshot, useFahrenheit: Boolean): String {
            val parts = mutableListOf<String>()
            snapshot.cpuPercent?.let { parts.add("CPU $it%") }
            snapshot.ramPercent?.let { parts.add("RAM $it%") }
            snapshot.tempCelsius?.let { c ->
                if (useFahrenheit) {
                    val f = c * 9f / 5f + 32f
                    parts.add("${kotlin.math.round(f).toInt()}°F")
                } else {
                    parts.add("${kotlin.math.round(c).toInt()}°C")
                }
            }
            return parts.joinToString("  •  ")
        }
    }
}
