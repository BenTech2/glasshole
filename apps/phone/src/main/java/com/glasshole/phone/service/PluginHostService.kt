package com.glasshole.phone.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginDiscovery
import com.glasshole.phone.plugin.PluginRouter
import com.glasshole.phone.plugins.broadcast.BroadcastPlugin
import com.glasshole.phone.plugins.calc.CalcPlugin
import com.glasshole.phone.plugins.chat.ChatPlugin
import com.glasshole.phone.plugins.device.DevicePlugin
import com.glasshole.phone.plugins.gallery.GalleryPlugin
import com.glasshole.phone.plugins.notes.NotesPlugin
import com.glasshole.phone.plugins.openclaw.OpenClawPlugin
import com.glasshole.phone.plugins.stream.StreamPlugin
import com.glasshole.sdk.IPluginCallback
import com.glasshole.sdk.IPluginHost
import com.glasshole.sdk.PluginConstants
import com.glasshole.sdk.PluginMessage

/**
 * Service that discovers and binds to phone-side plugin APKs.
 * Acts as the host side of the plugin AIDL interface.
 */
class PluginHostService : Service() {

    companion object {
        private const val TAG = "GlassHolePluginHost"
    }

    val pluginRouter = PluginRouter()

    // Callback to send plugin messages over BT
    var onSendPluginMessage: ((pluginId: String, type: String, payload: String) -> Boolean)? = null
    var onIsGlassConnected: (() -> Boolean)? = null

    private val pluginConnections = mutableMapOf<String, ServiceConnection>()

    private var bridgeService: BridgeService? = null
    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? BridgeService.LocalBinder)?.getService() ?: return
            bridgeService = service
            onSendPluginMessage = { pluginId, type, payload ->
                service.sendPluginMessage(pluginId, type, payload)
            }
            onIsGlassConnected = { service.isConnected }
            service.pluginRouter = pluginRouter
            Log.i(TAG, "PluginHost ↔ Bridge wired")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
        }
    }

    private val builtInPlugins: List<PhonePlugin> = listOf(
        NotesPlugin(),
        CalcPlugin(),
        StreamPlugin(),
        DevicePlugin(),
        GalleryPlugin(),
        OpenClawPlugin(),
        ChatPlugin(),
        BroadcastPlugin()
    )

    // Local binder for in-process binding (MainActivity)
    inner class LocalBinder : android.os.Binder() {
        fun getService(): PluginHostService = this@PluginHostService
    }
    private val localBinder = LocalBinder()

    // AIDL binder for cross-process binding (external plugins)
    private val hostBinder = object : IPluginHost.Stub() {
        override fun registerPlugin(pluginId: String, callback: IPluginCallback) {
            pluginRouter.registerPlugin(pluginId, callback)
        }

        override fun unregisterPlugin(pluginId: String) {
            pluginRouter.unregisterPlugin(pluginId)
        }

        override fun sendToGlass(pluginId: String, message: PluginMessage): Boolean {
            return onSendPluginMessage?.invoke(pluginId, message.type, message.payload) ?: false
        }

        override fun isGlassConnected(): Boolean {
            return onIsGlassConnected?.invoke() ?: false
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        // Return local binder for same-package callers, AIDL for external plugins
        val callerPackage = intent?.`package` ?: intent?.component?.packageName
        return if (callerPackage == packageName || callerPackage == null) {
            localBinder
        } else {
            hostBinder
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PluginHostService created")
        registerBuiltInPlugins()
        discoverAndBindPlugins()

        // Wire directly to BridgeService so plugin send paths work even when
        // MainActivity isn't in the foreground (e.g. share-sheet launch).
        val bridgeIntent = Intent(this, BridgeService::class.java)
        startService(bridgeIntent)
        bindService(bridgeIntent, bridgeConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        unregisterBuiltInPlugins()
        unbindAllPlugins()
        try { unbindService(bridgeConnection) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun registerBuiltInPlugins() {
        for (plugin in builtInPlugins) {
            val sender: (PluginMessage) -> Boolean = { msg ->
                onSendPluginMessage?.invoke(plugin.pluginId, msg.type, msg.payload) ?: false
            }
            plugin.onCreate(applicationContext, sender)

            val callback = object : IPluginCallback.Stub() {
                override fun onMessageFromGlass(message: PluginMessage) {
                    plugin.onMessageFromGlass(message)
                }
                override fun onGlassConnectionChanged(connected: Boolean) {
                    plugin.onGlassConnectionChanged(connected)
                }
            }
            pluginRouter.registerPlugin(plugin.pluginId, callback)
            Log.i(TAG, "Built-in plugin registered: ${plugin.pluginId}")
            AppLog.log("PluginHost", "Built-in plugin ready: ${plugin.pluginId}")
        }
    }

    private fun unregisterBuiltInPlugins() {
        for (plugin in builtInPlugins) {
            pluginRouter.unregisterPlugin(plugin.pluginId)
            try { plugin.onDestroy() } catch (_: Exception) {}
        }
    }

    fun discoverAndBindPlugins() {
        val plugins = PluginDiscovery.discoverPlugins(this)
        for (plugin in plugins) {
            bindToPlugin(plugin.pluginId, plugin.packageName, plugin.serviceName)
        }
    }

    private fun bindToPlugin(pluginId: String, packageName: String, serviceName: String) {
        if (pluginConnections.containsKey(pluginId)) return

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.i(TAG, "Plugin connected: $pluginId")
                val callback = IPluginCallback.Stub.asInterface(binder)
                pluginRouter.registerPlugin(pluginId, callback)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.i(TAG, "Plugin disconnected: $pluginId")
                pluginRouter.unregisterPlugin(pluginId)
                pluginConnections.remove(pluginId)
            }
        }

        val intent = Intent(PluginConstants.ACTION_PHONE_PLUGIN).apply {
            setClassName(packageName, serviceName)
        }

        try {
            val bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (bound) {
                pluginConnections[pluginId] = connection
                Log.i(TAG, "Bound to plugin: $pluginId ($packageName)")
            } else {
                Log.w(TAG, "Failed to bind to plugin: $pluginId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to plugin $pluginId: ${e.message}")
        }
    }

    private fun unbindAllPlugins() {
        for ((id, conn) in pluginConnections) {
            try {
                unbindService(conn)
                Log.i(TAG, "Unbound plugin: $id")
            } catch (_: Exception) {}
        }
        pluginConnections.clear()
    }
}
