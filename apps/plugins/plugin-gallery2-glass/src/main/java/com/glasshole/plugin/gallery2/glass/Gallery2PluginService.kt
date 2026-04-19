package com.glasshole.plugin.gallery2.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

class Gallery2PluginService : GlassPluginService() {
    override val pluginId: String = "gallery2"
    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d("Gallery2Plugin", "From phone: ${message.type}")
    }
}
