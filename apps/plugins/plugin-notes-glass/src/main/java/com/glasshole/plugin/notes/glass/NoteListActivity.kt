package com.glasshole.plugin.notes.glass

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.glasshole.glass.sdk.GlassPluginConstants
import org.json.JSONArray
import org.json.JSONObject

class NoteListActivity : Activity() {

    companion object {
        private const val TAG = "NotesGlassList"
        const val EXTRA_NOTES_JSON = "notes_json"
        private const val AUTO_DISMISS_MS = 30000L
    }

    private data class NoteItem(val id: String, val title: String)

    private val noteItems = mutableListOf<NoteItem>()
    private val itemViews = mutableListOf<TextView>()
    private var selectedIndex = 0
    private lateinit var scrollView: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { finish() }
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var swiped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlassBaseAppStarter.start(this)

        val json = intent.getStringExtra(EXTRA_NOTES_JSON) ?: "[]"
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                noteItems.add(NoteItem(
                    id = obj.getString("id"),
                    title = obj.optString("title", "Untitled")
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse note list: ${e.message}")
        }

        if (noteItems.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No notes yet"
                textSize = 28f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                gravity = Gravity.CENTER
            }
            setContentView(emptyView)
            handler.postDelayed({ finish() }, 3000L)
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 16, 24, 16)
        }

        val titleView = TextView(this).apply {
            text = "Notes (${noteItems.size})"
            textSize = 18f
            setTextColor(Color.GRAY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 12)
        }
        layout.addView(titleView)

        for (item in noteItems) {
            val tv = TextView(this).apply {
                text = item.title
                textSize = 26f
                setPadding(16, 16, 16, 16)
                maxLines = 1
            }
            itemViews.add(tv)
            layout.addView(tv)
        }

        val hint = TextView(this).apply {
            text = "Swipe to browse, tap to open"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 16, 0, 0)
        }
        layout.addView(hint)

        scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = true
            addView(layout)
            // EE2 has a real touchscreen, so ScrollView would intercept
            // horizontal swipes and taps before Activity.onTouchEvent sees
            // them. Route every touch into our handler and always consume
            // it — we scroll programmatically from updateSelection().
            setOnTouchListener { _, event ->
                handleTouchpad(event)
                true
            }
        }
        setContentView(scrollView)
        updateSelection()
        resetAutoDismiss()
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
        // Keep the selected row visible as selection moves
        val selectedView = itemViews.getOrNull(selectedIndex) ?: return
        scrollView.post {
            val viewTop = selectedView.top
            val viewBottom = selectedView.bottom
            val scrollY = scrollView.scrollY
            val visibleHeight = scrollView.height
            when {
                viewTop < scrollY ->
                    scrollView.smoothScrollTo(0, viewTop)
                viewBottom > scrollY + visibleHeight ->
                    scrollView.smoothScrollTo(0, viewBottom - visibleHeight)
            }
        }
    }

    private fun requestNote(id: String) {
        val payload = JSONObject().apply { put("id", id) }.toString()
        val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_TO_PHONE).apply {
            putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, "notes")
            putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, "NOTE_REQ")
            putExtra(GlassPluginConstants.EXTRA_PAYLOAD, payload)
        }
        for (pkg in listOf("com.glasshole.glassee1", "com.glasshole.glassee2", "com.glasshole.glassxe")) {
            intent.setPackage(pkg)
            sendBroadcast(intent)
        }
    }

    // EE1 uses onGenericMotionEvent; EE2 uses onTouchEvent. Route both into
    // the same handler so the list responds on both devices.
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
        resetAutoDismiss()
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
                // Swipe down = exit (Glass back gesture)
                if (dy > 80 && Math.abs(dy) > Math.abs(dx)) {
                    finish()
                    return true
                }
                if (Math.abs(dx) > 30 && !swiped) {
                    swiped = true
                    if (dx > 0 && selectedIndex < noteItems.size - 1) {
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
                if (!swiped && noteItems.isNotEmpty()) {
                    requestNote(noteItems[selectedIndex].id)
                    return true
                }
            }
        }
        return false
    }

    // Tap via DPAD_CENTER + EE2 TAB fallback
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetAutoDismiss()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (noteItems.isNotEmpty()) requestNote(noteItems[selectedIndex].id)
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
                    if (selectedIndex < noteItems.size - 1) { selectedIndex++; updateSelection() }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun resetAutoDismiss() {
        handler.removeCallbacks(autoDismissRunnable)
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }
}
