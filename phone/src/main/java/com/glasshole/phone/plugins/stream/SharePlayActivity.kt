package com.glasshole.phone.plugins.stream

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.widget.Toast
import com.glasshole.phone.AppLog
import com.glasshole.phone.service.PluginHostService

/**
 * Headless activity that receives share-sheet intents (e.g. from YouTube),
 * extracts a URL, and forwards it to the glass stream plugin over BT.
 */
class SharePlayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.log(
            TAG,
            "Share received: action=${intent?.action} type=${intent?.type} " +
                "pkg=${intent?.getStringExtra(Intent.EXTRA_REFERRER_NAME) ?: callingPackage ?: "unknown"}"
        )

        val rawText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)
        if (!subject.isNullOrEmpty()) AppLog.log(TAG, "Share subject: $subject")
        if (!rawText.isNullOrEmpty()) {
            AppLog.log(
                TAG,
                "Share text: ${rawText.take(120)}${if (rawText.length > 120) "…" else ""}"
            )
        }

        val url = extractUrl(intent)
        if (url == null) {
            AppLog.warn(TAG, "No URL found in share — action=${intent?.action}")
            toast("No URL found in share")
            finish()
            return
        }
        AppLog.log(TAG, "Extracted URL: $url (${identifyPlatform(url)})")

        // Ensure the host service is up; it will start BridgeService and wire sending.
        startService(Intent(this, PluginHostService::class.java))

        // Retry briefly in case the host service / bridge is still spinning up.
        attemptSend(url, attemptsLeft = 10)
    }

    private fun attemptSend(url: String, attemptsLeft: Int) {
        val plugin = StreamPlugin.instance
        val sent = plugin?.sendUrl(url) ?: false
        if (sent) {
            AppLog.log(TAG, "URL forwarded to glass: $url")
            toast("Sent to Glass")
            finish()
            return
        }
        if (attemptsLeft <= 0) {
            val msg = if (plugin == null) "GlassHole service not running"
                      else "Glass not connected"
            AppLog.warn(TAG, "Share aborted: $msg")
            toast(msg)
            finish()
            return
        }
        AppLog.log(TAG, "Share retry in 200ms (${attemptsLeft - 1} left)")
        Handler(Looper.getMainLooper()).postDelayed({
            attemptSend(url, attemptsLeft - 1)
        }, 200)
    }

    private fun extractUrl(intent: Intent?): String? {
        intent ?: return null
        if (intent.action != Intent.ACTION_SEND) return null

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return null
        val matcher = Patterns.WEB_URL.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    private fun identifyPlatform(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "youtube"
            lower.contains("twitch.tv") -> "twitch"
            lower.contains(".m3u8") -> "hls"
            else -> "other"
        }
    }

    companion object {
        private const val TAG = "Share"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
