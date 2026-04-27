package com.glasshole.plugin.nav.glass

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * On-glass nav card. Shows a single turn at a time — arrow icon plus
 * distance, instruction, and ETA. Updates arrive as local broadcasts
 * from NavGlassPluginService; the phone scrapes Google Maps' ongoing
 * navigation notification and forwards each update.
 *
 * Swipe down (or BACK) closes. When nav ends on the phone, NAV_END
 * flips the view back to "Waiting for navigation…" — the user can
 * also close explicitly.
 */
class NavActivity : Activity() {

    companion object {
        private const val TAG = "NavActivity"
    }

    private lateinit var content: LinearLayout
    private lateinit var turnIcon: ImageView
    private lateinit var distanceText: TextView
    private lateinit var instructionText: TextView
    private lateinit var etaText: TextView
    private lateinit var waitingText: TextView

    // Swipe-down-to-close gesture. EE2 uses onTouchEvent, EE1/XE use
    // onGenericMotionEvent with SOURCE_TOUCHPAD — both route through
    // handleGesture.
    private var downX = 0f
    private var downY = 0f

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(NavGlassPluginService.EXTRA_KIND)) {
                "update" -> applyUpdate(intent)
                "end" -> applyEnd()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nav)
        content = findViewById(R.id.content)
        turnIcon = findViewById(R.id.turnIcon)
        distanceText = findViewById(R.id.distanceText)
        instructionText = findViewById(R.id.instructionText)
        etaText = findViewById(R.id.etaText)
        waitingText = findViewById(R.id.waitingText)
        showWaiting()

        val filter = IntentFilter(NavGlassPluginService.ACTION_NAV_EVENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun applyUpdate(intent: Intent) {
        val distance = intent.getStringExtra(NavGlassPluginService.EXTRA_DISTANCE).orEmpty()
        val instruction = intent.getStringExtra(NavGlassPluginService.EXTRA_INSTRUCTION).orEmpty()
        val eta = intent.getStringExtra(NavGlassPluginService.EXTRA_ETA).orEmpty()
        val iconB64 = intent.getStringExtra(NavGlassPluginService.EXTRA_ICON_B64).orEmpty()

        distanceText.text = distance
        instructionText.text = instruction
        etaText.text = eta

        if (iconB64.isNotEmpty()) {
            try {
                val bytes = Base64.decode(iconB64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) turnIcon.setImageBitmap(bmp)
            } catch (e: Exception) {
                Log.w(TAG, "Icon decode failed: ${e.message}")
            }
        }

        content.visibility = View.VISIBLE
        waitingText.visibility = View.GONE
    }

    private fun applyEnd() {
        showWaiting()
    }

    private fun showWaiting() {
        content.visibility = View.GONE
        waitingText.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.y - downY
                val absDy = kotlin.math.abs(dy)
                val absDx = kotlin.math.abs(event.x - downX)
                if (dy > 120 && absDy > absDx * 1.3f) {
                    finish()
                    return true
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
