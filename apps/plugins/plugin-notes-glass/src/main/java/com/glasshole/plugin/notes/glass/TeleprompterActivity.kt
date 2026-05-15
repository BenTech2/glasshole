package com.glasshole.plugin.notes.glass

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Phone-controlled teleprompter. The phone pushes the text + initial
 * settings via TELEPROMPTER_START (handled by NotesGlassPluginService,
 * which starts this activity), and subsequent TELEPROMPTER_CONTROL
 * messages — play/pause, speed, font size, restart — arrive here via
 * a local broadcast the plugin service re-fires.
 *
 * Local glass-side controls also work so the wearer can manage things
 * without picking up the phone:
 *   - tap                       → toggle play/pause
 *   - swipe forward (TAB)       → speed +5 px/s
 *   - swipe back  (SHIFT+TAB)   → speed −5 px/s
 *   - swipe down  (BACK)        → exit
 *
 * After every state change (local or remote) the activity echoes its
 * current state back via TELEPROMPTER_STATE so the phone UI can stay
 * in sync.
 */
class TeleprompterActivity : Activity() {

    companion object {
        private const val TAG = "Teleprompter"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SPEED_PX_PER_SEC = "speedPxPerSec"
        const val EXTRA_FONT_SP = "fontSp"
        const val EXTRA_PLAYING = "playing"

        const val ACTION_CONTROL = "com.glasshole.plugin.notes.TELEPROMPTER_CONTROL"
        const val ACTION_STOP = "com.glasshole.plugin.notes.TELEPROMPTER_STOP"

        const val DEFAULT_SPEED_PX_PER_SEC = 40f
        const val DEFAULT_FONT_SP = 28f
        const val MIN_SPEED_PX_PER_SEC = 0f
        const val MAX_SPEED_PX_PER_SEC = 300f
        const val MIN_FONT_SP = 14f
        const val MAX_FONT_SP = 64f
    }

