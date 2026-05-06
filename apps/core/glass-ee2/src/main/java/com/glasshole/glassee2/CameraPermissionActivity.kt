package com.glasshole.glassee2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Same pattern as [GalleryPermissionActivity] but for CAMERA — used by
 * the live-camera debug stream. Shows the system runtime permission
 * dialog, then finishes. Launched by BluetoothListenerService when a
 * LIVE_CAM_START arrives and CAMERA hasn't been granted yet.
 */
class CameraPermissionActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera already granted", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (ok) "Camera granted — retry from phone" else "Camera denied",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
