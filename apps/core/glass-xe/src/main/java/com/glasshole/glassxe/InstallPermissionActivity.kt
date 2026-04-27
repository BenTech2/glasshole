package com.glasshole.glassxe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Shown when an APK arrives from the phone but "Unknown Sources" is
 * disabled on the headset. Mirrors the device-admin prompt from
 * HomeActivity: one friendly dialog that explains why, a button that
 * jumps to Security settings, and a Skip fallback.
 *
 * EE1 runs Android 4.4.4 (API 19), so this predates the per-app
 * REQUEST_INSTALL_PACKAGES model — Unknown Sources is a single global
 * checkbox under Settings → Security.
 */
class InstallPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AlertDialog.Builder(this)
            .setTitle("Allow installs?")
            .setMessage(
                "To install APKs sent from your phone, Glass needs " +
                "\"Unknown Sources\" enabled.\n\n" +
                "Tap Enable, turn it on under Security, then retry the install."
            )
            .setCancelable(false)
            .setPositiveButton("Enable") { _, _ ->
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(intent) } catch (_: Exception) {}
                finish()
            }
            .setNegativeButton("Skip") { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }
}
