package com.glasshole.plugin.nav.glass

import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

/**
 * Nav plugin glass-side service. The phone scrapes Google Maps'
 * navigation notification and sends NAV_UPDATE / NAV_END messages
 * here. We auto-launch NavActivity so the user doesn't have to open
 * it manually once nav starts, and forward every update via a
 * local broadcast to the running activity.
 */
class NavGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "NavGlassPlugin"
        const val ACTION_NAV_EVENT = "com.glasshole.plugin.nav.glass.EVENT"
        const val EXTRA_KIND = "kind"                 // "update" | "end"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_INSTRUCTION = "instruction"
        const val EXTRA_ETA = "eta"
        const val EXTRA_ICON_B64 = "icon_b64"
    }

    override val pluginId: String = "nav"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        when (message.type) {
            "NAV_UPDATE" -> handleUpdate(message.payload)
            "NAV_END" -> handleEnd()
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun handleUpdate(payload: String) {
        val json = try { org.json.JSONObject(payload) } catch (e: Exception) {
            Log.w(TAG, "Bad NAV_UPDATE payload: ${e.message}")
            return
        }
        // Auto-launch the nav activity if it isn't already running — this is
        // the core value of the plugin: glance-up nav without opening the app.
        try {
            val launch = Intent(this, NavActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
        } catch (e: Exception) {
            Log.w(TAG, "Launch NavActivity failed: ${e.message}")
        }

        val intent = Intent(ACTION_NAV_EVENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_KIND, "update")
            putExtra(EXTRA_DISTANCE, json.optString("distance", ""))
            putExtra(EXTRA_INSTRUCTION, json.optString("instruction", ""))
            putExtra(EXTRA_ETA, json.optString("eta", ""))
            putExtra(EXTRA_ICON_B64, json.optString("icon", ""))
        }
        sendBroadcast(intent)
    }

    private fun handleEnd() {
        val intent = Intent(ACTION_NAV_EVENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_KIND, "end")
        }
        sendBroadcast(intent)
    }
}