    private lateinit var scrollView: ScrollView
    private lateinit var textView: TextView
    private var speedPxPerSec: Float = DEFAULT_SPEED_PX_PER_SEC
    private var playing: Boolean = true
    /** Sub-pixel scroll accumulator. ScrollView only accepts int scrollBy
     *  values, so at low speeds we'd never move; integrate fractional
     *  px here and flush whole pixels to the view. */
    private var scrollAccumulatorPx: Float = 0f
    private var lastFrameUptimeMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val frameRunnable = object : Runnable {
        override fun run() {
            stepFrame()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONTROL -> applyControlPayload(intent.getStringExtra("payload") ?: "")
                ACTION_STOP -> finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on for the duration; nobody wants the panel
        // to sleep mid-line. The screen-off timeout still fires once
        // we finish().
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            isVerticalScrollBarEnabled = false
            // ScrollView eats touch events for its own drag-to-scroll on
            // EE2; we want every event to land on the activity so the
            // tap/swipe gestures below take effect. Turn its own touch
            // handling off — we drive scroll position from the timer.
            isClickable = false
            isFocusable = false
        }
        textView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 32, 32, 32)
            setLineSpacing(6f, 1.2f)
            textSize = DEFAULT_FONT_SP
        }
        scrollView.addView(textView)
        setContentView(scrollView)

        applyIntent(intent)
        startFrameLoop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_TEXT)?.let { textView.text = it }
        if (intent.hasExtra(EXTRA_FONT_SP)) {
            textView.textSize = intent.getFloatExtra(EXTRA_FONT_SP, DEFAULT_FONT_SP)
                .coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        }
        if (intent.hasExtra(EXTRA_SPEED_PX_PER_SEC)) {
            speedPxPerSec = intent.getFloatExtra(EXTRA_SPEED_PX_PER_SEC, DEFAULT_SPEED_PX_PER_SEC)
                .coerceIn(MIN_SPEED_PX_PER_SEC, MAX_SPEED_PX_PER_SEC)
        }
        if (intent.hasExtra(EXTRA_PLAYING)) {
            playing = intent.getBooleanExtra(EXTRA_PLAYING, true)
        }
        // Reset to the top whenever a new note is pushed in.
        scrollView.scrollTo(0, 0)
        scrollAccumulatorPx = 0f
        publishState()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ACTION_CONTROL)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        handler.removeCallbacks(frameRunnable)
        super.onDestroy()
    }

    private fun startFrameLoop() {
        handler.removeCallbacks(frameRunnable)
        lastFrameUptimeMs = SystemClock.uptimeMillis()
        handler.post(frameRunnable)
    }

    private fun stepFrame() {
        val now = SystemClock.uptimeMillis()
        val dtSec = (now - lastFrameUptimeMs) / 1000f
        lastFrameUptimeMs = now
        if (!playing || speedPxPerSec <= 0f) return

        scrollAccumulatorPx += speedPxPerSec * dtSec
        val whole = scrollAccumulatorPx.toInt()
        if (whole > 0) {
            scrollAccumulatorPx -= whole
            // ScrollView clamps automatically; once the bottom is reached
            // scrollBy is a no-op and the script just sits at the bottom.
            scrollView.scrollBy(0, whole)
        }
    }

    /** Apply a CONTROL message payload. Every field is optional —
     *  unset means "no change". `restart=true` resets scroll to top. */
    private fun applyControlPayload(payload: String) {
        if (payload.isEmpty()) return
        try {
            val json = JSONObject(payload)
            if (json.has("playing")) playing = json.getBoolean("playing")
            if (json.has("speedPxPerSec")) {
                speedPxPerSec = json.getDouble("speedPxPerSec").toFloat()
                    .coerceIn(MIN_SPEED_PX_PER_SEC, MAX_SPEED_PX_PER_SEC)
            }
            if (json.has("fontSp")) {
                textView.textSize = json.getDouble("fontSp").toFloat()
                    .coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            }
            if (json.optBoolean("restart", false)) {
                scrollView.scrollTo(0, 0)
                scrollAccumulatorPx = 0f
            }
            publishState()
        } catch (e: Exception) {
            Log.w(TAG, "applyControlPayload: ${e.message}")
        }
    }

    /** Echo current state back through the plugin bridge so the phone
     *  UI reflects local changes. Reads via the static plugin handle
     *  if the service is bound. */
    private fun publishState() {
        val state = JSONObject().apply {
            put("playing", playing)
            put("speedPxPerSec", speedPxPerSec)
            put("fontSp", textView.textSize / resources.displayMetrics.scaledDensity)
            put("offsetPx", scrollView.scrollY)
            put("contentLengthPx", textView.height)
        }
        NotesGlassPluginService.instance?.sendTeleprompterState(state.toString())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // EE1/XE touchpad → KeyEvent mapping. EE2 sends real touches,
        // so we also implement onGenericMotionEvent / dispatchTouchEvent
        // below.
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                playing = !playing
                publishState()
                true
            }
            KeyEvent.KEYCODE_TAB -> {
                val back = event?.isShiftPressed == true
                speedPxPerSec = (speedPxPerSec + if (back) -SPEED_STEP_PX_PER_SEC else SPEED_STEP_PX_PER_SEC)
                    .coerceIn(MIN_SPEED_PX_PER_SEC, MAX_SPEED_PX_PER_SEC)
                publishState()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // EE1 cyttsp5 touchpad: raw x/y arrives via onGenericMotionEvent
    // with SOURCE_TOUCHPAD. Track totals so a clean downward swipe
    // closes the activity (matches NoteViewActivity's pattern).
    private var startPadX: Float = 0f
    private var startPadY: Float = 0f

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event ?: return super.onGenericMotionEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startPadX = event.x; startPadY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.y - startPadY
                val dx = event.x - startPadX
                if (dy > 150 && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                    finish()
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}

private const val FRAME_INTERVAL_MS = 16L
private const val SPEED_STEP_PX_PER_SEC = 5f
