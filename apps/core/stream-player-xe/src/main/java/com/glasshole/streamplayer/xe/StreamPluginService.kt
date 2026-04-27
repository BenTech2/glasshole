package com.glasshole.streamplayer.xe

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
 * - PLAY_URL from phone → MainActivity → PlayerActivity (the original flow).
 * - PLAY / PAUSE / SEEK / SEEK_RELATIVE / NEXT / PREV / STOP from phone →
 *   routed to PlayerActivity.activeInstance so the phone-side Now Playing
 *   card on the GlassHole app can remote-control the glass player.
 * - PLAYBACK_STATE / PLAYBACK_END to phone (driven by PlayerActivity) so
 *   that card stays in sync without polling.
 */
class StreamPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "StreamPlayerPlugin"

        @Volatile
        var instance: StreamPluginService? = null
            private set
    }

    override val pluginId: String = "stream"

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d(TAG, "Message from phone: type=${message.type}")
        when (message.type) {
            "PLAY_URL" -> handlePlayUrl(message.payload)
            "PLAY", "PAUSE", "SEEK", "SEEK_RELATIVE", "NEXT", "PREV", "STOP" ->
                PlayerActivity.activeInstance?.handleCommand(message.type, message.payload)
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
        }
    }

    private fun handlePlayUrl(payload: String) {
        val url = try {
            JSONObject(payload).getString("url")
        } catch (e: Exception) {
            Log.e(TAG, "Bad PLAY_URL payload: ${e.message}")
            return
        }

        wakeScreen()

        Log.i(TAG, "Launching MainActivity with url=$url")
        // CLEAR_TASK on every share — otherwise a second PLAY_URL stacks
        // a second PlayerActivity on top of the first, and closing the new
        // one drops the user back into the still-playing previous video.
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_URL, url)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity: ${e.message}")
        }
    }

    fun sendPlaybackState(json: String) {
        sendToPhone(GlassPluginMessage("PLAYBACK_STATE", json))
    }

    fun sendPlaybackEnd() {
        sendToPhone(GlassPluginMessage("PLAYBACK_END", ""))
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
