package com.glasshole.streamplayer.ee1

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import org.json.JSONObject

/**
 * GlassHole plugin entry point for the Stream Player.
 *
 * When the phone shares a URL via the stream plugin, the message arrives here as
 * PLAY_URL and we hand it to our local MainActivity via EXTRA_URL —
 * no inter-app Intent hop required because the plugin service and the
 * player live in the same process.
 */
class StreamPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "StreamPlayerPlugin"
    }

    override val pluginId: String = "stream"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d(TAG, "Message from phone: type=${message.type}")
        if (message.type != "PLAY_URL") {
            Log.w(TAG, "Unknown message type: ${message.type}")
            return
        }

        val url = try {
            JSONObject(message.payload).getString("url")
        } catch (e: Exception) {
            Log.e(TAG, "Bad PLAY_URL payload: ${e.message}")
            return
        }

        wakeScreen()

        Log.i(TAG, "Launching MainActivity with url=$url")
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_URL, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "StreamPlayer:Wake"
            )
            // Hold long enough for MainActivity to take over with its own keep-screen-on flag.
            wl.acquire(5_000)
            Log.i(TAG, "Screen wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Wake lock failed: ${e.message}")
        }
    }
}
