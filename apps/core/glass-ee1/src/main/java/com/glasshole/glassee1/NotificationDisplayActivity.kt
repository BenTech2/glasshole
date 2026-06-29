package com.glasshole.glassee1

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
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
import com.glasshole.glassee1.home.NotifAction

/**
 * Full-screen card-styled notification for Glass. Shows app icon, title,
 * body text, optional full-bleed picture, and an optional row of
 * contextual actions (reply, open on phone, watch on glass, custom).
 *
 * Tap brings up the actions overlay; swipe down from the overlay cancels
 * it; swipe down from the card dismisses the popup entirely.
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

    /** Stack of pending notifications. New arrivals append (or replace
     *  in place if their key matches an existing entry); swipe
     *  forward/back navigates between them; swipe down pops the
     *  current entry. */
    private val stack = mutableListOf<ParsedNotif>()
    private var currentIndex = 0
    private var counterText: TextView? = null

    /** See XE copy. */
    private var cardSwitcher: FrameLayout? = null
    @Volatile private var swapInFlight: Boolean = false

    private var overlayVisible: Boolean = false
    private var overlaySelected: Int = 0
    private var optionsOverlay: LinearLayout? = null
    private var overlayOptionViews: List<TextView> = emptyList()
    private var overlayOptions: List<NotifAction> = emptyList()
    private var hintText: TextView? = null

    // EE1 / XE touchpad swipe tracking. EE2 converts these gestures to
    // TAB / BACK / DPAD_CENTER key events handled by onKeyDown.
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        cardSwitcher = FrameLayout(this)
        setContentView(cardSwitcher)

        val parsed = parseIntent()
        dismissMs = intent.getLongExtra("dismissMs", DEFAULT_DISMISS_MS)
            .takeIf { it > 0L } ?: DEFAULT_DISMISS_MS
        stack.add(parsed)
        currentIndex = 0
        showCurrent()
        playSoundFor(parsed)
        resetAutoDismiss()
    }

    /** singleTop launchMode reuses the existing instance when a new
     *  notification arrives; queue it up rather than replacing. */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        val parsed = parseIntent()
        val existing = stack.indexOfFirst {
            it.key.isNotEmpty() && it.key == parsed.key
        }
        if (existing >= 0) {
            stack[existing] = parsed
            currentIndex = existing
        } else {
            stack.add(parsed)
            currentIndex = stack.size - 1
        }
        val newDismiss = newIntent.getLongExtra("dismissMs", DEFAULT_DISMISS_MS)
        if (newDismiss in 1L..dismissMs) dismissMs = newDismiss
        showCurrent()
        playSoundFor(parsed)
        resetAutoDismiss()
    }

    /** See XE copy for the design note. */
    private fun showCurrent(slideDir: Int = 0) {
        val parsed = stack.getOrNull(currentIndex) ?: run { finish(); return }
        notifKey = parsed.key
        actions = parsed.actions
        if (overlayVisible) hideOverlay()

        val parent = cardSwitcher ?: run {
            setContentView(buildCardView(parsed)); return
        }
        val newCard = buildCardView(parsed)
        val oldCard = if (parent.childCount > 0) parent.getChildAt(0) else null

        if (slideDir == 0 || oldCard == null) {
            parent.removeAllViews()
            parent.addView(newCard, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            return
        }
        val width = parent.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        parent.addView(newCard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        newCard.translationX = (slideDir * width).toFloat()
        swapInFlight = true
        newCard.animate()
            .translationX(0f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .start()
        oldCard.animate()
            .translationX((-slideDir * width).toFloat())
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .withEndAction {
                parent.removeView(oldCard)
                swapInFlight = false
            }
            .start()
    }

    private fun playSoundFor(parsed: ParsedNotif) {
        val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
        val soundEnabled = prefs.getBoolean(BaseSettings.KEY_NOTIF_SOUND_ENABLED, true)
        val soundVolume = prefs.getInt(BaseSettings.KEY_NOTIF_SOUND_VOLUME, 100)
            .coerceIn(0, 100)
        if (soundEnabled && soundVolume > 0) {
            val perAppSounds = getSharedPreferences("notif_app_sounds", MODE_PRIVATE)
            val soundId = if (parsed.pkg.isNotEmpty()) {
                perAppSounds.getString(parsed.pkg, "") ?: ""
            } else ""
            NotifSoundPlayer.play(soundId, soundVolume)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleGesture(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        resetAutoDismiss()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)
                if (dy > 120 && absDy > absDx * 1.3f) {
                    if (overlayVisible) hideOverlay() else dismissCurrent()
                    return true
                }
                if (absDx > 60 && absDx > absDy) {
                    if (overlayVisible) cycleOverlaySelection(if (dx > 0) 1 else -1)
                    else if (stack.size > 1) cycleStack(if (dx > 0) 1 else -1)
                    return true
                }
                if (absDx < 25 && absDy < 25) {
                    if (overlayVisible) executeOverlaySelection() else showOverlay()
                    return true
                }
            }
        }
        return false
    }

    private fun dismissCurrent() {
        if (stack.isEmpty()) { finish(); return }
        stack.removeAt(currentIndex.coerceIn(0, stack.size - 1))
        if (stack.isEmpty()) { finish(); return }
        currentIndex = currentIndex.coerceAtMost(stack.size - 1)
        showCurrent()
        resetAutoDismiss()
    }

    private fun cycleStack(delta: Int) {
        if (stack.size <= 1) return
        if (swapInFlight) return
        currentIndex = ((currentIndex + delta) % stack.size + stack.size) % stack.size
        showCurrent(slideDir = delta)
        resetAutoDismiss()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetAutoDismiss()
        if (overlayVisible) return handleOverlayKey(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { dismissCurrent(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showOverlay(); true }
            KeyEvent.KEYCODE_TAB -> {
                if (stack.size > 1) {
                    cycleStack(if (event?.isShiftPressed == true) -1 else 1); true
                } else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleOverlayKey(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideOverlay(); true }
            KeyEvent.KEYCODE_TAB -> {
                cycleOverlaySelection(if (event?.isShiftPressed == true) -1 else 1); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleOverlaySelection(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { cycleOverlaySelection(-1); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                executeOverlaySelection(); true
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
        val pkg: String,
        val title: String,
        val text: String,
        val iconBase64: String,
        val titleIconBase64: String,
        val pictureBase64: String,
        val key: String,
        val actions: List<NotifAction>
    )

    private fun parseIntent(): ParsedNotif {
        val app = intent.getStringExtra("app") ?: ""
        val pkg = intent.getStringExtra("pkg") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val icon = intent.getStringExtra("icon") ?: ""
        val titleIcon = intent.getStringExtra("title_icon") ?: ""
        val picture = intent.getStringExtra("picture") ?: ""
        val key = intent.getStringExtra("key") ?: ""
        val actionsJson = intent.getStringExtra("actions")

        if (app.isEmpty() && title.isEmpty() && text.isEmpty() && !intent.hasExtra("text")) {
            val raw = intent.getStringExtra("message") ?: "(empty)"
            val colon = raw.indexOf(":")
            if (colon <= 0) return ParsedNotif("", "", "", raw.trim(), "", "", "", "", emptyList())
            val pkgName = raw.substring(0, colon).trim()
            val rest = raw.substring(colon + 1).trim()
            val dash = rest.indexOf(" - ")
            return if (dash > 0) {
                ParsedNotif(pkgName, "", rest.substring(0, dash).trim(), rest.substring(dash + 3).trim(), "", "", "", "", emptyList())
            } else {
                ParsedNotif(pkgName, "", "", rest, "", "", "", "", emptyList())
            }
        }

        return ParsedNotif(app, pkg, title, text, icon, titleIcon, picture, key, NotifAction.parseArray(actionsJson))
    }

    private fun buildCardView(p: ParsedNotif): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val pictureBitmap = decodeIcon(p.pictureBase64)
        val iconBitmap = decodeIcon(p.iconBase64)
        val titleIconBitmap = decodeIcon(p.titleIconBase64)
        val hasPicture = pictureBitmap != null
        val hasTitle = p.title.isNotEmpty()
        val hasBody = p.text.isNotEmpty()
        val hasTitleIcon = titleIconBitmap != null

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
                @Suppress("DEPRECATION")
                setBackgroundDrawable(
                    GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(0xEE000000.toInt(), 0x00000000)
                    )
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

        if (hasPicture) {
            column.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
        }

        if (hasTitle) {
            val titleText = TextView(this).apply {
                text = p.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setLineSpacing(0f, 1.02f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            if (hasTitleIcon) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(ImageView(this).apply {
                    setImageBitmap(circularBitmap(titleIconBitmap, dp(40)))
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        rightMargin = dp(10)
                    }
                })
                titleText.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                row.addView(titleText)
                column.addView(row)
            } else {
                column.addView(titleText)
            }
        }

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

        if (!hasPicture) {
            column.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
        }

        val hint = TextView(this).apply {
            text = "TAP FOR OPTIONS"
            setTextColor(0xFF4FC3F7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            // letterSpacing is API 21+. EE1 is API 19 only, and even
            // gating this with SDK_INT >= 21 isn't safe — Dalvik's verifier
            // soft-fails the whole method when it sees the unresolved
            // setLetterSpacing reference and replaces the body with
            // throw_verification_error, breaking notification rendering.
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

        overlayOptions = p.actions + NotifAction(SYNTH_DISMISS_ID, "Dismiss", "dismiss", null)
        val overlay = buildOptionsOverlay(overlayOptions)
        overlay.visibility = View.GONE
        root.addView(overlay)
        optionsOverlay = overlay

        // Stack counter chip — only shown when multiple notifs queued.
        if (stack.size > 1) {
            val counter = TextView(this).apply {
                text = "${currentIndex + 1} / ${stack.size}"
                setTextColor(Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                val padH = (10 * resources.displayMetrics.density).toInt()
                val padV = (4 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val cParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                val m = (8 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, m)
            }
            counter.layoutParams = cParams
            root.addView(counter)
            counterText = counter
        } else {
            counterText = null
        }

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

    /**
     * Soft-circle crop for the title-row avatar (sender / channel
     * icon). Returns a new ARGB bitmap of [sizePx]² with the source
     * scaled to a square and clipped to a circle.
     */
    private fun circularBitmap(src: android.graphics.Bitmap?, sizePx: Int): android.graphics.Bitmap? {
        if (src == null || sizePx <= 0) return null
        val out = android.graphics.Bitmap.createBitmap(
            sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val rectF = android.graphics.RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        paint.color = Color.WHITE
        canvas.drawOval(rectF, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled !== src) scaled.recycle()
        return out
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDismiss)
        super.onDestroy()
    }
}
