package com.glasshole.plugin.gallery.glass

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Thin Activity whose only job is to prompt the user to grant external
 * storage permissions to the gallery plugin. No UI — shows a system
 * permission dialog on top of whatever the user is looking at and then
 * finishes.
 */
class PermissionActivity : Activity() {

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
