package com.glasshole.phone.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Sparkline-style line graph of battery percentage over time. Held by
 * GlassDeviceInfoActivity which feeds it samples on every BATTERY_INFO
 * tick. Y axis is fixed 0–100%; X axis spans from the oldest in-buffer
 * sample to now.
 *
 * Rendering is intentionally minimal — no chart library dep, just a
 * Path of accent-tinted line segments with a faint fill underneath and
 * 0% / 50% / 100% gridlines.
 */
class BatteryHistoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Sample(val timeMs: Long, val percent: Float)

    private var samples: List<Sample> = emptyList()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val accentColor = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorPrimary, 0xFF8AB4F8.toInt()
    )
    private val gridColor = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOutlineVariant, 0x33FFFFFF
    )
    private val labelColor = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFAAAAAA.toInt()
    )

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = accentColor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (accentColor and 0x00FFFFFF) or 0x33000000
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
        color = gridColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = dp(10f)
        typeface = Typeface.MONOSPACE
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }

    fun setSamples(newSamples: List<Sample>) {
        samples = newSamples
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val padLeft = dp(28f)
        val padRight = dp(8f)
        val padTop = dp(8f)
        val padBottom = dp(14f)
        val plotW = w - padLeft - padRight
        val plotH = h - padTop - padBottom

        // Gridlines + labels at 0% / 50% / 100%
        for (pct in intArrayOf(0, 50, 100)) {
            val y = padTop + plotH - (pct / 100f) * plotH
            canvas.drawLine(padLeft, y, padLeft + plotW, y, gridPaint)
            canvas.drawText("${pct}%", dp(2f), y + dp(3f), labelPaint)
        }

        if (samples.size < 2) {
            canvas.drawText(
                "Collecting battery samples…",
                w / 2f, h / 2f + dp(4f), placeholderPaint
            )
            return
        }

        val minTime = samples.first().timeMs
        val maxTime = samples.last().timeMs
        val timeRange = (maxTime - minTime).coerceAtLeast(1L).toFloat()

        val path = Path()
        val fill = Path()
        for ((i, s) in samples.withIndex()) {
            val x = padLeft + ((s.timeMs - minTime) / timeRange) * plotW
            val y = padTop + plotH - (s.percent.coerceIn(0f, 100f) / 100f) * plotH
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, padTop + plotH)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        // Close the fill path along the baseline.
        val lastX = padLeft + plotW
        fill.lineTo(lastX, padTop + plotH)
        fill.lineTo(padLeft, padTop + plotH)
        fill.close()

        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)

        // X-axis range hint
        val rangeMin = (timeRange / 1000f / 60f)
        val rangeText = if (rangeMin < 1f) {
            "${(timeRange / 1000f).toInt()}s"
        } else {
            "${rangeMin.toInt()} min"
        }
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("now", w - padRight, h - dp(2f), labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("-$rangeText", padLeft, h - dp(2f), labelPaint)
    }
}
