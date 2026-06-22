package com.glasshole.glassee2.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Horizontal battery indicator drawn on the Home Time card. Replaces
 * the static ic_battery drawable so the visual actually tracks the
 * live percentage instead of just sitting next to a text label.
 *
 * Layout: a rounded-rectangle body taking ~85% of the view width,
 * a small nub on the right side for the terminal, and an inner fill
 * rect whose width is scaled to the current percentage.
 *
 * Color thresholds (per user request):
 *   > 30%  → white
 *   ≤ 30%  → yellow
 *   ≤ 10%  → red
 *
 * When [charging] is true, a small lightning bolt is drawn over the
 * fill so users who've hidden the percent text still see the device
 * is plugged in.
 */
class BatteryIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var percent: Int = 100
    private var charging: Boolean = false

    private val density = resources.displayMetrics.density

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#212121") // dark so it reads on a colored fill
    }
    private val boltPath = Path()
    /** Reusable rect for drawRoundRect — the float-args overload is
     *  API 21+ so we go through the RectF version which has been
     *  available since API 1. Allocating once here keeps onDraw GC-free. */
    private val tmpRect = RectF()

    /** Push a new battery snapshot. Only invalidates when something
     *  actually changed so the time-card minute tick stays cheap. */
    fun setBattery(percent: Int, charging: Boolean) {
        val newPct = percent.coerceIn(0, 100)
        if (newPct == this.percent && charging == this.charging) return
        this.percent = newPct
        this.charging = charging
        invalidate()
    }

    private fun colorForLevel(): Int = when {
        percent <= 10 -> 0xFFEF5350.toInt() // red
        percent <= 30 -> 0xFFFFEB3B.toInt() // yellow
        else -> 0xFFFFFFFF.toInt()          // white
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val color = colorForLevel()
        outlinePaint.color = color
        nubPaint.color = color
        fillPaint.color = color

        // Body takes 85% of the width, nub 15%. Stroke is centered on
        // the rect edge so inset by half its width to avoid clipping.
        val stroke = outlinePaint.strokeWidth
        val inset = stroke / 2f
        val bodyLeft = inset
        val bodyRight = w * 0.85f
        val bodyTop = inset
        val bodyBottom = h - inset
        val corner = 2.5f * density
        tmpRect.set(bodyLeft, bodyTop, bodyRight, bodyBottom)
        canvas.drawRoundRect(tmpRect, corner, corner, outlinePaint)

        // Nub: vertically centered, ~40% of body height.
        val nubLeft = bodyRight + 1.5f * density
        val nubRight = w - inset
        val nubH = (bodyBottom - bodyTop) * 0.4f
        val nubTop = (h - nubH) / 2f
        val nubBottom = nubTop + nubH
        tmpRect.set(nubLeft, nubTop, nubRight, nubBottom)
        canvas.drawRoundRect(tmpRect, 1f * density, 1f * density, nubPaint)

        // Inner fill — inset by stroke + 1.5dp so the body outline
        // stays visible around it. Width scales linearly with percent.
        val innerPad = stroke + 1.5f * density
        val innerLeft = bodyLeft + innerPad
        val innerRight = bodyRight - innerPad
        val innerTop = bodyTop + innerPad
        val innerBottom = bodyBottom - innerPad
        val totalWidth = innerRight - innerLeft
        val fillWidth = totalWidth * (percent / 100f)
        if (fillWidth > 0f) {
            canvas.drawRect(innerLeft, innerTop, innerLeft + fillWidth, innerBottom, fillPaint)
        }

        if (charging) drawBolt(canvas, bodyLeft, bodyTop, bodyRight, bodyBottom)
    }

    /** Lightning bolt centered in the body. Drawn dark so it reads on
     *  the colored fill — at low %, where the fill width is small,
     *  the bolt still shows because it spans the full body height. */
    private fun drawBolt(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val height = (b - t) * 0.70f
        val width = height * 0.45f
        boltPath.rewind()
        boltPath.moveTo(cx + width * 0.10f, cy - height / 2f)
        boltPath.lineTo(cx - width / 2f, cy + height * 0.10f)
        boltPath.lineTo(cx - width * 0.10f, cy + height * 0.10f)
        boltPath.lineTo(cx - width * 0.10f, cy + height / 2f)
        boltPath.lineTo(cx + width / 2f, cy - height * 0.10f)
        boltPath.lineTo(cx + width * 0.10f, cy - height * 0.10f)
        boltPath.close()
        canvas.drawPath(boltPath, boltPaint)
    }
}
