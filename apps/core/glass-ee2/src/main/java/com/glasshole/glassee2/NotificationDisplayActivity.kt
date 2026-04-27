package com.glasshole.glassee2

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
import com.glasshole.glassee2.home.NotifAction
import org.json.JSONObject

/**
 * Full-screen card-styled notification used on Glass Enterprise Edition 2.
 * Shows app icon, title, body text, and an optional row of contextual
 * actions (reply, open on phone, watch on glass, custom).
 */
class NotificationDisplayActivity : Activity() {

    companion object {
        private const val TAG = "NotifDisplay"
        private const val DEFAULT_DISMISS_MS = 12000L
        private const val REQUEST_VOICE = 101
        private const val SYNTH_DISMISS_ID = "__dismiss__"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }
    private var dismissMs: Long = DEFAULT_DISMISS_MS

    private var notifKey: String = ""
    private var actions: List<NotifAction> = emptyList()
    private var pendingReplyActionId: String? = null

    // Options overlay — shown when the user taps the card. Holds the
    // action list plus an always-present Dismiss at the end.
    private var overlayVisible: Boolean = false
    private var overlaySelected: Int = 0
    private var optionsOverlay: LinearLayout? = null
    private var overlayOptionViews: List<TextView> = emptyList()
    /** Actions + synthetic "Dismiss" at the end. */
    private var overlayOptions: List<NotifAction> = emptyList()
    private var hintText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetAutoDismiss()
        if (overlayVisible) return handleOverlayKey(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showOverlay(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleOverlayKey(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideOverlay(); true }
            KeyEvent.KEYCODE_TAB -> {
                cycleOverlaySelection(if (event?.isShiftPressed == true) -1 else 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleOverlaySelection(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { cycleOverlaySelection(-1); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                executeOverlaySelection()
                true
            }
            else -> true
        }
    }

    private fun showOverlay() {
        optionsOverlay?.let {
            overlaySelected = 0
            paintOverlaySelection()
            it.visibility = View.VISIBLE
            hintText?.visibility = View.GONE
            overlayVisible = true
        }
    }

    private fun hideOverlay() {
        optionsOverlay?.visibility = View.GONE
        hintText?.visibility = View.VISIBLE
        overlayVisible = false
    }

    private fun cycleOverlaySelection(delta: Int) {
        val n = overlayOptions.size
        if (n == 0) return
        overlaySelected = ((overlaySelected + delta) % n + n) % n
        paintOverlaySelection()
    }

    private fun paintOverlaySelection() {
        overlayOptionViews.forEachIndexed { idx, tv ->
            tv.setTextColor(
                if (idx == overlaySelected) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt()
            )
        }
    }

    private fun executeOverlaySelection() {
        val option = overlayOptions.getOrNull(overlaySelected) ?: return
        if (option.id == SYNTH_DISMISS_ID) {
            finish()
        } else {
            invokeAction(option)
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

    private fun parseIntent(): ParsedNotif {
        val app = intent.getStringExtra("app") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val icon = intent.getStringExtra("icon") ?: ""
        val picture = intent.getStringExtra("picture") ?: ""
        val key = intent.getStringExtra("key") ?: ""
        val actionsJson = intent.getStringExtra("actions")

        if (app.isEmpty() && title.isEmpty() && text.isEmpty() && !intent.hasExtra("text")) {
            // Legacy "$app: $title - $text"
            val raw = intent.getStringExtra("message") ?: "(empty)"
            val colon = raw.indexOf(":")
            if (colon <= 0) return ParsedNotif("", "", raw.trim(), "", "", "", emptyList())
            val pkgName = raw.substring(0, colon).trim()
            val rest = raw.substring(colon + 1).trim()
            val dash = rest.indexOf(" - ")
            return if (dash > 0) {
                ParsedNotif(pkgName, rest.substring(0, dash).trim(), rest.substring(dash + 3).trim(), "", "", "", emptyList())
            } else {
                ParsedNotif(pkgName, "", rest, "", "", "", emptyList())
            }
        }

        return ParsedNotif(app, title, text, icon, picture, key, NotifAction.parseArray(actionsJson))
    }

    private fun buildCardView(p: ParsedNotif): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val pictureBitmap = decodeIcon(p.pictureBase64)
        val iconBitmap = decodeIcon(p.iconBase64)
        val hasPicture = pictureBitmap != null
        val hasTitle = p.title.isNotEmpty()
        val hasBody = p.text.isNotEmpty()

        // Picture goes full-bleed behind everything; a bottom-to-top dark
        // gradient keeps text readable over any image. Classic Glass
        // timeline card look — content floats, no chrome.
        if (hasPicture) {
            root.addView(ImageView(this).apply {
                setImageBitmap(pictureBitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            root.addView(View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xEE000000.toInt(), 0x00000000)
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(26), dp(28), dp(18))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Picture layouts anchor everything to the bottom so text sits on
        // the dark gradient. Text-only layouts anchor the title at the top
        // and let the footer drop to the bottom.
        if (hasPicture) {
            column.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
        }

        // Title — big, thin, white.
        if (hasTitle) {
            column.addView(TextView(this).apply {
                text = p.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setLineSpacing(0f, 1.02f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        // Body — smaller, even thinner, lighter gray. If there's no title
        // the body gets the big-title slot instead so the card isn't just
        // one small line of text at the bottom.
        if (hasBody) {
            column.addView(TextView(this).apply {
                text = p.text
                if (!hasTitle) {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    maxLines = 4
                } else {
                    setTextColor(0xFFDDDDDD.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                    maxLines = if (hasPicture) 2 else 3
                }
                setLineSpacing(0f, 1.1f)
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (hasTitle) topMargin = dp(8) }
            })
        }

        // Spacer pushes actions/footer to the bottom for text-only cards.
        if (!hasPicture) {
            column.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
        }

        // "TAP FOR OPTIONS" hint sits just above the footer so the user
        // knows the card is interactive. Hidden while the overlay is up.
        val hint = TextView(this).apply {
            text = "TAP FOR OPTIONS"
            setTextColor(0xFF4FC3F7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
            // The letterSpacing on sans-serif-medium shifts the first
            // glyph a hair left of the title's left sidebearing. A small
            // start padding lines it up with the title / body.
            setPadding(dp(4), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(4) }
        }
        column.addView(hint)
        hintText = hint

        column.addView(buildFooter(p.app, iconBitmap))

        root.addView(column)

        // Tap-activated options overlay — layered on top of the card.
        // Always includes a synthetic "Dismiss" so the user can close the
        // popup without waiting for the auto-dismiss timeout.
        overlayOptions = p.actions + NotifAction(SYNTH_DISMISS_ID, "Dismiss", "dismiss", null)
        val overlay = buildOptionsOverlay(overlayOptions)
        overlay.visibility = View.GONE
        root.addView(overlay)
        optionsOverlay = overlay

        return root
    }

    private fun buildOptionsOverlay(options: List<NotifAction>): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xC0000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlay.addView(TextView(this).apply {
            text = "tap to confirm · swipe to choose · swipe down to cancel"
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        val viewList = mutableListOf<TextView>()
        options.forEach { option ->
            val tv = TextView(this).apply {
                text = option.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setPadding(dp(18), dp(10), dp(18), dp(10))
            }
            row.addView(tv)
            viewList.add(tv)
        }
        overlay.addView(row)
        overlayOptionViews = viewList
        return overlay
    }

    private fun buildFooter(app: String, iconBitmap: android.graphics.Bitmap?): View {
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        if (iconBitmap != null) {
            footer.addView(ImageView(this).apply {
                setImageBitmap(iconBitmap)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    rightMargin = dp(10)
                }
            })
        }
        footer.addView(TextView(this).apply {
            text = app.uppercase()
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        footer.addView(TextView(this).apply {
            text = "now"
            setTextColor(0xFFDDDDDD.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            maxLines = 1
        })
        return footer
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
                    // Also inform the phone for logging
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
