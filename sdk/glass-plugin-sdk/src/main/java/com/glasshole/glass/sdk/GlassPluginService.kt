package com.glasshole.glass.sdk

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Abstract base class for Glass-side GlassHole plugins.
 * On EE2 (API 27+): binds to the host via AIDL.
 * On EE1/XE (API 19): uses explicit broadcasts.
 */
abstract class GlassPluginService : Service() {

    companion object {
        private const val TAG = "GlassHoleGlassPlugin"
    }

    /** Unique plugin identifier — must match the phone-side plugin */
    abstract val pluginId: String

    /** Called when a message arrives from the phone-side plugin */
    abstract fun onMessageFromPhone(message: GlassPluginMessage)

    /** Called when phone connection state changes */
    open fun onPhoneConnectionChanged(connected: Boolean) {}

    // AIDL host (EE2)
    private var hostService: IGlassPluginHost? = null
    private var hostBound = false

    private val pluginCallback = object : IGlassPluginCallback.Stub() {
        override fun onMessageFromPhone(message: GlassPluginMessage) {
            this@GlassPluginService.onMessageFromPhone(message)
        }

        override fun onPhoneConnectionChanged(connected: Boolean) {
            this@GlassPluginService.onPhoneConnectionChanged(connected)
        }
    }

    private val hostConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            hostService = IGlassPluginHost.Stub.asInterface(binder)
            try {
                hostService?.registerGlassPlugin(pluginId, pluginCallback)
                Log.i(TAG, "Glass plugin '$pluginId' registered with host")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register glass plugin: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hostService = null
            hostBound = false
        }
    }

    // Broadcast receiver (EE1/XE)
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(GlassPluginConstants.EXTRA_PLUGIN_ID) ?: return
            if (id != pluginId) return

            val type = intent.getStringExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE) ?: ""
            val payload = intent.getStringExtra(GlassPluginConstants.EXTRA_PAYLOAD) ?: ""
            val binary = intent.getByteArrayExtra(GlassPluginConstants.EXTRA_BINARY_DATA)

            onMessageFromPhone(GlassPluginMessage(type, payload, binary))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 27) {
            bindToHost()
        } else {
            registerBroadcastReceiver()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 27) {
            try { hostService?.unregisterGlassPlugin(pluginId) } catch (_: Exception) {}
            if (hostBound) {
                unbindService(hostConnection)
                hostBound = false
            }
        } else {
            try { unregisterReceiver(messageReceiver) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return pluginCallback.asBinder()
    }

    /** Send a message to the phone-side plugin */
    protected fun sendToPhone(message: GlassPluginMessage): Boolean {
        return if (Build.VERSION.SDK_INT >= 27) {
            sendViaAidl(message)
        } else {
            sendViaBroadcast(message)
        }
    }

    private fun sendViaAidl(message: GlassPluginMessage): Boolean {
        return try {
            hostService?.sendToPhone(pluginId, message) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "sendToPhone AIDL failed: ${e.message}")
            false
        }
    }

    private fun sendViaBroadcast(message: GlassPluginMessage): Boolean {
        return try {
            val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_TO_PHONE).apply {
                // Target the GlassHole base app — try EE1 first, then XE
                putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, pluginId)
                putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, message.type)
                putExtra(GlassPluginConstants.EXTRA_PAYLOAD, message.payload)
                if (message.binaryData != null) {
                    putExtra(GlassPluginConstants.EXTRA_BINARY_DATA, message.binaryData)
                }
            }
            // Try to send to whichever GlassHole base app is installed
            for (pkg in listOf("com.glasshole.glassee1", "com.glasshole.glassxe", "com.glasshole.glassee2")) {
                intent.setPackage(pkg)
                sendBroadcast(intent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendToPhone broadcast failed: ${e.message}")
            false
        }
    }

    private fun bindToHost() {
        // Try EE2 host first
        for (pkg in listOf("com.glasshole.glassee2", "com.glasshole.glassee1", "com.glasshole.glassxe")) {
            val intent = Intent().apply {
                setClassName(pkg, "$pkg.PluginHostService")
            }
            try {
                hostBound = bindService(intent, hostConnection, Context.BIND_AUTO_CREATE)
                if (hostBound) {
                    Log.i(TAG, "Bound to host: $pkg")
                    return
                }
            } catch (_: Exception) {}
        }
        Log.w(TAG, "Could not bind to any GlassHole host")
    }

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter(GlassPluginConstants.ACTION_MESSAGE_FROM_PHONE)
        registerReceiver(messageReceiver, filter)
        Log.i(TAG, "Registered broadcast receiver for plugin '$pluginId'")
    }
}
