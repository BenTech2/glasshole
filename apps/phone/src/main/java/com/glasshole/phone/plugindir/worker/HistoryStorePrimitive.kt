package com.glasshole.phone.plugindir.worker

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists each matching message from a glass plugin into a ring buffer
 * held in SharedPreferences on the phone. The companion phone-side
 * activity (e.g. CalcHistoryActivity) reads directly from the same prefs
 * to render the list — no IPC, no service.
 *
 * Expected params:
 * ```
 * {
 *   "trigger":     "RESULT",           # message type to persist
 *   "prefs_name":  "calc_history",     # SharedPreferences file
 *   "prefs_key":   "history",          # JSON array key inside that file
 *   "max_entries": 100                 # cap; oldest entries drop off
 * }
 * ```
 *
 * Stores each message payload as-is (it must already be a JSON object).
 * Newest first, so UIs can render top-down without sorting.
 */
class HistoryStorePrimitive : WorkerPrimitive {

    companion object {
        private const val TAG = "HistoryStorePrim"
        private const val DEFAULT_MAX_ENTRIES = 100
    }

    private var trigger: String = ""
    private var prefsName: String = ""
    private var prefsKey: String = "history"
    private var maxEntries: Int = DEFAULT_MAX_ENTRIES

    private var appContext: Context? = null

    override fun start(
        context: Context,
        params: JSONObject,
        emit: (type: String, payload: String) -> Unit
    ) {
        this.appContext = context.applicationContext
        this.trigger = params.optString("trigger")
        this.prefsName = params.optString("prefs_name")
        this.prefsKey = params.optString("prefs_key", "history")
        this.maxEntries = params.optInt("max_entries", DEFAULT_MAX_ENTRIES).coerceAtLeast(1)
        Log.i(TAG, "armed: trigger=$trigger prefs=$prefsName.$prefsKey cap=$maxEntries")
    }

    override fun onMessage(type: String, payload: String) {
        if (trigger.isEmpty() || type != trigger) return
        if (prefsName.isEmpty()) return
        val ctx = appContext ?: return
        try {
            val entry = JSONObject(payload)
            val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val existingStr = prefs.getString(prefsKey, "[]") ?: "[]"
            val existing = JSONArray(existingStr)

            val updated = JSONArray().apply {
                put(entry)
                for (i in 0 until minOf(existing.length(), maxEntries - 1)) {
                    put(existing.getJSONObject(i))
                }
            }
            prefs.edit().putString(prefsKey, updated.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "[$prefsName] store failed: ${e.message}")
        }
    }

    override fun stop() {
        appContext = null
    }
}
