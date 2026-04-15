package com.glasshole.glassee1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginConstants

/**
 * Manifest-declared receiver for plugin messages.
 * Forwards to BluetoothListenerService via a static reference.
 */
class PluginMessageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GlassHolePluginRecv"

        @Volatile
        var btService: BluetoothListenerService? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pluginId = intent.getStringExtra(GlassPluginConstants.EXTRA_PLUGIN_ID) ?: return
        val type = intent.getStringExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE) ?: return
        val payload = intent.getStringExtra(GlassPluginConstants.EXTRA_PAYLOAD) ?: ""

        val service = btService
        if (service == null) {
            Log.w(TAG, "BT service not running, starting it")
            context.startService(Intent(context, BluetoothListenerService::class.java))
            return
        }

        Log.i(TAG, "Plugin->Phone: $pluginId:$type os=${service.isPhoneConnected}")
        val sent = service.sendPluginMessage(pluginId, type, payload)
        Log.i(TAG, "Plugin->Phone sent=$sent")
    }
}
