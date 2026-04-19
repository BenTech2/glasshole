package com.glasshole.glassxe

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/**
 * Full-screen notification card for Glass XE. Matches EE1 layout/controls.
 * XE timeline cards are static — interactive notifications require this
 * Activity. Input:
 *   - Swipe forward  → KEYCODE_TAB            → focus next action
 *   - Swipe backward → KEYCODE_TAB + SHIFT    → focus previous action
 *   - Tap            → KEYCODE_DPAD_CENTER    → invoke focused action
 *   - Swipe down     → KEYCODE_BACK           → dismiss
 */
class NotificationDisplayActivity : Activity() {

    companion object {
        private const val TAG = "NotifDisplay"
        private const val DEFAULT_DISMISS_MS = 15000L
        private const val REQUEST_VOICE = 101
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }
    private var dismissMs: Long = DEFAULT_DISMISS_MS

    private var notifKey: String = ""
    private var actions: List<NotifAction> = emptyList()
    private var pendingReplyActionId: String? = null
    private val actionButtons = mutableListOf<TextView>()
    private var focusedActionIndex = 0

    // XE touchpad state (raw x/y from SOURCE_TOUCHPAD onGenericMotionEvent)
    private var padStartX: Float = 0f
    private var padStartY: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        val parsed = parseIntent()
        notifKey = parsed.key
        actions = parsed.actions
        dismissMs = intent.getLongExtra("dismissMs", DEFAULT_DISMISS_MS)
            .takeIf { it > 0L } ?: DEFAULT_DISMISS_MS
        setContentView(buildCardView(parsed))

        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
            handler.postDelayed({ tone.release() }, 500)
        } catch (_: Exception) {}

        resetAutoDismiss()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        resetAutoDismiss()
        return super.onTouchEvent(event)
    }

    // XE touchpad path. The cyttsp5 is SOURCE_TOUCHPAD and delivers raw
    // DOWN/MOVE/UP via onGenericMotionEvent, NOT onTouchEvent, and does NOT
    // emit KEYCODE_TAB for swipes. Interpret the raw gesture ourselves:
    //   horizontal swipe right  → next action button
    //   horizontal swipe left   → previous action button
    //   vertical swipe down     → dismiss (finish)
    //   small movement          → tap = invoke focused action
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event ?: return super.onGenericMotionEvent(event)
        resetAutoDismiss()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                padStartX = event.x
                padStartY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - padStartX
                val dy = event.y - padStartY
                val absDx = Math.abs(dx)
                val absDy = Math.abs(dy)
                val tapThreshold = 20f
                val swipeThreshold = 60f

                if (absDx < tapThreshold && absDy < tapThreshold) {
                    if (actions.isNotEmpty()) {
                        invokeAction(actions[focusedActionIndex])
                    }
                    return true
                }
                if (absDy > absDx * 1.3f && dy > swipeThreshold) {
                    finish()
                    return true
                }
                if (absDx > absDy && absDx > swipeThreshold) {
                    if (actionButtons.isNotEmpty()) {
                        focusedActionIndex = if (dx > 0) {
                            (focusedActionIndex + 1) % actionButtons.size
                        } else {
                            (focusedActionIndex - 1 + actionButtons.size) % actionButtons.size
                        }
                        highlightFocusedAction()
                    }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetAutoDismiss()
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_TAB -> {
                if (actionButtons.isEmpty()) return true
                val shift = event?.isShiftPressed == true
                focusedActionIndex = if (shift) {
                    (focusedActionIndex - 1 + actionButtons.size) % actionButtons.size
                } else {
                    (focusedActionIndex + 1) % actionButtons.size
                }
                highlightFocusedAction()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (actions.isNotEmpty()) invokeAction(actions[focusedActionIndex])
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun resetAutoDismiss() {
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, dismissMs)
    }

    private data class ParsedNotif(
        val app: String,
        val title: String,
        val text: String,
        val iconBase64: String,
        val pictureBase64: String,
        val key: String,
        val actions: List<NotifAction>
    )

    private data class NotifAction(
        val id: String,
        val label: String,
        val type: String,
        val url: String?
    )

    private fun parseIntent(): ParsedNotif {
        val app = intent.getStringExtra("app") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val icon = intent.getStringExtra("icon") ?: ""
        val picture = intent.getStringExtra("picture") ?: ""
        val key = intent.getStringExtra("key") ?: ""
        val actionsJson = intent.getStringExtra("actions")

        if (app.isEmpty() && title.isEmpty() && text.isEmpty()) {
            val raw = intent.getStringExtra("message") ?: "(empty)"
            return ParsedNotif("", "", raw, "", "", "", emptyList())
        }

        val parsedActions = mutableListOf<NotifAction>()
        if (!actionsJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(actionsJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    parsedActions.add(
                        NotifAction(
                            id = o.optString("id"),
                            label = o.optString("label"),
                            type = o.optString("type"),
                            url = o.optString("url").takeIf { it.isNotEmpty() }
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Actions parse failed: ${e.message}")
            }
        }

        return ParsedNotif(app, title, text, icon, picture, key, parsedActions)
    }

    private fun buildCardView(p: ParsedNotif): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        decodeIcon(p.iconBase64)?.let { bmp ->
            header.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    rightMargin = dp(10)
                }
            })
        }
        header.addView(TextView(this).apply {
            text = p.app.uppercase()
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        column.addView(header)

        column.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10)
            )
        })

        val pictureBitmap = decodeIcon(p.pictureBase64)
        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        textColumn.addView(TextView(this).apply {
            text = if (p.title.isNotEmpty()) p.title else p.text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (pictureBitmap != null) 20f else 26f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setLineSpacing(0f, 1.05f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (p.title.isNotEmpty() && p.text.isNotEmpty()) {
            textColumn.addView(TextView(this).apply {
                text = p.text
                setTextColor(0xFFCCCCCC.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (pictureBitmap != null) 13f else 16f)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setLineSpacing(0f, 1.12f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
        }
        bodyRow.addView(textColumn)
        if (pictureBitmap != null) {
            bodyRow.addView(ImageView(this).apply {
                setImageBitmap(pictureBitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(80), dp(80)).apply {
                    leftMargin = dp(10)
                }
            })
        }
        column.addView(bodyRow)

        column.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        if (p.actions.isNotEmpty()) {
            column.addView(buildActionsRow(p.actions))
        }

        root.addView(column)
        return root
    }

    private fun buildActionsRow(list: List<NotifAction>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        list.forEachIndexed { idx, a ->
            val btn = makeActionButton(a.label)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) leftMargin = dp(6)
            }
            btn.layoutParams = lp
            btn.setOnClickListener { invokeAction(a) }
            row.addView(btn)
            actionButtons.add(btn)
        }
        focusedActionIndex = 0
        row.post { highlightFocusedAction() }
        return row
    }

    private fun makeActionButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = unfocusedBg()
        }
    }

    private fun unfocusedBg() = GradientDrawable().apply {
        cornerRadius = dp(4).toFloat()
        setColor(0xFF1A2735.toInt())
        setStroke(dp(1), 0xFF37474F.toInt())
    }

    private fun focusedBg() = GradientDrawable().apply {
        cornerRadius = dp(4).toFloat()
        setColor(0xFF2E5C7E.toInt())
        setStroke(dp(2), 0xFF4FC3F7.toInt())
    }

    private fun highlightFocusedAction() {
        actionButtons.forEachIndexed { i, tv ->
            tv.background = if (i == focusedActionIndex) focusedBg() else unfocusedBg()
        }
    }

    private fun invokeAction(a: NotifAction) {
        Log.i(TAG, "Action tapped: ${a.id} / ${a.type}")
        when (a.type) {
            "reply" -> startVoiceReply(a.id)
            "open_glass_stream" -> {
                val url = a.url
                if (url.isNullOrEmpty()) {
                    toast("No stream URL")
                } else {
                    BluetoothListenerService.instance?.playStreamLocally(url)
                    BluetoothListenerService.instance?.sendNotifAction(notifKey, a.id, null)
                    finish()
                }
            }
            else -> {
                val ok = BluetoothListenerService.instance
                    ?.sendNotifAction(notifKey, a.id, null) ?: false
                val msg = when {
                    !ok -> "Not connected"
                    a.type == "open_phone" -> "Opening on phone"
                    else -> "Sent"
                }
                toast(msg)
                finish()
            }
        }
    }

    private fun startVoiceReply(actionId: String) {
        pendingReplyActionId = actionId
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your reply")
            }
            startActivityForResult(intent, REQUEST_VOICE)
        } catch (e: Exception) {
            Log.e(TAG, "Voice recognition unavailable: ${e.message}")
            toast("Voice input unavailable")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VOICE) {
            val actionId = pendingReplyActionId ?: return
            pendingReplyActionId = null
            if (resultCode == RESULT_OK && data != null) {
                val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val text = results?.firstOrNull().orEmpty()
                if (text.isEmpty()) {
                    toast("No speech detected")
                    return
                }
                val ok = BluetoothListenerService.instance
                    ?.sendNotifAction(notifKey, actionId, text) ?: false
                toast(if (ok) "Reply sent" else "Not connected")
                finish()
            } else {
                toast("Cancelled")
            }
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun decodeIcon(base64: String): android.graphics.Bitmap? {
        if (base64.isEmpty()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(autoDismiss)
        super.onDestroy()
    }
}
