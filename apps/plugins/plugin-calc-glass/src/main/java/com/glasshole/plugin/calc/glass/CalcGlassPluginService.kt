package com.glasshole.plugin.calc.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler

class CalcGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "CalcGlassPlugin"
        const val PREFS_NAME = "calc_settings"
    }

    override val pluginId = "calc"

    // Routes SCHEMA_REQ / CONFIG_READ / CONFIG_WRITE for the dynamic
    // settings UI. The only live knob here is `max_history`, which the
    // phone-side history-store primitive reads via param substitution.
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
            "EQUATION" -> {
                // Reserved for future "send equation from phone" flow.
                Log.d(TAG, "Received equation from phone: ${message.payload}")
            }
            else -> Log.d(TAG, "Unknown message type: ${message.type}")
        }
    }
}
