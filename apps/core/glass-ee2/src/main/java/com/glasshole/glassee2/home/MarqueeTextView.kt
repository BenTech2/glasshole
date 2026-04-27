package com.glasshole.glassee2.home

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.AppCompatTextView

/**
 * iPod-style scrolling text. Copied from the old plugin-media-glass
 * module since the base app now owns the media card. Draws the string
 * twice with a gap for a seamless loop; pauses at the start of every
 * cycle so opening words are readable on each pass.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        private const val DP_PER_SEC = 45f
        private const val GAP_DP = 40f
        private const val HOLD_AT_START_MS = 2000L
    }

    private val pxPerSec: Float
    private val gapPx: Float
    private var animStartTime = 0L

    init {
        val density = resources.displayMetrics.density
        pxPerSec = DP_PER_SEC * density
        gapPx = GAP_DP * density
        setSingleLine(true)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        animStartTime = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val content = text?.toString() ?: return
        if (content.isEmpty()) return

        val textWidth = paint.measureText(content)
        val availWidth = (width - paddingStart - paddingEnd).toFloat()

        if (textWidth <= availWidth) {
            super.onDraw(canvas)
            return
        }

        val now = AnimationUtils.currentAnimationTimeMillis()
        if (animStartTime == 0L) animStartTime = now

        val cycleDist = textWidth + gapPx
        val scrollDurMs = (cycleDist / pxPerSec * 1000f).toLong().coerceAtLeast(1L)
        val totalCycleMs = HOLD_AT_START_MS + scrollDurMs
        val phase = (now - animStartTime) % totalCycleMs
        val offset = if (phase < HOLD_AT_START_MS) {
            0f
        } else {
            val scrollElapsed = phase - HOLD_AT_START_MS
            (scrollElapsed.toFloat() / scrollDurMs) * cycleDist
        }

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

        postInvalidateOnAnimation()
    }
}
