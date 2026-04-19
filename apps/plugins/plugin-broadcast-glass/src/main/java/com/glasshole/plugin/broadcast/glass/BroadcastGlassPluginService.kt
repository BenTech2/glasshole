package com.glasshole.plugin.broadcast.glass

import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

/**
 * Phone → glass CONFIG is delivered here and handed off to BroadcastActivity
 * via a local broadcast. We also keep a SharedPreferences copy so the
 * activity can apply the last-known config instantly on open, before the
 * phone has time to reply to the START handshake.
 *
 * CHAT / CHAT_STATUS messages are forwarded the same way — the broadcast
 * plugin runs its own chat client when the display mode is "chat", so we
 * don't depend on the separate Chat plugin being installed at all.
 */
class BroadcastGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "BroadcastGlassPlugin"
        const val ACTION_CONFIG = "com.glasshole.plugin.broadcast.glass.CONFIG"
        const val ACTION_CHAT = "com.glasshole.plugin.broadcast.glass.CHAT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_KIND = "kind"     // "chat" | "status"
        const val EXTRA_USER = "user"
        const val EXTRA_TEXT = "text"
        const val EXTRA_COLOR = "color"
        const val EXTRA_SIZE = "size"
        const val EXTRA_MAX_MSGS = "max_msgs"
    }

    override val pluginId: String = "broadcast"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        when (message.type) {
            "CONFIG" -> handleConfig(message.payload)
            "CHAT" -> handleChat(message.payload)
            "CHAT_STATUS" -> handleChatStatus(message.payload)
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun handleConfig(payload: String) {
        try {
            BroadcastPrefs.save(this, payload)
            val intent = Intent(ACTION_CONFIG).apply {
                setPackage(packageName)
                putExtra(EXTRA_CONFIG_JSON, payload)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Bad CONFIG payload: ${e.message}")
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
