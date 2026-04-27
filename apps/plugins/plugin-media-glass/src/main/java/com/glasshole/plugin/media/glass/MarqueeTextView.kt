package com.glasshole.plugin.media.glass

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.AppCompatTextView

/**
 * TextView that scrolls its text horizontally at a tunable speed when the
 * text is wider than the view. Draws the string twice (with a gap) so the
 * loop appears seamless — no visible "jump" back to the start.
 *
 * Android's built-in `ellipsize="marquee"` works but its speed is hardcoded
 * to about 30 px/s and requires the view to hold focus. Rolling our own
 * gives an iPod-like tempo and lets the animation run without any focus
 * tricks.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        /** Scroll speed in density-independent pixels per second. */
        private const val DP_PER_SEC = 45f
        /** Empty space between end of one loop and start of the next, in dp. */
        private const val GAP_DP = 40f
        /**
         * Hold-at-start duration, applied both on first display and each
         * time the scroll wraps back to the left edge. Matches iPod
         * behavior — pause briefly at the start before scrolling again
         * so the opening words are readable on every cycle.
         */
        private const val HOLD_AT_START_MS = 2000L
    }

    private val pxPerSec: Float
    private val gapPx: Float

    private var animStartTime = 0L

    init {
        val density = resources.displayMetrics.density
        pxPerSec = DP_PER_SEC * density
        gapPx = GAP_DP * density
        // Fall-through rendering: no built-in marquee, single line so we
        // control horizontal layout entirely in onDraw.
        setSingleLine(true)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        // Restart the scroll cycle each time the text changes so a new song
        // title starts from the left rather than mid-scroll.
        animStartTime = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val content = text?.toString() ?: return
        if (content.isEmpty()) return

        val textWidth = paint.measureText(content)
        val availWidth = (width - paddingStart - paddingEnd).toFloat()

        if (textWidth <= availWidth) {
            // Text fits — render normally (leverages TextView's full layout
            // pipeline for ellipsize / padding / alignment).
            super.onDraw(canvas)
            return
        }

        val now = AnimationUtils.currentAnimationTimeMillis()
        if (animStartTime == 0L) animStartTime = now

        val cycleDist = textWidth + gapPx
        val scrollDurMs = (cycleDist / pxPerSec * 1000f).toLong().coerceAtLeast(1L)
        // Each cycle = hold + scroll. Modulo gives us the phase within the
        // current cycle; phase < hold means static, beyond that means we're
        // in the scroll portion.
        val totalCycleMs = HOLD_AT_START_MS + scrollDurMs
        val phase = (now - animStartTime) % totalCycleMs
        val offset = if (phase < HOLD_AT_START_MS) {
            0f
        } else {
            val scrollElapsed = phase - HOLD_AT_START_MS
            (scrollElapsed.toFloat() / scrollDurMs) * cycleDist
        }

        // Clip horizontally so anything past the paddings doesn't bleed.
        val saved = canvas.save()
        canvas.clipRect(
            paddingStart.toFloat(), 0f,
            (width - paddingEnd).toFloat(), height.toFloat()
        )

        val baseLine = baseline.toFloat()
        val startX = paddingStart.toFloat() - offset
        canvas.drawText(content, startX, baseLine, paint)
        canvas.drawText(content, startX + cycleDist, baseLine, paint)

        canvas.restoreToCount(saved)

        // Keep the animation advancing.
        postInvalidateOnAnimation()
    }
}
