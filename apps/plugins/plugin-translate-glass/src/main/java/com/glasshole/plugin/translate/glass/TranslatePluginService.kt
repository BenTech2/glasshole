// SPDX-License-Identifier: MIT
package com.glasshole.plugin.translate.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler
import java.util.ArrayDeque

/**
 * BT bridge for the Translate plugin. Mirrors the GlassNav pattern:
 *  - TranslateActivity registers a listener via [setListener]
 *  - Outbound TRANSLATE_REQUEST messages go through [send]
 *  - Inbound TRANSLATE_RESULT / TRANSLATE_ERROR messages are
 *    forwarded to the listener (or buffered until it registers,
 *    so cold-start races don't drop the result)
 *  - Dynamic plugin settings flow (SCHEMA_REQ / CONFIG_*) is
 *    handled by [PluginConfigHandler] against the `translate_settings`
 *    SharedPreferences, which the phone-side picks up as its source
 *    of truth.
 */
class TranslatePluginService : GlassPluginService() {

    companion object {
        private const val TAG = "TranslateBridge"
        const val PREFS_NAME = "translate_settings"
        private const val BUFFER_CAPACITY = 8

        @Volatile @JvmStatic private var listener: Listener? = null
        private val pending = ArrayDeque<GlassPluginMessage>()
        @Volatile @JvmStatic private var instance: TranslatePluginService? = null

        /** Activity-facing send helper. Retries while the service is
         *  still binding (cold-start: onResume fires before the
         *  launcher's plugin host has bound this service). */
        @JvmStatic
        fun sendToPhoneFromActivity(message: GlassPluginMessage) {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            val r = object : Runnable {
                var attempts = 20
                override fun run() {
                    val s = instance
                    if (s != null) {
                        try {
                            s.sendToPhone(message)
                            Log.i(TAG, "Sent ${message.type} (${message.payload.length} chars)")
                        } catch (e: Throwable) {
                            Log.w(TAG, "Send failed: ${e.message}")
                        }
                    } else if (--attempts > 0) {
                        h.postDelayed(this, 250L)
                    } else {
                        Log.w(TAG, "${message.type}: service never bound, giving up")
                    }
                }
            }
            r.run()
        }

        @JvmStatic
        fun setListener(l: Listener?) {
            synchronized(pending) {
                listener = l
                if (l != null) {
                    val drained = ArrayList<GlassPluginMessage>(pending.size)
                    while (pending.isNotEmpty()) drained.add(pending.removeFirst())
                    if (drained.isNotEmpty()) {
                        Log.i(TAG, "Flushing ${drained.size} buffered message(s)")
                        drained.forEach { l.onPhoneMessage(it) }
                    }
                }
            }
        }
    }

    fun interface Listener {
        fun onPhoneMessage(message: GlassPluginMessage)
    }

    private val configHandler by lazy {
        PluginConfigHandler(
            context = this,
            prefsName = PREFS_NAME,
            schemaResId = R.raw.plugin_schema,
            send = { type, payload -> sendToPhone(GlassPluginMessage(type, payload)) }
        )
    }

    override val pluginId: String = "translate"

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        if (configHandler.handle(message)) return

        synchronized(pending) {
            val cb = listener
            if (cb != null) {
                cb.onPhoneMessage(message)
            } else {
                if (pending.size >= BUFFER_CAPACITY) pending.removeFirst()
                pending.addLast(message)
                Log.d(TAG, "Buffered ${message.type} (no listener, depth=${pending.size})")
            }
        }
    }
}
