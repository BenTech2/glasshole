// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skytrack.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler
import java.util.ArrayDeque

/**
 * BT bridge for SkyTrack. Phone-side TrackerPhonePlugin pushes
 * `AIRCRAFT_UPDATE` payloads here; the activity registers a listener
 * to receive them. Buffer the messages while no listener is set so
 * the cold-start race (launcher binds service → activity registers
 * listener) doesn't drop the first refresh.
 *
 * Outbound: the activity calls [requestStart] / [requestStop] on
 * resume / pause; the phone-side starts the polling loop accordingly.
 */
class SkyTrackPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "SkyTrackBridge"
        const val PREFS_NAME = "skytrack_settings"
        private const val BUFFER_CAPACITY = 4

        @Volatile @JvmStatic private var listener: Listener? = null
        private val pending = ArrayDeque<GlassPluginMessage>()
        @Volatile @JvmStatic private var instance: SkyTrackPluginService? = null

        /** Ask the phone-side to begin polling. Called from activity
         *  onResume; retries while the service is still binding. */
        @JvmStatic fun requestStart() = sendOrRetry(GlassPluginMessage("REQ_TRACKER_START", ""))
        @JvmStatic fun requestStop()  = sendOrRetry(GlassPluginMessage("REQ_TRACKER_STOP",  ""))

        private fun sendOrRetry(message: GlassPluginMessage) {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            val r = object : Runnable {
                var attempts = 10
                override fun run() {
                    val s = instance
                    if (s != null) {
                        try {
                            s.sendToPhone(message)
                            Log.i(TAG, "Sent ${message.type}")
                        } catch (e: Throwable) {
                            Log.w(TAG, "Send ${message.type} failed: ${e.message}")
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

    override val pluginId: String = "skytrack"

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
            }
        }
    }
}
