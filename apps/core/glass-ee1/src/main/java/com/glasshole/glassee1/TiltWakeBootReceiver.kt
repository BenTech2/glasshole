package com.glasshole.glassee1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
                startServiceCompat(
                    context, Intent(context, BluetoothListenerService::class.java)
                )
                Log.i("GlassHoleBoot", "Auto-started BluetoothListenerService ($action)")
            } catch (e: Exception) {
                Log.e("GlassHoleBoot", "BluetoothListenerService start failed: ${e.message}")
            }
        } else {
            Log.i("GlassHoleBoot", "Auto-start disabled — skipping BT listener")
        }

        if (prefs.getBoolean(BaseSettings.KEY_TILT_WAKE, false)) {
            try {
                startServiceCompat(context, Intent(context, TiltWakeService::class.java))
                Log.i("GlassHoleBoot", "Auto-started TiltWakeService")
            } catch (e: Exception) {
                Log.e("GlassHoleBoot", "TiltWakeService start failed: ${e.message}")
            }
        }
    }

    private fun startServiceCompat(context: Context, intent: Intent) {
        // startForegroundService is API 26+; on EE1's 4.4.4 use the plain
        // startService path (background-service restrictions weren't in
        // force yet, so this still puts the service in the foreground once
        // startForeground() is called from onCreate).
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

/** Shared config for base-app settings on glass-ee1. */
object BaseSettings {
    const val PREFS = "glasshole_base_settings"
    const val KEY_TILT_WAKE = "tilt_wake_enabled"
    const val KEY_AUTO_START = "auto_start_enabled"
    /** While the Nav card is visible on Home, keep the display on (suppress
     *  the idle-dim timer). */
    const val KEY_NAV_KEEP_SCREEN_ON = "nav_keep_screen_on"
    /** Acquire a brief SCREEN_BRIGHT wake lock whenever a NAV_UPDATE arrives,
     *  so the display wakes to show the new turn even if it was sleeping. */
    const val KEY_NAV_WAKE_ON_UPDATE = "nav_wake_on_update"
    /** When set, the Home carousel snaps back to the Time card on every
     *  screen-on event so the user always re-enters from a known position. */
    const val KEY_WAKE_TO_TIME_CARD = "wake_to_time_card"
}
