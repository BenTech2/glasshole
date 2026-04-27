package com.glasshole.glassee2

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Shown when an APK arrives from the phone but REQUEST_INSTALL_PACKAGES
 * hasn't been granted. Mirrors the device-admin prompt in HomeActivity:
 * one friendly dialog that explains why, a button that jumps straight to
 * the right settings page, and a Skip fallback.
 *
 * This doesn't itself retry the install — after granting, the user taps
 * "Send APK" on the phone again.
 */
class InstallPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AlertDialog.Builder(this)
            .setTitle("Allow installs?")
            .setMessage(
                "To install APKs sent from your phone, GlassHole needs " +
                "permission to install from unknown sources.\n\n" +
                "Tap Enable, toggle GlassHole on, then retry the install."
            )
            .setCancelable(false)
            .setPositiveButton("Enable") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
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
