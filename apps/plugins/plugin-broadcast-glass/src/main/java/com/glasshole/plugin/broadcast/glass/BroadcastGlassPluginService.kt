package com.glasshole.plugin.broadcast.glass

import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler

/**
 * Glass-side service for the dynamic Broadcast plugin. Streaming config
 * (URL, resolution, bitrate, display mode, chat overlay) lives in this
 * plugin's own SharedPreferences, managed by [PluginConfigHandler] —
 * BroadcastActivity reads it directly on open, so no CONFIG roundtrip
 * with the phone is needed.
 *
 * Chat overlay messages still flow through here: the phone-side
 * chat-twitch / chat-youtube worker primitives fire CHAT / CHAT_STATUS
 * and we rebroadcast locally so BroadcastActivity can render them.
 */
class BroadcastGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "BroadcastGlassPlugin"
        const val PREFS_NAME = "broadcast_settings"
        const val ACTION_CHAT = "com.glasshole.plugin.broadcast.glass.CHAT"
        const val EXTRA_KIND = "kind"     // "chat" | "status"
        const val EXTRA_USER = "user"
        const val EXTRA_TEXT = "text"
        const val EXTRA_COLOR = "color"
    }

    override val pluginId: String = "broadcast"

    // Routes SCHEMA_REQ / CONFIG_READ / CONFIG_WRITE for the dynamic
    // settings UI. BroadcastActivity reads the persisted values directly
    // from PREFS_NAME each time it starts streaming.
    private val configHandler by lazy {
        PluginConfigHandler(
            context = this,
            prefsName = PREFS_NAME,
            schemaResId = R.raw.plugin_schema,
            send = { type, payload ->
                sendToPhone(GlassPluginMessage(type, payload))
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

    private fun handleChat(payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val intent = Intent(ACTION_CHAT).apply {
                setPackage(packageName)
                putExtra(EXTRA_KIND, "chat")
                putExtra(EXTRA_USER, json.optString("user", ""))
                putExtra(EXTRA_TEXT, json.optString("text", ""))
                putExtra(EXTRA_COLOR, json.optString("color", ""))
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
