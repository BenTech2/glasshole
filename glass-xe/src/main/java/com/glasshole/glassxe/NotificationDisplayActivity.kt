package com.glasshole.glassxe

import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class NotificationDisplayActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        val message = intent.getStringExtra("message") ?: "(empty)"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(40, 40, 40, 40)
        }

        val label = TextView(this).apply {
            text = "Notification"
            textSize = 18f
            setTextColor(0xFF90CAF9.toInt())
        }

        val bodyView = TextView(this).apply {
            text = message
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 16, 0, 0)
        }

        val scrollView = ScrollView(this).apply {
            addView(bodyView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        layout.addView(label)
        layout.addView(scrollView)
        setContentView(layout)

        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
            handler.postDelayed({ tone.release() }, 500)
        } catch (_: Exception) {}

        handler.postDelayed(autoDismiss, 15000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDismiss)
        super.onDestroy()
    }
}
