package com.glasshole.plugin.openclaw.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

/**
 * OpenClaw is a kickstart-only plugin: the glass side has nothing to react
 * to from the phone (the actual chat happens in Telegram on the phone).
 * This service exists solely so the phone can discover the plugin via
 * LIST_PACKAGES and so the kickstart broadcast has a registered sender.
 */
class OpenClawGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "OpenClawGlassPlugin"
    }

    override val pluginId: String = "openclaw"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        when (message.type) {
            "KICKSTART_ACK" -> Log.i(TAG, "Kickstart ack: ${message.payload}")
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }
}
