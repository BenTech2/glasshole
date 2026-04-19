package com.glasshole.plugin.calc.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

class CalcGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "CalcGlassPlugin"
    }

    override val pluginId = "calc"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        when (message.type) {
            "EQUATION" -> {
                // Could receive an equation from the phone to evaluate
                Log.d(TAG, "Received equation from phone: ${message.payload}")
            }
            else -> Log.d(TAG, "Unknown message type: ${message.type}")
        }
    }
}
