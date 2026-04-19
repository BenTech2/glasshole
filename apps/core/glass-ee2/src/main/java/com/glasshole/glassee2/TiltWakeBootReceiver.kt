package com.glasshole.glassee2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-launches TiltWakeService on boot if the user previously enabled
 * tilt-to-wake from the phone companion app. Also re-launches
 * BluetoothListenerService so the glass app is ready for a new phone
 * connection without requiring the user to open anything manually.
 */
class TiltWakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Gate the BT listener auto-start on the user's preference. Default
        // to true so the first install "just works" — can be disabled from
        // the phone's Glass Settings toggle.
        val prefs = context.getSharedPreferences(
            BaseSettings.PREFS, Context.MODE_PRIVATE
        )
        if (prefs.getBoolean(BaseSettings.KEY_AUTO_START, true)) {
            try {
                context.startForegroundService(
                    Intent(context, BluetoothListenerService::class.java)
                )
                Log.i("GlassHoleBoot", "Auto-started BluetoothListenerService ($action)")
            } catch (e: Exception) {
                Log.e("GlassHoleBoot", "BluetoothListenerService start failed: ${e.message}")
            }
        } else {
            Log.i("GlassHoleBoot", "Auto-start disabled — skipping BT listener")
        }

        // Tilt-wake is opt-in, so check the persisted flag before launching.
        if (prefs.getBoolean(BaseSettings.KEY_TILT_WAKE, false)) {
            try {
                context.startForegroundService(Intent(context, TiltWakeService::class.java))
                Log.i("GlassHoleBoot", "Auto-started TiltWakeService")
            } catch (e: Exception) {
                Log.e("GlassHoleBoot", "TiltWakeService start failed: ${e.message}")
            }
        }
    }
}

/** Shared config for base-app settings on glass-ee2. */
object BaseSettings {
    const val PREFS = "glasshole_base_settings"
    const val KEY_TILT_WAKE = "tilt_wake_enabled"
    const val KEY_AUTO_START = "auto_start_enabled"
}
