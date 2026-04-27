package com.glasshole.glassee2.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Thin row of dots that shows the current page index in a paginated
 * drawer. Inactive dots are grey; the active one is amber. Sized by
 * the density, capped to a reasonable number of dots on-screen.
 */
class DotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val densityScale = resources.displayMetrics.density

    private var count: Int = 0
    private var current: Int = 0
    private var leadingIsAction: Boolean = false

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()
        style = Paint.Style.FILL
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
        style = Paint.Style.FILL
    }
    private val actionStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * @param leadingIsAction when true, position 0 renders as an amber
     *   "×" mark instead of a plain dot — signals that swiping to the
     *   leftmost page is a distinct action, not another content item.
     */
    fun setPages(count: Int, current: Int, leadingIsAction: Boolean = false) {
        this.count = count
        this.current = current.coerceIn(0, (count - 1).coerceAtLeast(0))
        this.leadingIsAction = leadingIsAction
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (count <= 0) return
        val radius = 4f * densityScale
        val gap = 10f * densityScale
        val totalWidth = count * (radius * 2) + (count - 1) * gap
        val startX = (width - totalWidth) / 2f + radius
        val cy = height / 2f
        for (i in 0 until count) {
            val cx = startX + i * (radius * 2 + gap)
            if (leadingIsAction && i == 0) {
                val half = radius
                actionStroke.strokeWidth =
                    if (i == current) 2.4f * densityScale else 1.6f * densityScale
                actionStroke.color =
                    if (i == current) 0xFFFFC107.toInt() else 0xFF888888.toInt()
                canvas.drawLine(cx - half, cy - half, cx + half, cy + half, actionStroke)
                canvas.drawLine(cx - half, cy + half, cx + half, cy - half, actionStroke)
            } else {
                canvas.drawCircle(cx, cy, radius, if (i == current) activePaint else inactivePaint)
            }
        }
    }
}
