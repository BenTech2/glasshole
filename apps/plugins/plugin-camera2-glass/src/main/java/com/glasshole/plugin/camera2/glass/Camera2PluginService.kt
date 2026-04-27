package com.glasshole.plugin.camera2.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler

/**
 * Routes SCHEMA_REQ / CONFIG_READ / CONFIG_WRITE for the dynamic
 * settings UI. Values land in [PREFS_NAME] so [CameraActivity] can
 * read them directly.
 */
class Camera2PluginService : GlassPluginService() {

    companion object {
        private const val TAG = "Camera2Plugin"
        const val PREFS_NAME = "camera2_settings"
    }

    override val pluginId: String = "camera2"

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
        Log.d(TAG, "From phone: ${message.type}")
    }
}
