package com.glasshole.plugin.ssh.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginConstants

/**
 * Manifest-declared receiver that wakes [SshPluginService] when an
 * SSH-targeted broadcast lands while the process is dead.
 *
 * On EE1 / XE the host falls back to broadcasts (no AIDL), and on
 * KitKat the SDK's dynamic in-service receiver dies whenever the OS
 * kills the plugin process under memory pressure. Quick-Connect from
 * the phone then hits a forwarded ACTION_MESSAGE_FROM_PHONE that
 * nobody is listening for — the terminal never opens.
 *
 * A manifest receiver is the right tool: Android resurrects the
 * process to deliver the broadcast, and we use that opportunity to
 * `startService` so the plugin is alive long enough to handle the
 * payload (and stays alive for the next message thanks to
 * START_STICKY).
 */
class SshWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SshWakeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pluginId = intent.getStringExtra(GlassPluginConstants.EXTRA_PLUGIN_ID) ?: return
        if (pluginId != "ssh") return
        val type = intent.getStringExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE) ?: ""
        val payload = intent.getStringExtra(GlassPluginConstants.EXTRA_PAYLOAD) ?: ""
        Log.d(TAG, "Waking SshPluginService for type=$type")
        val svc = Intent(context, SshPluginService::class.java).apply {
            putExtra(SshPluginService.EXTRA_BROADCAST_TYPE, type)
            putExtra(SshPluginService.EXTRA_BROADCAST_PAYLOAD, payload)
        }
        try { context.startService(svc) } catch (e: Exception) {
            Log.w(TAG, "startService failed: ${e.message}")
        }
    }
}
