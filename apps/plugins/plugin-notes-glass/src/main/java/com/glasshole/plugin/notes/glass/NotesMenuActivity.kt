package com.glasshole.plugin.notes.glass

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.glasshole.glass.sdk.GlassPluginConstants

class NotesMenuActivity : Activity() {

    private val menuItems = listOf("New Note", "View Notes")
    private var selectedIndex = 0
    private val itemViews = mutableListOf<TextView>()
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var swiped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Revive the base GlassHole BT service if it was LRU-killed.
        GlassBaseAppStarter.start(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "GlassHole Notes"
            textSize = 20f
            setTextColor(Color.GRAY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        for (item in menuItems) {
            val tv = TextView(this).apply {
                text = item
                textSize = 30f
                setPadding(16, 20, 16, 20)
            }
            itemViews.add(tv)
            layout.addView(tv)
        }

        val hint = TextView(this).apply {
            text = "Swipe to select, tap to open"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 24, 0, 0)
        }
        layout.addView(hint)

        setContentView(layout)
        updateSelection()
    }

    private fun updateSelection() {
        for ((i, tv) in itemViews.withIndex()) {
            if (i == selectedIndex) {
                tv.setTextColor(Color.WHITE)
                tv.setBackgroundColor(0xFF1565C0.toInt())
            } else {
                tv.setTextColor(0xFF90CAF9.toInt())
                tv.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun selectItem() {
        when (selectedIndex) {
            0 -> startActivity(Intent(this, DictateActivity::class.java))
            1 -> requestNoteList()
        }
    }

    private fun requestNoteList() {
        val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_TO_PHONE).apply {
            putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, "notes")
            putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, "NOTE_LIST_REQ")
            putExtra(GlassPluginConstants.EXTRA_PAYLOAD, "")
        }
        for (pkg in listOf("com.glasshole.glassee1", "com.glasshole.glassee2", "com.glasshole.glassxe")) {
            intent.setPackage(pkg)
            sendBroadcast(intent)
        }
    }

    // EE1 (Glass XE touchpad) delivers events via onGenericMotionEvent.
    // EE2 (Android 8 with a real touchscreen/trackpad) delivers via onTouchEvent.
    // Handle both paths.
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (handleTouchpad(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (handleTouchpad(event)) return true
        return super.onTouchEvent(event)
    }

    private fun handleTouchpad(event: MotionEvent?): Boolean {
        event ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                swiped = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                // Vertical swipe down = exit (standard Glass back gesture)
                if (dy > 80 && Math.abs(dy) > Math.abs(dx)) {
                    finish()
                    return true
                }
                if (Math.abs(dx) > 30 && !swiped) {
                    swiped = true
                    if (dx > 0 && selectedIndex < menuItems.size - 1) {
                        selectedIndex++
                        updateSelection()
                    } else if (dx < 0 && selectedIndex > 0) {
                        selectedIndex--
                        updateSelection()
                    }
                    lastTouchX = event.x
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                // A release without a prior swipe = tap = open current item
                if (!swiped) {
                    selectItem()
                    return true
                }
            }
        }
        return false
    }

    // Tap via DPAD_CENTER (Glass touchpad tap) and TAB (EE2 swipe fallback)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                selectItem()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                if (event?.isShiftPressed == true) {
                    if (selectedIndex > 0) { selectedIndex--; updateSelection() }
                } else {
                    if (selectedIndex < menuItems.size - 1) { selectedIndex++; updateSelection() }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
