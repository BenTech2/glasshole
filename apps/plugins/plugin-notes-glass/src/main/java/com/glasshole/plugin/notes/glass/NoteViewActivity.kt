package com.glasshole.plugin.notes.glass

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView

class NoteViewActivity : Activity() {

    companion object {
        const val EXTRA_NOTE_TEXT = "note_text"
        const val EXTRA_NOTE_ID = "note_id"
        private const val AUTO_DISMISS_MS = 30000L
        private const val SCROLL_STEP = 60
    }

    private lateinit var scrollView: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val noteText = intent.getStringExtra(EXTRA_NOTE_TEXT) ?: "No content"

        scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            isVerticalScrollBarEnabled = true
        }

        val textView = TextView(this).apply {
            text = noteText
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 24, 24, 24)
            setLineSpacing(4f, 1.1f)
        }

        scrollView.addView(textView)
        setContentView(scrollView)

        // Use a GestureDetector to watch for a downward fling that closes
        // the note. Everything else falls through to the ScrollView, which
        // handles its own vertical drag-to-scroll natively — that way it
        // works on both EE2 (touchscreen) and EE1 (touchpad).
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val dy = if (e1 != null) e2.y - e1.y else 0f
                val dx = if (e1 != null) e2.x - e1.x else 0f
                if (velocityY > 1500 && dy > 100 && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                    finish()
                    return true
                }
                return false
            }
        })

        scrollView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            resetAutoDismiss()
            // Return false so ScrollView still processes the touch and scrolls
            // natively. A fling-down to close still runs because the detector
            // sees the event regardless.
            false
        }

        // Glass EE1 / XE touchpads emit ACTION_SCROLL on the horizontal or
        // vertical axis rather than touch events. Route those to scrollBy
        // directly — ScrollView's own onGenericMotionEvent only handles a
        // mouse wheel on VSCROLL.
        scrollView.setOnGenericMotionListener { _, event ->
            if (event?.action == MotionEvent.ACTION_SCROLL) {
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val delta = if (h != 0f) h else v
                if (delta != 0f) {
                    scrollView.smoothScrollBy(0, (delta * SCROLL_STEP * 2).toInt())
                    resetAutoDismiss()
                    return@setOnGenericMotionListener true
                }
            }
            false
        }

        resetAutoDismiss()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetAutoDismiss()
        // Glass EE1 / XE touchpad gesture mapping:
        //  - forward swipe = KEYCODE_TAB          → scroll down
        //  - backward swipe = KEYCODE_TAB + SHIFT → scroll up
        //  - tap           = KEYCODE_DPAD_CENTER  → toggle page-scroll
        //  - swipe down    = KEYCODE_BACK         → exit
        return when (keyCode) {
            KeyEvent.KEYCODE_TAB -> {
                val up = event?.isShiftPressed == true
                scrollView.smoothScrollBy(
                    0,
                    if (up) -SCROLL_STEP * 4 else SCROLL_STEP * 4
                )
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                scrollView.smoothScrollBy(0, SCROLL_STEP * 4)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                scrollView.smoothScrollBy(0, -SCROLL_STEP * 4)
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // The Glass EE1 cyttsp5 touchpad is SOURCE_TOUCHPAD and delivers raw
    // ACTION_DOWN / ACTION_MOVE / ACTION_UP events via onGenericMotionEvent
    // (not onTouchEvent), with regular X/Y coordinates. Track finger position
    // and map deltas to content scrolling. On ACTION_UP, if the total gesture
    // was a big downward swipe, finish the activity (Glass "back" gesture).
    private var lastPadX: Float = 0f
    private var lastPadY: Float = 0f
    private var startPadX: Float = 0f
    private var startPadY: Float = 0f

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event ?: return super.onGenericMotionEvent(event)

        // Mouse wheel / Glass XE HSCROLL path
        if (event.action == MotionEvent.ACTION_SCROLL) {
            resetAutoDismiss()
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val delta = if (h != 0f) h else v
            if (delta != 0f) {
                scrollView.smoothScrollBy(0, (delta * SCROLL_STEP * 3).toInt())
                return true
            }
        }

        // EE1 touchpad path: DOWN/MOVE/UP with raw x/y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastPadX = event.x
                lastPadY = event.y
                startPadX = event.x
                startPadY = event.y
                resetAutoDismiss()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastPadX
                val dy = event.y - lastPadY
                if (Math.abs(dx) > Math.abs(dy)) {
                    scrollView.scrollBy(0, dx.toInt())
                } else {
                    scrollView.scrollBy(0, -dy.toInt())
                }
                lastPadX = event.x
                lastPadY = event.y
                resetAutoDismiss()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val totalDx = event.x - startPadX
                val totalDy = event.y - startPadY
                // Big downward swipe = close the note. Must be clearly
                // vertical to avoid mistaking horizontal scroll for exit.
                if (totalDy > 150 && Math.abs(totalDy) > Math.abs(totalDx) * 1.5f) {
                    finish()
                    return true
                }
                resetAutoDismiss()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetAutoDismiss()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun resetAutoDismiss() {
        handler.removeCallbacks(autoDismissRunnable)
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }
}
