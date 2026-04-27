package com.glasshole.glass.sdk

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Drop-in helper for plugins that want the dynamic settings UI. A plugin
 * delegates three message types — `SCHEMA_REQ`, `CONFIG_READ`,
 * `CONFIG_WRITE` — to [handle] and gets back a boolean saying whether
 * the message was consumed. Anything not recognized returns false so
 * the plugin can fall through to its own handlers.
 *
 * The plugin's SharedPreferences file is authoritative — this helper
 * reads and writes there directly. On `CONFIG_WRITE` the helper also
 * invokes an optional `onConfigChanged` callback so the plugin can
 * take side-effects (restart a worker, refresh its activity, etc.).
 *
 * Usage inside a GlassPluginService subclass:
 * ```
 * private val configHandler = PluginConfigHandler(
 *     context = this,
 *     prefsName = "notes_settings",
 *     schemaResId = R.raw.plugin_schema,
 *     send = { type, payload -> sendToPhone(GlassPluginMessage(type, payload)) },
 *     onConfigChanged = { applyNewConfig() }
 * )
 *
 * override fun onMessageFromPhone(message: GlassPluginMessage) {
 *     if (configHandler.handle(message)) return
 *     // ... plugin's own handling ...
 * }
 * ```
 */
class PluginConfigHandler(
    private val context: Context,
    private val prefsName: String,
    private val schemaResId: Int,
    private val send: (type: String, payload: String) -> Unit,
    private val onConfigChanged: (() -> Unit)? = null
) {
    private val TAG = "PluginConfigHandler"

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * @return true if the message was a config/schema op and was handled.
     */
    fun handle(message: GlassPluginMessage): Boolean {
        return when (message.type) {
            GlassPluginConstants.MSG_SCHEMA_REQ -> {
                send(GlassPluginConstants.MSG_SCHEMA_RESP, readSchema())
                true
            }
            GlassPluginConstants.MSG_CONFIG_READ -> {
                send(GlassPluginConstants.MSG_CONFIG, prefsToJson())
                true
            }
            GlassPluginConstants.MSG_CONFIG_WRITE -> {
                applyConfig(message.payload)
                // Reply with the full new config so the phone sees what
                // actually landed — trims any fields the plugin ignored.
                send(GlassPluginConstants.MSG_CONFIG, prefsToJson())
                onConfigChanged?.invoke()
                true
            }
            else -> false
        }
    }

    private fun readSchema(): String {
        if (schemaResId == 0) return "{}"
        return try {
            context.resources.openRawResource(schemaResId).use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read schema res $schemaResId: ${e.message}")
            "{}"
        }
    }

    private fun prefsToJson(): String {
        val obj = JSONObject()
        for ((k, v) in prefs.all) {
            try { obj.put(k, v) } catch (_: Exception) {}
        }
        return obj.toString()
    }

    private fun applyConfig(json: String) {
        val obj = try { JSONObject(json) } catch (e: Exception) {
            Log.w(TAG, "Bad CONFIG_WRITE payload: ${e.message}")
            return
        }
        val editor = prefs.edit()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            when (v) {
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Double -> editor.putFloat(k, v.toFloat())
                is Float -> editor.putFloat(k, v)
                is String -> editor.putString(k, v)
                null, JSONObject.NULL -> editor.remove(k)
                else -> editor.putString(k, v.toString())
            }
        }
        editor.apply()
    }
}
