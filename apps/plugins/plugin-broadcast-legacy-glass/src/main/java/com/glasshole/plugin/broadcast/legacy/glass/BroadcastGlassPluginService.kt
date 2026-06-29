package com.glasshole.plugin.broadcast.legacy.glass

import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler

/**
 * Glass-side service for the dynamic Broadcast plugin (EE1 / XE Camera1
 * path). Routes SCHEMA_REQ / CONFIG_READ / CONFIG_WRITE through the
 * standard [PluginConfigHandler] so the phone's dynamic settings UI
 * renders this plugin like every other one. The previous hand-rolled
 * `CONFIG` message handler never engaged with the schema-driven flow,
 * so the phone's plugin manager opened it with no settings page at
 * all — fixed by migrating to the SDK helper.
 *
 * Chat overlay messages (CHAT / CHAT_STATUS) still flow directly: the
 * phone-side chat-twitch / chat-youtube worker primitives fire them
 * and BroadcastActivity renders.
 */
class BroadcastGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "BroadcastGlassPlugin"
        const val PREFS_NAME = "broadcast_config"
        const val ACTION_CONFIG = "com.glasshole.plugin.broadcast.legacy.glass.CONFIG"
        const val ACTION_CHAT = "com.glasshole.plugin.broadcast.legacy.glass.CHAT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_KIND = "kind"
        const val EXTRA_USER = "user"
        const val EXTRA_TEXT = "text"
        const val EXTRA_COLOR = "color"
        const val EXTRA_SIZE = "size"
        const val EXTRA_MAX_MSGS = "max_msgs"
    }

    override val pluginId: String = "broadcast"

    private val configHandler by lazy {
        PluginConfigHandler(
            context = this,
            prefsName = PREFS_NAME,
            schemaResId = R.raw.plugin_schema,
            send = { type, payload ->
                sendToPhone(GlassPluginMessage(type, payload))
            },
            // BroadcastActivity reads BroadcastPrefs on each start, but
            // we also re-fire the local ACTION_CONFIG broadcast so a
            // live session can pick up edits without restarting.
            onConfigChanged = {
                val intent = Intent(ACTION_CONFIG).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_CONFIG_JSON, readPrefsAsJson())
                }
                sendBroadcast(intent)
            }
        )
    }

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        if (configHandler.handle(message)) return
        when (message.type) {
            "CHAT" -> handleChat(message.payload)
            "CHAT_STATUS" -> handleChatStatus(message.payload)
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun readPrefsAsJson(): String {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val obj = org.json.JSONObject()
        for ((k, v) in p.all) {
            try { obj.put(k, v) } catch (_: Exception) {}
        }
        return obj.toString()
    }

    private fun handleChat(payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val intent = Intent(ACTION_CHAT).apply {
                setPackage(packageName)
                putExtra(EXTRA_KIND, "chat")
                putExtra(EXTRA_USER, json.optString("user", ""))
                putExtra(EXTRA_TEXT, json.optString("text", ""))
                putExtra(EXTRA_COLOR, json.optString("color", ""))
                putExtra(EXTRA_SIZE, json.optInt("size", 0))
                putExtra(EXTRA_MAX_MSGS, json.optInt("maxMsgs", 0))
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Bad CHAT payload: ${e.message}")
        }
    }

    private fun handleChatStatus(payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val intent = Intent(ACTION_CHAT).apply {
                setPackage(packageName)
                putExtra(EXTRA_KIND, "status")
                putExtra(EXTRA_TEXT, json.optString("text", ""))
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Bad CHAT_STATUS payload: ${e.message}")
        }
    }
}
