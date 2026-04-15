package com.glasshole.glassee2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginConstants

/**
 * Manifest-declared receiver for plugin messages sent by plugin activities
 * (NotesMenuActivity, DictateActivity, CalcActivity, etc.) via
 *   Intent(GlassPluginConstants.ACTION_MESSAGE_TO_PHONE)
 *     .setPackage("com.glasshole.glassee2")
 *
 * EE2 primarily uses AIDL for plugin↔host communication, but the plugin
 * activities use broadcasts because they don't hold a binder. Without this
 * receiver, their outbound messages disappear into the void.
 *
 * Mirrors the EE1 implementation — forwards to BluetoothListenerService via
 * a static reference set in the service's onCreate.
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
            Log.w(TAG, "BT service not running; dropping $pluginId:$type")
            return
        }

        Log.i(TAG, "Plugin->Phone: $pluginId:$type")
        val sent = service.sendPluginMessage(pluginId, type, payload)
        Log.i(TAG, "Plugin->Phone sent=$sent")
    }
}
