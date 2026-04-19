package com.glasshole.plugin.notes.glass

import android.content.Context
import android.content.Intent

/**
 * Kicks the GlassHole base-app BluetoothListenerService so that opening a
 * plugin activity always revives BT if the base app was LRU-killed.
 */
object GlassBaseAppStarter {
    private val candidates = listOf(
        "com.glasshole.glassee1" to "com.glasshole.glassee1.BluetoothListenerService",
        "com.glasshole.glassxe" to "com.glasshole.glassxe.BluetoothListenerService",
        "com.glasshole.glassee2" to "com.glasshole.glassee2.BluetoothListenerService"
    )

    fun start(context: Context) {
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply { setClassName(pkg, cls) }
            try { context.startService(intent) } catch (_: Exception) {}
        }
    }
}
