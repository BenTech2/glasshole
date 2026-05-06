package com.glasshole.plugin.camera2.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Catches the system-wide camera button broadcast and starts CameraActivity
 * in quick-capture mode.
 *
 * On modern Android (API 26+) `Intent.ACTION_CAMERA_BUTTON` is delivered as
 * a broadcast, not as an activity start, so the high-priority intent filter
 * on CameraActivity itself never fires from a hardware key press — we need a
 * BroadcastReceiver in between. The receiver re-issues the camera-button
 * intent at the activity, which CameraActivity matches via
 * `quickCaptureTriggerActions` and fires the shutter as soon as the capture
 * session is configured.
 *
 * CameraActivity carries FLAG_TURN_SCREEN_ON + FLAG_SHOW_WHEN_LOCKED so the
 * device wakes and dismisses the lockscreen on its own when launched from
 * sleep — no extra wake lock needed here.
 */
class CameraButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_CAMERA_BUTTON) return
        Log.i(TAG, "Camera button broadcast received — launching capture")

        val launch = Intent(context, CameraActivity::class.java).apply {
            action = Intent.ACTION_CAMERA_BUTTON
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch CameraActivity: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CameraButtonReceiver"
    }
}
