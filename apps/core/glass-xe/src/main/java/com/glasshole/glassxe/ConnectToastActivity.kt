package com.glasshole.glassxe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tiny transparent activity that shows a "Connected to phone" banner for a
 * couple of seconds and then finishes itself. Triggered by the base app
 * when the phone opens the BT socket and the user has "Show connection
 * notifications" enabled on the phone. We use an activity rather than a
 * Toast because Android 8+ aggressively kills service-originated toasts
 * with a "Toast already killed" message, making them invisible on EE2.
 */
class ConnectToastActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake the display + show over lock screen so the user sees the
        // banner even if the glass was asleep when the phone reconnected.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(
                (24 * density).toInt(),
                (32 * density).toInt(),
                (24 * density).toInt(),
                (32 * density).toInt()
            )
        }
        container.addView(TextView(this).apply {
            text = "Connected to phone"
            textSize = 24f
            setTextColor(0xFF43A047.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        setContentView(container)

        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
        try {
            val pm = getSystemService(POWER_SERVICE)
            val m = pm?.javaClass?.getMethod("goToSleep", Long::class.javaPrimitiveType)
            m?.invoke(pm, android.os.SystemClock.uptimeMillis())
        } catch (_: Throwable) { }
    }
}
