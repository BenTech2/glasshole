package com.glasshole.plugin.devtools.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler

/**
 * Dev-tools plugin. Pure on-glass UI — no phone-side counterpart yet.
 * The plugin service exists so the launcher's plugin host enumerates
 * us correctly and so we have a place to drop config + future phone
 * round-trips.
 */
class DevToolsGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "DevToolsGlassPlugin"
        const val PREFS_NAME = "devtools_settings"

        /** Singleton SSH server handle. Lives on the service so the
         *  server outlives the activity — the user starts it in the
         *  panel, backs out, and the server keeps listening for the
         *  laptop to SSH in. */
        @Volatile var sshd: SshdManager? = null
            private set
    }

    override val pluginId = "devtools"

    override fun onCreate() {
        super.onCreate()
        sshd = SshdManager(applicationContext)
    }

    override fun onDestroy() {
        try { sshd?.stop() } catch (_: Exception) {}
        sshd = null
        super.onDestroy()
    }

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
        Log.d(TAG, "Unknown message type: ${message.type}")
    }
}
