package com.glasshole.plugin.camera2.glass

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime-permission shim for [Camera2PluginService.PERM_DISABLE_LED].
 * Fires ActivityCompat.requestPermissions and toasts the result.
 *
 * On stock Glass EE2 the permission is `signature|privileged` so the
 * system silently denies — but the activity is still useful because:
 *   • it shows the user we tried, with a clear toast explaining why
 *     the deny happened,
 *   • the same entry point works on any future platform-signed /
 *     rooted build that can actually be granted the permission.
 */
class CameraLedPermissionActivity : Activity() {
    companion object {
        private const val REQ = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perm = Camera2PluginService.PERM_DISABLE_LED
        if (ContextCompat.checkSelfPermission(this, perm) ==
            PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "LED control already granted", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(perm), REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (granted) "LED control granted"
            else "LED control denied — needs platform signing or root",
            Toast.LENGTH_LONG
        ).show()
        // Re-emit the LED status so the phone UI updates without polling.
        Camera2PluginService.requestLedStatusBroadcast(this)
        finish()
    }
}
