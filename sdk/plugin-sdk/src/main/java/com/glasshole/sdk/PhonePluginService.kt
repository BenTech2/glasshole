package com.glasshole.sdk

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Abstract base class for phone-side GlassHole plugins.
 * Plugin APKs extend this service to receive messages from Glass
 * and send messages back through the GlassHole host.
 */
abstract class PhonePluginService : Service() {

    companion object {
        private const val TAG = "GlassHolePlugin"
    }

    /** Unique plugin identifier (e.g., "notes", "media", "calc") */
    abstract val pluginId: String

    /** Called when a message arrives from the Glass-side plugin */
    abstract fun onMessageFromGlass(message: PluginMessage)

    /** Called when Glass connection state changes */
    open fun onGlassConnectionChanged(connected: Boolean) {}

    private var hostService: IPluginHost? = null
    private var hostBound = false

    private val pluginCallback = object : IPluginCallback.Stub() {
        override fun onMessageFromGlass(message: PluginMessage) {
            this@PhonePluginService.onMessageFromGlass(message)
        }

        override fun onGlassConnectionChanged(connected: Boolean) {
            this@PhonePluginService.onGlassConnectionChanged(connected)
        }
    }

    private val hostConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            hostService = IPluginHost.Stub.asInterface(binder)
            try {
                hostService?.registerPlugin(pluginId, pluginCallback)
                Log.i(TAG, "Plugin '$pluginId' registered with host")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register plugin: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hostService = null
            hostBound = false
            Log.i(TAG, "Plugin '$pluginId' disconnected from host")
        }
    }

    override fun onCreate() {
        super.onCreate()
        bindToHost()
    }

    override fun onDestroy() {
        try {
            hostService?.unregisterPlugin(pluginId)
        } catch (_: Exception) {}
        if (hostBound) {
            unbindService(hostConnection)
            hostBound = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Plugins are bound by the host via AIDL
        return pluginCallback.asBinder()
    }

    /** Send a message to the Glass-side plugin */
    protected fun sendToGlass(message: PluginMessage): Boolean {
        return try {
            hostService?.sendToGlass(pluginId, message) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "sendToGlass failed: ${e.message}")
            false
        }
    }

    /** Check if Glass is currently connected */
    protected fun isGlassConnected(): Boolean {
        return try {
            hostService?.isGlassConnected ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun bindToHost() {
        val intent = Intent().apply {
            setClassName("com.glasshole.phone", "com.glasshole.phone.service.PluginHostService")
        }
        try {
            hostBound = bindService(intent, hostConnection, Context.BIND_AUTO_CREATE)
            if (!hostBound) {
                Log.w(TAG, "Could not bind to GlassHole host service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to host: ${e.message}")
        }
    }
}
