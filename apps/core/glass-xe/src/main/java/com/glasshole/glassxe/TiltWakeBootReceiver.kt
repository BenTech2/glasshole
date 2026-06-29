package com.glasshole.glassxe

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
    /** User-facing inversion toggle for navigation gestures across
     *  every base-app cover-flow / drawer surface. Read at gesture-time
     *  so flipping the toggle on the phone takes effect immediately. */
    const val KEY_INVERT_NAV = "invert_nav"
    /** Mirrors the system "Stay awake" developer toggle. See the EE2
     *  copy of this file for the WRITE_SECURE_SETTINGS adb-grant
     *  command. */
    const val KEY_STAY_AWAKE_WHEN_CHARGING = "stay_awake_when_charging"
    /** Alpha (0..255) of the black overlay drawn on top of the
     *  user-uploaded wallpaper on Home. 0 = wallpaper at full brightness,
     *  255 = wallpaper fully hidden (solid black). Driven by the
     *  phone-side fade slider in DeviceActivity. */
    const val KEY_BACKGROUND_FADE = "background_fade"
    /** How the wallpaper ImageView scales the source: "fit" (default —
     *  whole image visible, letterboxed), "zoom" (centerCrop — fills
     *  screen by cropping), "stretch" (fitXY — fills screen, distorts
     *  aspect). User-driven via the phone-side wallpaper section. */
    const val KEY_WALLPAPER_SCALE_MODE = "wallpaper_scale_mode"
    /** When true, the battery indicator on the Time card shows the
     *  numeric percent next to the icon. When false, just the icon. */
    const val KEY_SHOW_BATTERY_PERCENT = "show_battery_percent"
    /** When true, the top-bar status row is mirrored: battery moves to
     *  the top-left, phone+Wi-Fi icons to the top-right. Default off. */
    const val KEY_SWAP_TOP_BAR = "swap_top_bar"
    /** When true, show the same Home wallpaper behind the Settings
     *  cover-flow drawer (with the same fade). Default off — the drawer
     *  is text-heavy and not everyone wants the contrast variability. */
    const val KEY_WALLPAPER_ON_SETTINGS = "wallpaper_on_settings"
    /** When true, show the same Home wallpaper behind the App cover-flow
     *  drawer. Same reasoning as above. */
    const val KEY_WALLPAPER_ON_APP_DRAWER = "wallpaper_on_app_drawer"
    /** When true, ring the ToneGenerator beep behind the notification
     *  popup. Off lets the visual card alone do the alerting. */
    const val KEY_NOTIF_SOUND_ENABLED = "notif_sound_enabled"
    /** Beep loudness in the 0..100 range ToneGenerator's constructor
     *  takes directly. 0 is equivalent to disabling sound; we still
     *  honour the enabled toggle as a separate "remember my volume"
     *  affordance. */
    const val KEY_NOTIF_SOUND_VOLUME = "notif_sound_volume"
    /** Comma-separated list of package names the user has pinned to the
     *  front of the App Drawer. Capped at 4 — entries past the 4th are
     *  ignored. Set by the phone-side PinnedAppsActivity via
     *  SET_PINNED_APPS; read by AppDrawerActivity on every load. */
    const val KEY_PINNED_APPS = "pinned_apps"
    const val PINNED_APPS_MAX = 4
    /** Debug-mode stats overlay on the Home time card — CPU / RAM /
     *  thermal one-liner pinned top-center. Phone-side debug screen
     *  toggles this on. Default off. */
    const val KEY_SHOW_STATS_OVERLAY = "show_stats_overlay"
    /** "F" or "C" — temperature unit for the stats overlay. */
    const val KEY_STATS_TEMP_UNIT = "stats_temp_unit"

    fun isNavInverted(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_INVERT_NAV, false)

    /** Returns the user's pinned package list, in pin order, capped to
     *  [PINNED_APPS_MAX] and de-duplicated. Empty list if nothing pinned. */
    fun getPinnedPackages(context: android.content.Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_PINNED_APPS, "") ?: ""
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(PINNED_APPS_MAX)
    }
}
