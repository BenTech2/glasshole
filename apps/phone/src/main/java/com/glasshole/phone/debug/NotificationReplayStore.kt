package com.glasshole.phone.debug

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local-only ring buffer of forwarded-notification payloads, used by the
 * Debug screen to replay a previously-seen notification onto the glass
 * verbatim. Strictly opt-in (off by default), strictly local — nothing
 * leaves the device.
 *
 * The cache holds the same composed JSON we hand to BridgeService.sendRaw,
 * so a replay is byte-for-byte identical to the original delivery, which
 * is what we want when iterating on glass-side rendering for a specific
 * notification shape (e.g. YouTube Shorts cards).
 */
object NotificationReplayStore {

    private const val TAG = "NotifReplayStore"
    private const val PREFS = "glasshole_debug_replay"
    private const val KEY_ENABLED = "capture_enabled"
    private const val KEY_LIMIT = "cache_limit"
    private const val DEFAULT_LIMIT = 250
    /** Sentinel for "no cap" — UI's Unlimited option. */
    const val UNLIMITED = -1
    private const val FILE_NAME = "notif_replay_cache.json"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getLimit(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LIMIT, DEFAULT_LIMIT)

    fun setLimit(context: Context, limit: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LIMIT, limit).apply()
        // If we just shrank the cap, drop oldest entries to fit.
        if (limit != UNLIMITED) {
            val entries = load(context)
            if (entries.size > limit) {
                save(context, entries.takeLast(limit))
            }
        }
    }

    /** Append a freshly-composed notification payload. No-op if disabled. */
    fun capture(context: Context, json: String) {
        if (!isEnabled(context)) return
        val ctx = context.applicationContext
        val entries = load(ctx).toMutableList()
        entries.add(Entry(System.currentTimeMillis(), json))
        val limit = getLimit(ctx)
        val trimmed = if (limit == UNLIMITED) entries else entries.takeLast(limit)
        save(ctx, trimmed)
    }

    /** Today's (local-time) entries, newest first. */
    fun todayNewestFirst(context: Context): List<Entry> {
        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return load(context.applicationContext)
            .filter { it.timestampMs >= startOfToday }
            .reversed()
    }

    fun clear(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        try { file.delete() } catch (_: Exception) {}
    }

    fun count(context: Context): Int = load(context.applicationContext).size

    // --- IO ---

    private fun cacheFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): List<Entry> {
        val file = cacheFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                Entry(
                    timestampMs = obj.getLong("ts"),
                    json = obj.getString("json")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            emptyList()
        }
    }

    private fun save(context: Context, entries: List<Entry>) {
        val file = cacheFile(context)
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject().apply {
                    put("ts", e.timestampMs)
                    put("json", e.json)
                })
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    /**
     * Captured payload + when we saw it. The caller decodes [json] to pull
     * out app/title for display; the Spinner label is built from a parsed
     * snapshot rather than a separate stored field so we stay in sync if
     * the JSON shape changes upstream.
     */
    data class Entry(val timestampMs: Long, val json: String) {
        fun summary(): String {
            return try {
                val obj = JSONObject(json)
                val app = obj.optString("app", "?")
                val title = obj.optString("title", "").ifEmpty { obj.optString("text", "") }
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(timestampMs))
                if (title.isNotEmpty()) "$time · $app — $title" else "$time · $app"
            } catch (_: Exception) {
                "(unparseable @ $timestampMs)"
            }
        }
    }
}
