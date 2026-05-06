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
    /** While the Nav card is visible on Home, keep the display on (suppress
     *  the idle-dim timer). */
    const val KEY_NAV_KEEP_SCREEN_ON = "nav_keep_screen_on"
    /** Acquire a brief SCREEN_BRIGHT wake lock whenever a NAV_UPDATE arrives,
     *  so the display wakes to show the new turn even if it was sleeping. */
    const val KEY_NAV_WAKE_ON_UPDATE = "nav_wake_on_update"
    /** When set, the Home carousel snaps back to the Time card on every
     *  screen-on event so the user always re-enters from a known position. */
    const val KEY_WAKE_TO_TIME_CARD = "wake_to_time_card"
    /** User-facing inversion toggle for navigation gestures across
     *  every base-app cover-flow / drawer surface (Home, Settings, App
     *  drawer, Notification drawer). When true, swipe-forward / TAB /
     *  DPAD_RIGHT all navigate to the PREVIOUS item instead of the
     *  next one. Read at gesture-time so flipping the toggle on the
     *  phone takes effect immediately without restarting activities. */
    const val KEY_INVERT_NAV = "invert_nav"
    /** When true, push Settings.Global.STAY_ON_WHILE_PLUGGED_IN to
     *  AC|USB|WIRELESS so the glass display never blanks while charging.
     *  Equivalent to the system "Stay awake" developer toggle. Requires
     *  WRITE_SECURE_SETTINGS, granted via:
     *      adb shell pm grant com.glasshole.glassee2.launcher \
     *          android.permission.WRITE_SECURE_SETTINGS
     *  (and the matching standalone applicationId where applicable). */
    const val KEY_STAY_AWAKE_WHEN_CHARGING = "stay_awake_when_charging"

    /** Convenience accessor for the invert flag — every nav surface
     *  reads this once per gesture; the cost of a SharedPreferences
     *  hit is negligible compared to the gesture-rate. */
    fun isNavInverted(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_INVERT_NAV, false)
}
