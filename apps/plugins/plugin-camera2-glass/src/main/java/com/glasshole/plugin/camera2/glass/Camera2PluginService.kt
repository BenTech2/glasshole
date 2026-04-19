package com.glasshole.plugin.camera2.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

/**
 * Minimal plugin shell — the camera runs entirely on-glass and doesn't
 * currently talk to the phone. This service exists so the glasshole base
 * app's plugin discovery sees and counts it, and so we have a hook for
 * future phone-side features (remote shutter, live preview, etc.).
 */
class Camera2PluginService : GlassPluginService() {

    override val pluginId: String = "camera2"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d("Camera2Plugin", "From phone: ${message.type}")
    }
}
