package com.glasshole.phone.widget

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-glass-device battery history. Each glass that's been connected
 * gets its own log file at
 *   <filesDir>/battery_history/<sanitized device id>.bin
 *
 * File format is a flat sequence of (long timestamp ms, float
 * percent) records — 12 bytes per sample. ~17KB per day at 1
 * sample/minute, ~120KB per week. We append in-memory and rewrite
 * the whole file on flush; small enough that the read-modify-write
 * cost is fine.
 *
 * Retention is enforced on every append: anything older than the
 * configured window (in [setRetentionMs]) is dropped before flush.
 *
 * Thread-safe — both BridgeService's background 60s poll and the
 * device-info activity's 5s live poll write here.
 */
class BatteryHistoryStore private constructor(context: Context) {

    data class Sample(val timeMs: Long, val percent: Float)

    private val rootDir: File = File(context.filesDir, "battery_history").apply { mkdirs() }
    /** deviceId → sorted list of samples in memory. Lazily populated
     *  from disk on first access for that device. */
    private val cache = HashMap<String, MutableList<Sample>>()
    private val lock = ReentrantLock()
    @Volatile private var retentionMs = DEFAULT_RETENTION_MS

    fun setRetentionMs(ms: Long) {
        retentionMs = ms.coerceAtLeast(60_000L)  // at least 1 minute
    }

    fun getRetentionMs(): Long = retentionMs

    /** Append a sample for [deviceId] and flush to disk. Older
     *  samples beyond [retentionMs] are dropped. */
    fun add(deviceId: String, timeMs: Long, percent: Float) {
        if (deviceId.isBlank()) return
        lock.withLock {
            val list = loadOrCreateLocked(deviceId)
            // Append; keep sorted (samples are nearly monotonic, but
            // a clock skew shouldn't break the graph).
            list.add(Sample(timeMs, percent.coerceIn(0f, 100f)))
            list.sortBy { it.timeMs }
            trimLocked(list)
            writeLocked(deviceId, list)
        }
    }

    /** Read samples for [deviceId] within [windowMs] ago to now. Pass
     *  Long.MAX_VALUE for "all retained". */
    fun read(deviceId: String, windowMs: Long): List<Sample> {
        if (deviceId.isBlank()) return emptyList()
        return lock.withLock {
            val list = loadOrCreateLocked(deviceId)
            val cutoff = System.currentTimeMillis() - windowMs
            if (windowMs == Long.MAX_VALUE) list.toList()
            else list.filter { it.timeMs >= cutoff }
        }
    }

    private fun loadOrCreateLocked(deviceId: String): MutableList<Sample> {
        return cache.getOrPut(deviceId) {
            val file = fileFor(deviceId)
            if (!file.exists()) mutableListOf() else readFromDisk(file)
        }
    }

    private fun trimLocked(list: MutableList<Sample>) {
        val cutoff = System.currentTimeMillis() - retentionMs
        // Samples are sorted ascending; drop the prefix older than cutoff.
        val firstKeep = list.indexOfFirst { it.timeMs >= cutoff }
        if (firstKeep > 0) {
            list.subList(0, firstKeep).clear()
        } else if (firstKeep < 0) {
            // All samples older than cutoff.
            list.clear()
        }
    }

    private fun fileFor(deviceId: String): File =
        File(rootDir, sanitize(deviceId) + ".bin")

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "unknown" }

    private fun readFromDisk(file: File): MutableList<Sample> {
        val out = mutableListOf<Sample>()
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                while (true) {
                    val ts = try { input.readLong() } catch (_: EOFException) { return@use }
                    val pct = try { input.readFloat() } catch (_: EOFException) { return@use }
                    out.add(Sample(ts, pct))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery history read failed (${file.name}): ${e.message}")
        }
        return out
    }

    private fun writeLocked(deviceId: String, list: List<Sample>) {
        val file = fileFor(deviceId)
        try {
            DataOutputStream(FileOutputStream(file).buffered()).use { out ->
                for (s in list) {
                    out.writeLong(s.timeMs)
                    out.writeFloat(s.percent)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery history write failed (${file.name}): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BatteryHistoryStore"
        const val DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000  // 7 days

        // Window presets exposed to the activity.
        const val WINDOW_30M = 30L * 60 * 1000
        const val WINDOW_1H = 60L * 60 * 1000
        const val WINDOW_6H = 6L * 60 * 60 * 1000
        const val WINDOW_24H = 24L * 60 * 60 * 1000
        const val WINDOW_7D = 7L * 24 * 60 * 60 * 1000
        const val WINDOW_ALL = Long.MAX_VALUE

        @Volatile private var instance: BatteryHistoryStore? = null
        fun get(context: Context): BatteryHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: BatteryHistoryStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
