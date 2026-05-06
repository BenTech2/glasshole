package com.glasshole.plugin.ssh.glass

import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler
import org.json.JSONObject

/**
 * Glass-side SSH plugin service. Routes plugin-protocol envelopes:
 *
 *   SET_PROFILES   — phone pushes the full profile snapshot. We
 *                    persist it via [ProfileStore] so the glass picker
 *                    and any later OPEN can resolve ids offline of the
 *                    phone.
 *   OPEN           — phone "Quick Connect": payload {"id":"..."}.
 *                    Foregrounds [TerminalActivity] with the profile id
 *                    so it skips the manual-creds dialog and dials the
 *                    remote directly.
 *   PROFILES_REQ   — sent by the glass on connect to ask the phone to
 *                    push its current snapshot (so a fresh device pick
 *                    up the latest profiles without an edit).
 */
class SshPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "SshGlassPlugin"
        const val PREFS_NAME = "ssh_settings"
        const val EXTRA_BROADCAST_TYPE = "broadcast_type"
        const val EXTRA_BROADCAST_PAYLOAD = "broadcast_payload"
    }

    override val pluginId: String = "ssh"

    private val profileStore by lazy { ProfileStore(this) }

    private val configHandler by lazy {
        PluginConfigHandler(
            context = this,
            prefsName = PREFS_NAME,
            schemaResId = R.raw.plugin_schema,
            send = { type, payload ->
                sendToPhone(GlassPluginMessage(type, payload))
            }
        )
    }

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d(TAG, "Message from phone: type=${message.type}")
        if (configHandler.handle(message)) return
        when (message.type) {
            "SET_PROFILES" -> {
                profileStore.saveSnapshot(message.payload)
                Log.i(TAG, "Saved ${profileStore.list().size} profiles")
            }
            "OPEN" -> handleOpen(message.payload)
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
        }
    }

    /** Manifest-receiver wake path. KitKat (EE1 / XE) kills our process
     *  under memory pressure, which drops the SDK's dynamic broadcast
     *  receiver; [SshWakeReceiver] then forwards the message to us via
     *  startService so we get to act on it. Returning START_STICKY also
     *  asks the OS to bring us back if it kills us mid-session. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = intent?.getStringExtra(EXTRA_BROADCAST_TYPE)
        val payload = intent?.getStringExtra(EXTRA_BROADCAST_PAYLOAD)
        if (type != null) {
            onMessageFromPhone(GlassPluginMessage(type, payload ?: ""))
        }
        return START_STICKY
    }

    private fun handleOpen(payload: String) {
        val id = try { JSONObject(payload).optString("id") } catch (_: Exception) { "" }
        if (id.isEmpty()) {
            Log.w(TAG, "OPEN with no profile id")
            return
        }
        if (profileStore.get(id) == null) {
            Log.w(TAG, "OPEN with unknown profile id $id")
            return
        }
        // Wake the display so a phone-side Quick Connect surfaces the
        // terminal even when the glass is dozing. The activity itself
        // then declares showWhenLocked + turnScreenOn to keep it lit
        // past the wake lock's brief lifetime.
        wakeScreen()
        startActivity(Intent(this, TerminalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(TerminalActivity.EXTRA_PROFILE_ID, id)
        })
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassHole:SshOpenWake"
            )
            wl.acquire(3_000L)
        } catch (e: Exception) {
            Log.w(TAG, "wakeScreen failed: ${e.message}")
        }
    }
}
