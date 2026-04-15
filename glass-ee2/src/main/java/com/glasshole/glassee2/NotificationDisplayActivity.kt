package com.glasshole.glassee2

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen card-styled notification used on Glass Enterprise Edition 2.
 *
 * Layout:
 *   ┌─────────────────────────────────────┐
 *   │  [icon] APP NAME                    │
 *   │                                     │
 *   │  Big Title Goes Here                │
 *   │  Body text continues below with a   │
 *   │  smaller size for the details.      │
 *   └─────────────────────────────────────┘
 *
 * Accepts either:
 *  - structured extras: app, title, text, icon (base64 PNG)
 *  - legacy "message" string of "$app: $title - $text"
 */
class NotificationDisplayActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val parsed = parseIntent()
        setContentView(buildCardView(parsed))

        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
            handler.postDelayed({ tone.release() }, 500)
        } catch (_: Exception) {}

        handler.postDelayed(autoDismiss, 8000)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, 8000)
        return super.onTouchEvent(event)
    }

    private data class ParsedNotif(
        val app: String,
        val title: String,
        val text: String,
        val iconBase64: String
    )

    private fun parseIntent(): ParsedNotif {
        // New path: structured extras
        val app = intent.getStringExtra("app")
        if (!app.isNullOrEmpty() || intent.hasExtra("text")) {
            return ParsedNotif(
                app = app ?: "",
                title = intent.getStringExtra("title") ?: "",
                text = intent.getStringExtra("text") ?: "",
                iconBase64 = intent.getStringExtra("icon") ?: ""
            )
        }
        // Legacy: "$app: $title - $text"
        val raw = intent.getStringExtra("message") ?: "(empty)"
        val colon = raw.indexOf(":")
        if (colon <= 0) return ParsedNotif("", "", raw.trim(), "")
        val pkgName = raw.substring(0, colon).trim()
        val rest = raw.substring(colon + 1).trim()
        val dash = rest.indexOf(" - ")
        return if (dash > 0) {
            ParsedNotif(pkgName, rest.substring(0, dash).trim(), rest.substring(dash + 3).trim(), "")
        } else {
            ParsedNotif(pkgName, "", rest, "")
        }
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

        // Top row: icon + uppercase app name
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconBitmap = decodeIcon(p.iconBase64)
        if (iconBitmap != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(iconBitmap)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    rightMargin = dp(10)
                }
            }
            header.addView(iv)
        }

        val appLabel = TextView(this).apply {
            text = p.app.uppercase()
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(appLabel)
        column.addView(header)

        // Spacer
        column.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(16)
            )
        })

        // Big title
        val headline = TextView(this).apply {
            text = if (p.title.isNotEmpty()) p.title else p.text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setLineSpacing(0f, 1.05f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        column.addView(headline)

        // Body (only if title is distinct from body)
        if (p.title.isNotEmpty() && p.text.isNotEmpty()) {
            val body = TextView(this).apply {
                text = p.text
                setTextColor(0xFFCCCCCC.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setLineSpacing(0f, 1.12f)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                layoutParams = lp
            }
            column.addView(body)
        }

        root.addView(column)
        return root
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
