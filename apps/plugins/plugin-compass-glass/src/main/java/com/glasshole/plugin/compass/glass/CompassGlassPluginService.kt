package com.glasshole.plugin.compass.glass

import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler

/**
 * Compass plugin glass-side service. Heading is computed on the glass
 * itself (EE2 / EE1 / XE all have magnetometers). The phone's GPS feeds
 * us lat / lon / altitude / speed via LOCATION_UPDATE messages — Glass
 * hardware has no GPS of its own.
 */
class CompassGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "CompassGlassPlugin"
        const val PREFS_NAME = "compass_settings"
        const val ACTION_LOCATION_UPDATE = "com.glasshole.plugin.compass.glass.LOCATION_UPDATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_ALT = "alt"
        const val EXTRA_ACC = "acc"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_HAS_FIX = "has_fix"
    }

    override val pluginId: String = "compass"

    // Routes SCHEMA_REQ / CONFIG_READ / CONFIG_WRITE for the dynamic
    // settings UI. Current values live in SharedPreferences("compass_settings").
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
            "LOCATION_UPDATE" -> forwardLocation(message.payload)
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun forwardLocation(payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val intent = Intent(ACTION_LOCATION_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_HAS_FIX, json.optBoolean("has_fix", true))
                putExtra(EXTRA_LAT, json.optDouble("lat", Double.NaN))
                putExtra(EXTRA_LON, json.optDouble("lon", Double.NaN))
                putExtra(EXTRA_ALT, json.optDouble("alt", Double.NaN))
                putExtra(EXTRA_ACC, json.optDouble("acc", Double.NaN))
                putExtra(EXTRA_SPEED, json.optDouble("speed", Double.NaN))
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Bad LOCATION_UPDATE payload: ${e.message}")
        }
    }
}
