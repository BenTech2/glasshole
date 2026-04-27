package com.glasshole.glassee2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Lifted from the retired plugin-gallery-glass APK. Shows the system
 * storage permission dialog then finishes — gives users a way to grant
 * runtime permissions for Gallery without adb, since the base-app
 * gallery handler has no other UI.
 */
class GalleryPermissionActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (needed.isEmpty()) {
            Toast.makeText(this, "Gallery: storage access already granted", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        Toast.makeText(
            this,
            if (ok) "Gallery: storage access granted" else "Gallery: storage access denied",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
