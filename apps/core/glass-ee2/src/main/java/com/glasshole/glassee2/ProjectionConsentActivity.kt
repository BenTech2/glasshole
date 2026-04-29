package com.glasshole.glassee2

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Tiny no-UI shim that fires the system's
 * MediaProjection consent dialog. Result (granted/denied) is handed
 * back to BluetoothListenerService via a static callback so the BT
 * thread can spin up the streamer or send LIVE_SCREEN_ERR. The user
 * sees only the "Start now" / "Cancel" system dialog — this activity
 * itself never paints anything.
 */
class ProjectionConsentActivity : Activity() {
    companion object {
        private const val TAG = "ProjectionConsent"
        private const val REQ = 7401
        @Volatile var pendingResult: ((code: Int, data: Intent?) -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        } catch (e: Exception) {
            Log.e(TAG, "createScreenCaptureIntent failed: ${e.message}")
            pendingResult?.invoke(RESULT_CANCELED, null)
            pendingResult = null
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            pendingResult?.invoke(resultCode, data)
            pendingResult = null
        }
        finish()
    }
}
