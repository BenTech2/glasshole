package com.glasshole.plugin.aiassistant.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler

/**
 * Glass-side service for the AI Assistant plugin. Two responsibilities:
 *
 *  1. Surface the dynamic settings UI on the phone (provider, API keys,
 *     model, system prompt, etc.) by routing SCHEMA_REQ / CONFIG_READ /
 *     CONFIG_WRITE through [PluginConfigHandler].
 *
 *  2. Receive RESPONSE / ERROR replies from the phone proxy and
 *     relay them to a live [AssistantActivity] (if any) via local
 *     broadcast — the heavy HTTP work happens on the phone, so this
 *     side is just a thin pipe.
 *
 * The ASK flow is initiated by [AssistantActivity] which sends the
 * envelope directly via the SDK's sendToPhone helper.
 */
class AiAssistantPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "AiAssistantPlugin"
        const val PREFS_NAME = "ai_assistant_settings"
        const val ACTION_REPLY = "com.glasshole.plugin.aiassistant.glass.REPLY"
        const val EXTRA_KIND = "kind"      // "response" | "error"
        const val EXTRA_TEXT = "text"
    }

    override val pluginId: String = "ai"

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
            "RESPONSE" -> relay("response", message.payload)
            "ERROR" -> relay("error", message.payload)
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    /** Local broadcast so [AssistantActivity], if visible, can render
     *  the reply without us holding an Activity reference here. */
    private fun relay(kind: String, payload: String) {
        try {
            sendBroadcast(android.content.Intent(ACTION_REPLY).apply {
                setPackage(packageName)
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_TEXT, payload)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Reply broadcast failed: ${e.message}")
        }
    }
}
