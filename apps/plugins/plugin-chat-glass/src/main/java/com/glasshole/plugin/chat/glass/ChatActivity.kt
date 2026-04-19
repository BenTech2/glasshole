package com.glasshole.plugin.chat.glass

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Glass-side chat viewer. Starts the upstream pipe by sending START to the
 * phone plugin on open, listens for CHAT / STATUS broadcasts from
 * ChatGlassPluginService, and tells the phone to STOP on close so we're
 * not polling YouTube / holding a socket when no one's looking.
 */
class ChatActivity : Activity() {

    companion object {
        private const val TAG = "ChatActivity"
        // Absolute floor — used if the phone hasn't told us the configured
        // cap yet. The phone ships a maxMsgs field with every message;
        // anything outside this hard max gets clamped for safety.
        private const val FALLBACK_MAX_MESSAGES = 200
        private const val ABSOLUTE_MAX_MESSAGES = 1000
    }

    private var maxMessages = FALLBACK_MAX_MESSAGES

    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())

    // Gesture tracking for the Glass touchpad.
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    // Multiplier applied to raw horizontal finger travel so small touchpad
    // swipes translate to useful chat-scroll distance.
    private val horizScrollFactor = 2.5f

    // True while the viewport is pinned to the newest message. Goes false
    // as soon as the user scrolls up, so incoming messages stop yanking
    // them back to live. Tap or swipe-all-the-way-down re-latches it.
    private var stickToBottom = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(ChatGlassPluginService.EXTRA_KIND)) {
                "chat" -> {
                    val cap = intent.getIntExtra(ChatGlassPluginService.EXTRA_MAX_MSGS, 0)
                    if (cap in 10..ABSOLUTE_MAX_MESSAGES) maxMessages = cap
                    appendChat(
                        user = intent.getStringExtra(ChatGlassPluginService.EXTRA_USER) ?: "",
                        text = intent.getStringExtra(ChatGlassPluginService.EXTRA_TEXT) ?: "",
                        color = intent.getStringExtra(ChatGlassPluginService.EXTRA_COLOR) ?: "",
                        sizeSp = intent.getIntExtra(ChatGlassPluginService.EXTRA_SIZE, 0)
                    )
                }
                "status" -> setStatus(
                    intent.getStringExtra(ChatGlassPluginService.EXTRA_TEXT) ?: ""
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        scroll = findViewById(R.id.chatScroll)
        container = findViewById(R.id.chatContainer)
        status = findViewById(R.id.statusText)

        val filter = IntentFilter(ChatGlassPluginService.ACTION_CHAT_EVENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        sendToPhone("START", "")
    }

    override fun onDestroy() {
        sendToPhone("STOP", "")
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Gesture mapping:
    //   swipe forward (right) → scroll toward newer messages (live-time)
    //   swipe back (left)     → scroll toward older messages
    //   tap                   → jump to latest
    //   swipe down            → close
    // EE2's screen-surface touches come through dispatchTouchEvent; XE's
    // and EE1's SOURCE_TOUCHPAD come through onGenericMotionEvent. Both
    // routes call handleGesture() so the UX is identical on all hardware.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleGesture(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event ?: return super.onGenericMotionEvent(event)
        if (handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                if (kotlin.math.abs(dx) > 1f) {
                    scroll.scrollBy(0, (dx * horizScrollFactor).toInt())
                    lastX = event.x
                    stickToBottom = isAtBottom()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                val absDx = kotlin.math.abs(totalDx)
                val absDy = kotlin.math.abs(totalDy)

                if (totalDy > 120 && absDy > absDx * 1.3f) {
                    finish()
                    return true
                }
                if (absDx < 30 && absDy < 30) {
                    stickToBottom = true
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun isAtBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= child.height - 20
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun setStatus(text: String) {
        main.post {
            if (text.isEmpty()) {
                status.visibility = View.GONE
            } else {
                status.text = text
                status.visibility = View.VISIBLE
            }
        }
    }

    private fun appendChat(user: String, text: String, color: String, sizeSp: Int) {
        main.post {
            status.visibility = View.GONE

            val row = TextView(this).apply {
                val userColor = parseColor(color) ?: 0xFF4FC3F7.toInt()
                val spanned = android.text.SpannableStringBuilder()
                if (user.isNotEmpty()) {
                    val start = spanned.length
                    spanned.append(user)
                    spanned.setSpan(
                        android.text.style.ForegroundColorSpan(userColor),
                        start, spanned.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spanned.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start, spanned.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spanned.append(": ")
                }
                spanned.append(text)
                setText(spanned)
                setTextColor(Color.WHITE)
                textSize = if (sizeSp in 8..40) sizeSp.toFloat() else 14f
                setPadding(0, 4, 0, 4)
            }
            container.addView(row)

            while (container.childCount > maxMessages) {
                container.removeViewAt(0)
            }
            if (stickToBottom) {
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun parseColor(color: String): Int? {
        if (color.isBlank()) return null
        return try { Color.parseColor(color) } catch (_: Exception) { null }
    }

    /**
     * Broadcast-based phone send path — matches CalcActivity and OpenClaw
     * so we don't need to bind the plugin service from here.
     */
    private fun sendToPhone(type: String, payload: String) {
        try {
            val intent = Intent("com.glasshole.glass.MESSAGE_TO_PHONE").apply {
                putExtra("plugin_id", "chat")
                putExtra("message_type", type)
                putExtra("payload", payload)
            }
            for (pkg in listOf(
                "com.glasshole.glassee1",
                "com.glasshole.glassxe",
                "com.glasshole.glassee2"
            )) {
                intent.setPackage(pkg)
                sendBroadcast(intent)
            }
        } catch (_: Exception) {}
    }
}
