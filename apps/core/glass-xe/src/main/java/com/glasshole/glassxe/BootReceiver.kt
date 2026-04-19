package com.glasshole.glassxe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-start BluetoothListenerService on boot and after the app is
 * reinstalled / updated so the user never has to open the launcher tile
 * manually. API 19 has loose background service rules — plain startService
 * from a receiver is allowed.
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

object BaseSettings {
    const val PREFS = "glasshole_base_settings"
    const val KEY_AUTO_START = "auto_start_enabled"
}
