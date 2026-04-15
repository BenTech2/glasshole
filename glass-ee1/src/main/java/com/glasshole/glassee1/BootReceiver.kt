package com.glasshole.glassee1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-start BluetoothListenerService on boot and after the app is
 * reinstalled / updated. API 19 has loose background service rules so
 * a plain startService from the receiver is fine.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences(
            BaseSettings.PREFS, Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean(BaseSettings.KEY_AUTO_START, true)) {
            Log.i("GlassHoleBoot", "Auto-start disabled — skipping ($action)")
            return
        }

        try {
            context.startService(Intent(context, BluetoothListenerService::class.java))
            Log.i("GlassHoleBoot", "Auto-started BluetoothListenerService ($action)")
        } catch (e: Exception) {
            Log.e("GlassHoleBoot", "Auto-start failed: ${e.message}")
        }
    }
}

/** Shared base-app prefs (kept outside the receiver so the BT listener can reuse the keys). */
object BaseSettings {
    const val PREFS = "glasshole_base_settings"
    const val KEY_AUTO_START = "auto_start_enabled"
}
