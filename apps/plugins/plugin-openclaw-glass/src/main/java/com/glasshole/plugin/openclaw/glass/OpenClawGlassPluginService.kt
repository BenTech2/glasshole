package com.glasshole.plugin.openclaw.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler

/**
 * OpenClaw is a kickstart-only plugin: the glass-side activity fires a
 * KICKSTART broadcast locally which this service picks up and forwards
 * as a PLUGIN:openclaw:KICKSTART message. The phone's http-post primitive
 * (declared in res/raw/plugin_schema.json) then POSTs to the Telegram
 * bot API — no hardcoded phone-side plugin class required.
 */
class OpenClawGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "OpenClawGlassPlugin"
        const val PREFS_NAME = "openclaw_settings"
    }

    override val pluginId: String = "openclaw"

    // Routes SCHEMA_REQ / CONFIG_READ / CONFIG_WRITE so the phone's
    // dynamic settings UI can read and write our config.
    private val configHandler by lazy {
        PluginConfigHandler(
            context = this,
            prefsName = PREFS_NAME,
            schemaResId = R.raw.plugin_schema,
            send = { type, payload -> sendToPhone(GlassPluginMessage(type, payload)) }
        )
    }

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        if (configHandler.handle(message)) return
        when (message.type) {
            // Only legacy surface left — the phone primitive no longer
            // sends an ack, but we'll accept it if something still does.
            "KICKSTART_ACK" -> Log.i(TAG, "Kickstart ack: ${message.payload}")
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }
}
