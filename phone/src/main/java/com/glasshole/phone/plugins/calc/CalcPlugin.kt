package com.glasshole.phone.plugins.calc

import android.content.Context
import android.util.Log
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONArray
import org.json.JSONObject

class CalcPlugin : PhonePlugin {

    companion object {
        private const val TAG = "CalcPlugin"
        const val PREFS_NAME = "calc_history"
        const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 100
    }

    override val pluginId: String = "calc"

    private lateinit var appContext: Context

    override fun onCreate(context: Context, sender: PluginSender) {
        this.appContext = context
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "RESULT" -> handleResult(message.payload)
            else -> Log.d(TAG, "Unknown message type: ${message.type}")
        }
    }

    private fun handleResult(payload: String) {
        try {
            val json = JSONObject(payload)
            val expr = json.getString("expr")
            val result = json.getString("result")
            val timestamp = json.getLong("timestamp")

            Log.i(TAG, "Calculation: $expr = $result")

            val entry = JSONObject().apply {
                put("expr", expr)
                put("result", result)
                put("timestamp", timestamp)
            }

            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val historyStr = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
            val history = JSONArray(historyStr)

            val updated = JSONArray()
            updated.put(entry)
            for (i in 0 until minOf(history.length(), MAX_HISTORY - 1)) {
                updated.put(history.getJSONObject(i))
            }

            prefs.edit().putString(KEY_HISTORY, updated.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store result: ${e.message}")
        }
    }
}
