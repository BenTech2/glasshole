package com.glasshole.glassee2

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginConstants
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.IGlassPluginCallback
import com.glasshole.glass.sdk.IGlassPluginHost

/**
 * Discovers and binds to Glass-side plugin APKs.
 * Routes messages between the BT service and plugin APKs.
 */
class PluginHostService : Service() {

    companion object {
        private const val TAG = "GlassHoleGlassHost"
    }

    private var btService: BluetoothListenerService? = null
    private var btBound = false
    private val pluginConnections = mutableMapOf<String, ServiceConnection>()

    private val btConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            btService = (binder as BluetoothListenerService.LocalBinder).getService()
            btBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            btService = null
            btBound = false
        }
    }

    private val hostBinder = object : IGlassPluginHost.Stub() {
        override fun registerGlassPlugin(pluginId: String, callback: IGlassPluginCallback) {
            btService?.registerPlugin(pluginId, callback)
        }

        override fun unregisterGlassPlugin(pluginId: String) {
            btService?.unregisterPlugin(pluginId)
        }

        override fun sendToPhone(pluginId: String, message: GlassPluginMessage): Boolean {
            return btService?.sendPluginMessage(pluginId, message.type, message.payload) ?: false
        }

        override fun isPhoneConnected(): Boolean {
            return btService?.isPhoneConnected ?: false
        }
    }

    override fun onBind(intent: Intent?): IBinder = hostBinder

    /** Watches for plugin installs/replacements and re-runs discovery. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (!pkg.startsWith("com.glasshole.")) return
            Log.i(TAG, "Package change ($action): $pkg — rediscovering plugins")
            // Give the PM a moment to finish settling before we rebind.
            Handler(Looper.getMainLooper()).postDelayed({
                discoverAndBindPlugins()
            }, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Bind to BT service
        val btIntent = Intent(this, BluetoothListenerService::class.java)
        bindService(btIntent, btConnection, Context.BIND_AUTO_CREATE)

        // Listen for plugin reinstalls so we rebind their AIDL callbacks.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)

        discoverAndBindPlugins()
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
        if (btBound) {
            unbindService(btConnection)
            btBound = false
        }
        for ((_, conn) in pluginConnections) {
            try { unbindService(conn) } catch (_: Exception) {}
        }
        pluginConnections.clear()
        super.onDestroy()
    }

    private fun discoverAndBindPlugins() {
        val intent = Intent(GlassPluginConstants.ACTION_GLASS_PLUGIN)
        val resolved = packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)

        for (info in resolved) {
            val serviceInfo = info.serviceInfo ?: continue
            val metaData = serviceInfo.metaData ?: continue
            val pluginId = metaData.getString(GlassPluginConstants.META_PLUGIN_ID) ?: continue

            // If we're already bound to this plugin, skip — a future disconnect
            // will retry.
            if (pluginConnections.containsKey(pluginId)) continue

            bindPlugin(pluginId, serviceInfo.packageName, serviceInfo.name)
        }
    }

    private fun bindPlugin(pluginId: String, packageName: String, className: String) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val callback = IGlassPluginCallback.Stub.asInterface(binder)
                btService?.registerPlugin(pluginId, callback)
                Log.i(TAG, "Glass plugin connected: $pluginId")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.i(TAG, "Glass plugin disconnected: $pluginId — will retry")
                btService?.unregisterPlugin(pluginId)
                try { unbindService(this) } catch (_: Exception) {}
                pluginConnections.remove(pluginId)
                // Plugin process died (crash or package replaced) — try to
                // rebind after a short delay so it restarts and re-registers.
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!pluginConnections.containsKey(pluginId)) {
                        bindPlugin(pluginId, packageName, className)
                    }
                }, 750)
            }
        }

        val bindIntent = Intent(GlassPluginConstants.ACTION_GLASS_PLUGIN).apply {
            setClassName(packageName, className)
        }

        try {
            if (bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
                pluginConnections[pluginId] = connection
                Log.i(TAG, "Bound to glass plugin: $pluginId")
            } else {
                Log.w(TAG, "bindService returned false for $pluginId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to glass plugin $pluginId: ${e.message}")
        }
    }
}
