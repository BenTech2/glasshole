package com.glasshole.plugin.device.glass

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One-tap "Device Access" tile. On Android 6+ WRITE_SETTINGS is a special
 * runtime permission — without it brightness, screen timeout, and the
 * auto-time fallback all silently fail. This activity checks the current
 * state and, if needed, opens the system's Modify System Settings page so
 * the user can flip the toggle for this plugin.
 *
 * On Android 5 and older (EE1 / XE) WRITE_SETTINGS is granted at install
 * time, so the activity just shows "All set" and there's nothing to do.
 */
class DeviceAccessActivity : Activity() {

    private lateinit var stateText: TextView
    private lateinit var actionText: TextView

    private var touchStartX = 0f
    private var touchStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        container.addView(TextView(this).apply {
            text = "Device Access"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        stateText = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, dp(10), 0, 0)
        }
        container.addView(stateText)

        actionText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF66BB6A.toInt())
            setPadding(0, dp(14), 0, 0)
        }
        container.addView(actionText)

        setContentView(container)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (canWriteSystem()) {
            stateText.text = "System-settings access is granted. " +
                "Brightness, screen timeout, and time sync from the phone should work."
            actionText.text = "Swipe down to close"
            actionText.setOnClickListener(null)
        } else {
            stateText.text = "GlassHole needs permission to modify system settings " +
                "so brightness, screen timeout, and time sync work from the phone."
            actionText.text = "Tap to open Settings"
            actionText.setOnClickListener { openSystemSettings() }
        }
    }

    private fun canWriteSystem(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.System.canWrite(this)
    }

    private fun openSystemSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            stateText.text = "Couldn't open Settings automatically — " +
                "find GlassHole Device under Apps ▸ Special access ▸ Modify system settings."
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // Swipe down to close — works on EE2 touch and on EE1/XE raw touchpad events.
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (handleSwipe(event)) return true
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (handleSwipe(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleSwipe(event: MotionEvent?): Boolean {
        event ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.y - touchStartY
                val dx = event.x - touchStartX
                if (dy > 80 && Math.abs(dy) > Math.abs(dx)) {
                    finish()
                    return true
                }
            }
        }
        return false
    }
}
