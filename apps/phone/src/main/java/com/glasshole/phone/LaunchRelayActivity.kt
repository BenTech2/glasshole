package com.glasshole.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Transparent no-UI activity used to launch other apps when the request
 * originates from our NotificationListenerService. On Android 14+ a
 * background foreground-service cannot bring another app's existing task
 * to the foreground — but a visible Activity can. We start this relay
 * with same-UID (which is allowed), and it immediately calls startActivity
 * for the target package, then finishes itself.
 */
class LaunchRelayActivity : Activity() {

    companion object {
        private const val TAG = "LaunchRelay"
        const val EXTRA_TARGET_PKG = "target_pkg"
        const val EXTRA_TARGET_URL = "target_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_TARGET_PKG)
        val url = intent.getStringExtra(EXTRA_TARGET_URL)

        try {
            val launched = when {
                !url.isNullOrEmpty() -> launchUrl(url, pkg)
                !pkg.isNullOrEmpty() -> launchPackage(pkg)
                else -> false
            }
            if (!launched && !pkg.isNullOrEmpty()) {
                // URL route failed — fall back to the plain launcher intent
                launchPackage(pkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Relay launch failed: ${e.message}")
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun launchUrl(url: String, preferredPkg: String?): Boolean {
        return try {
            val viewIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Prefer routing to the notification's source app first so
                // YouTube videos open in YouTube, Twitch in Twitch, etc.
                if (!preferredPkg.isNullOrEmpty() &&
                    packageManager.getLaunchIntentForPackage(preferredPkg) != null) {
                    setPackage(preferredPkg)
                }
            }
            startActivity(viewIntent)
            Log.i(TAG, "Launched URL $url via ${preferredPkg ?: "default handler"}")
            true
        } catch (e: android.content.ActivityNotFoundException) {
            // Preferred package couldn't handle the URL — retry without the
            // package constraint so Android picks any handler.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Log.i(TAG, "Launched URL $url via default handler (fallback)")
                true
            } catch (e2: Exception) {
                Log.w(TAG, "URL launch failed: ${e2.message}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "URL launch failed: ${e.message}")
            false
        }
    }

    private fun launchPackage(pkg: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent == null) {
                Log.w(TAG, "No launcher intent for $pkg")
                return false
            }
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(launchIntent)
            Log.i(TAG, "Relayed launch for $pkg")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Package launch failed for $pkg: ${e.message}")
            false
        }
    }
}
