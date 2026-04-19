package com.glasshole.plugin.openclaw.glass

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.TextView

/**
 * Entry point shown when the user opens OpenClaw from the glass launcher.
 * Fires a KICKSTART broadcast at the phone plugin and auto-dismisses — the
 * actual conversation continues on the phone in the Telegram app, so there's
 * nothing to do on the glass side beyond telling the user it worked.
 */
class OpenClawActivity : Activity() {

    companion object {
        private const val TAG = "OpenClawActivity"
        private const val AUTO_DISMISS_MS = 2500L
    }

    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_openclaw)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText = findViewById(R.id.statusText)
        hintText = findViewById(R.id.hintText)

        sendKickstart()
        handler.postDelayed({ finish() }, AUTO_DISMISS_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun sendKickstart() {
        try {
            val payload = org.json.JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
            }.toString()

            val intent = Intent("com.glasshole.glass.MESSAGE_TO_PHONE").apply {
                putExtra("plugin_id", "openclaw")
                putExtra("message_type", "KICKSTART")
                putExtra("payload", payload)
            }
            for (pkg in listOf(
                "com.glasshole.glassee1",
                "com.glasshole.glassxe",
                "com.glasshole.glassee2"
            )) {
                intent.setPackage(pkg)
                sendBroadcast(intent)
            }

            statusText.text = "Chat started"
            hintText.text = "Reply in Telegram on phone"
        } catch (e: Exception) {
            Log.e(TAG, "Kickstart broadcast failed: ${e.message}")
            statusText.text = "Not connected"
            hintText.text = "Phone isn't reachable"
        }
    }
}
