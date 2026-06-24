// SPDX-License-Identifier: GPL-3.0-or-later
// Replaces upstream GlassNav's BluetoothMapsService (which opens an
// RFCOMM socket to a separate Companion APK). This service plugs into
// the GlassHole BT bridge: phone-side companion sends
//   PLUGIN:glassnav:LOC:<base64 32-byte payload>
//   PLUGIN:glassnav:DEST:{"n":"...","dn":"...","la":..,"lo":..,"di":..}
// which we hand to MainActivity's existing bluetoothHandler unchanged.
// All the binary / JSON parsing in MainActivity stays exactly as
// upstream — we only swap the transport layer.
package com.cato.glassnav

import android.util.Base64
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler
import java.util.ArrayDeque

class GlassNavPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "GlassNavBridge"
        const val PREFS_NAME = "glassnav_settings"

        /** Max number of buffered messages while waiting for the
         *  listener to register. Just needs to be big enough to hold
         *  one DEST + a handful of LOC packets that arrive in the
         *  ~1–2 s window before MainActivity finishes onCreate. */
        private const val BUFFER_CAPACITY = 32

        /**
         * Set by MainActivity from its onCreate listener registration.
         * @Volatile so the binder-thread reads see the UI-thread write.
         */
        @Volatile @JvmStatic private var listener: Listener? = null

        /** Holds messages that arrived before [listener] was set. The
         *  share flow fires LAUNCH_PACKAGE + DEST back-to-back; the
         *  glass takes 1–2 s to cold-start MainActivity, during which
         *  the DEST would otherwise be silently dropped. Flushed in
         *  FIFO order when the listener registers. */
        private val pending = ArrayDeque<ByteArray>()

        /** Live reference to the running service instance — only the
         *  service can fire plugin messages back to the phone, so
         *  MainActivity uses this to send REQ_GPS_START/STOP without
         *  having to bind the service itself. Null when the service
         *  is unbound (which happens between AIDL teardown and the
         *  next plugin host reconnect). */
        @Volatile @JvmStatic private var instance: GlassNavPluginService? = null

        /** Ask the phone to (re-)start GPS streaming over BT. Called
         *  from MainActivity.onResume so any time the user opens
         *  GlassNav — by share, app drawer, voice trigger, anything —
         *  the phone GPS pipeline kicks on. Phone-side
         *  GlassNavPhonePlugin catches the message + calls
         *  SpeedTracker.start.
         *
         *  Retry: when MainActivity cold-starts the plugin process,
         *  its onResume fires before the launcher has finished binding
         *  this service — `instance` is null and a one-shot send would
         *  silently no-op. We retry every 500 ms for ~5 s; the service
         *  binding always lands well inside that window. */
        @JvmStatic
        fun requestGpsStreamStart() {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            val r = object : Runnable {
                var attempts = 10
                override fun run() {
                    val s = instance
                    if (s != null) {
                        try {
                            s.sendToPhone(GlassPluginMessage("REQ_GPS_START", ""))
                            Log.i(TAG, "REQ_GPS_START sent")
                        } catch (e: Throwable) {
                            Log.w(TAG, "REQ_GPS_START send failed: ${e.message}")
                        }
                    } else if (--attempts > 0) {
                        h.postDelayed(this, 500L)
                    } else {
                        Log.w(TAG, "REQ_GPS_START: service never bound, giving up")
                    }
                }
            }
            r.run()
        }

        /** Called from MainActivity.onCreate (and onDestroy with null
         *  to detach). Drains any buffered messages immediately so
         *  pre-listener DEST/LOC packets actually reach the activity. */
        @JvmStatic
        fun setListener(l: Listener?) {
            synchronized(pending) {
                listener = l
                if (l != null) {
                    val drained = ArrayList<ByteArray>(pending.size)
                    while (pending.isNotEmpty()) drained.add(pending.removeFirst())
                    if (drained.isNotEmpty()) {
                        Log.i(TAG, "Flushing ${drained.size} buffered message(s) to fresh listener")
                        drained.forEach { l.onPhoneData(it) }
                    }
                }
            }
        }
    }

    private val configHandler by lazy {
        // Drives the dynamic settings UI on the phone: SCHEMA_REQ /
        // SCHEMA_RESP for layout + CONFIG_READ / CONFIG_WRITE for
        // the key/value store at `glassnav_settings` SharedPreferences.
        // MainActivity reads keys like `keep_screen_on` from the same
        // prefs file.
        PluginConfigHandler(
            context = this,
            prefsName = PREFS_NAME,
            schemaResId = R.raw.plugin_schema,
            send = { type, payload ->
                sendToPhone(GlassPluginMessage(type, payload))
            }
        )
    }

    fun interface Listener {
        /** Hand a raw payload to MainActivity exactly the way the
         *  upstream BluetoothMapsService's ConnectedThread did:
         *  arbitrary bytes that MainActivity then either parses as
         *  JSON (if starts with '{') or as the 32-byte binary
         *  location format. */
        fun onPhoneData(bytes: ByteArray)
    }

    override val pluginId: String = "glassnav"

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        // Phone settings UI traffic (SCHEMA_REQ / CONFIG_*) — handled
        // before we look for a listener since these arrive whether or
        // not MainActivity is foregrounded.
        if (configHandler.handle(message)) return

        val bytes: ByteArray = when (message.type) {
            // JSON destination — payload IS the JSON string, send the
            // UTF-8 bytes straight through (MainActivity does
            // `new String(payload, 0, msg.arg1)` and JSON-parses).
            "DEST", "DESTINATION" -> message.payload.toByteArray(Charsets.UTF_8)
            // Binary 32-byte location envelope (lat/lon/alt double +
            // speed/bearing float, big-endian). Base64-encoded for
            // transport because our BT bridge passes Strings only.
            "LOC", "LOCATION" -> try {
                Base64.decode(message.payload, Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.w(TAG, "LOC base64 decode failed: ${e.message}"); return
            }
            else -> {
                Log.d(TAG, "Unknown message type: ${message.type}")
                return
            }
        }

        // Dispatch under the same lock that protects the buffer flush
        // so a setListener() racing with onMessageFromPhone can't lose
        // or duplicate a message.
        synchronized(pending) {
            val cb = listener
            if (cb != null) {
                cb.onPhoneData(bytes)
            } else {
                if (pending.size >= BUFFER_CAPACITY) pending.removeFirst()
                pending.addLast(bytes)
                Log.d(TAG, "Buffered ${message.type} (no listener yet, depth=${pending.size})")
            }
        }
    }
}
