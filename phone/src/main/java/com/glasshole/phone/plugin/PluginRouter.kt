package com.glasshole.phone.plugin

import android.util.Log
import com.glasshole.sdk.IPluginCallback
import com.glasshole.sdk.PluginMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes messages between BT protocol and registered phone plugins.
 */
class PluginRouter {

    companion object {
        private const val TAG = "GlassHoleRouter"
    }

    private val callbacks = ConcurrentHashMap<String, IPluginCallback>()

    fun registerPlugin(pluginId: String, callback: IPluginCallback) {
        callbacks[pluginId] = callback
        Log.i(TAG, "Plugin registered: $pluginId (total: ${callbacks.size})")
    }

    fun unregisterPlugin(pluginId: String) {
        callbacks.remove(pluginId)
        Log.i(TAG, "Plugin unregistered: $pluginId (total: ${callbacks.size})")
    }

    fun routeToPlugin(pluginId: String, message: PluginMessage): Boolean {
        val callback = callbacks[pluginId]
        if (callback == null) {
            Log.w(TAG, "No plugin registered for '$pluginId'")
            return false
        }
        return try {
            callback.onMessageFromGlass(message)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route to plugin '$pluginId': ${e.message}")
            callbacks.remove(pluginId)
            false
        }
    }

    fun notifyConnectionChanged(connected: Boolean) {
        for ((id, callback) in callbacks) {
            try {
                callback.onGlassConnectionChanged(connected)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify plugin '$id': ${e.message}")
                callbacks.remove(id)
            }
        }
    }

    fun getRegisteredPlugins(): Set<String> = callbacks.keys.toSet()
}
