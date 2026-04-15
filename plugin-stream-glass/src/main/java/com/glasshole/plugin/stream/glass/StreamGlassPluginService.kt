package com.glasshole.plugin.stream.glass

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import org.json.JSONObject

class StreamGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "StreamGlassPlugin"

        // Stream-viewer variants, in preferred order. Each package has its own
        // MainActivity class inside its own subpackage.
        private val VIEWERS = listOf(
            "com.glasscode.twitchviewer.ee2" to "com.glasscode.twitchviewer.ee2.MainActivity",
            "com.glasscode.twitchviewer.ee1" to "com.glasscode.twitchviewer.ee1.MainActivity",
            "com.glasscode.twitchviewer.explorer" to "com.glasscode.twitchviewer.explorer.MainActivity"
        )
        private const val ACTION_PLAY_URL = "com.glasscode.twitchviewer.ACTION_PLAY_URL"
        private const val EXTRA_URL = "com.glasscode.twitchviewer.EXTRA_URL"
    }

    override val pluginId: String = "stream"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d(TAG, "Message from phone: type=${message.type}")
        when (message.type) {
            "PLAY_URL" -> handlePlayUrl(message.payload)
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

        val pm = packageManager
        val target = VIEWERS.firstOrNull { (pkg, _) ->
            try {
                pm.getPackageInfo(pkg, 0); true
            } catch (_: Exception) { false }
        }

        if (target == null) {
            Log.e(TAG, "No glass-stream-viewer app installed")
            return
        }
        val (targetPkg, targetActivity) = target

        // Wake the screen BEFORE launching so the viewer isn't started into a black
        // screen that the user can't see. Hold the lock briefly across the launch;
        // the viewer's PlayerActivity sets FLAG_KEEP_SCREEN_ON on its own window.
        wakeScreen()

        Log.i(TAG, "Launching $targetPkg/$targetActivity with url=$url")
        val intent = Intent(ACTION_PLAY_URL).apply {
            setClassName(targetPkg, targetActivity)
            putExtra(EXTRA_URL, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch viewer: ${e.message}")
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
                "GlassHoleStream:Wake"
            )
            // Hold for 5s — enough time for the viewer activity to take over the
            // screen with its own FLAG_KEEP_SCREEN_ON.
            wl.acquire(5_000)
            Log.i(TAG, "Screen wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Wake lock failed: ${e.message}")
        }
    }
}
