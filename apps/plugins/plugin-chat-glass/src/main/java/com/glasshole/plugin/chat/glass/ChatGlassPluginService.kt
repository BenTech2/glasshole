package com.glasshole.plugin.chat.glass

import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

/**
 * Chat plugin glass-side service. The phone holds the IRC/YouTube
 * connection and fires CHAT / STATUS messages at us; we just broadcast
 * them locally so the visible ChatActivity can render them.
 */
class ChatGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "ChatGlassPlugin"
        const val ACTION_CHAT_EVENT = "com.glasshole.plugin.chat.glass.EVENT"
        const val EXTRA_KIND = "kind"
        const val EXTRA_USER = "user"
        const val EXTRA_TEXT = "text"
        const val EXTRA_COLOR = "color"
        const val EXTRA_SIZE = "size"
        const val EXTRA_MAX_MSGS = "max_msgs"
    }

    override val pluginId: String = "chat"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        when (message.type) {
            "CHAT" -> forwardChat(message.payload)
            "STATUS" -> forwardStatus(message.payload)
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun forwardChat(payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val intent = Intent(ACTION_CHAT_EVENT).apply {
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

    private fun forwardStatus(payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val intent = Intent(ACTION_CHAT_EVENT).apply {
                setPackage(packageName)
                putExtra(EXTRA_KIND, "status")
                putExtra(EXTRA_TEXT, json.optString("text", ""))
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Bad STATUS payload: ${e.message}")
        }
    }
}
