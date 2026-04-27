package com.glasshole.plugin.opencv.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

/**
 * Minimal plugin shell — the demo runs entirely on-glass. This service
 * only exists so the base app's plugin discovery counts us and so
 * we have a future hook for phone-side settings (frame-rate cap,
 * label filters, etc.).
 */
class OpenCvPluginService : GlassPluginService() {

    override val pluginId: String = "opencv"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d("OpenCvPlugin", "From phone: ${message.type}")
    }
}
