package com.glasshole.plugin.opencv.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws object-detector bounding boxes (and their labels) over the
 * camera preview. The Activity is responsible for transforming every
 * frame's boxes from the detector's input-image coordinate space into
 * this view's pixel coordinates before calling [setBoxes]; the overlay
 * itself is coordinate-agnostic and simply redraws whatever rects it
 * was last handed.
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Box(
        val rect: RectF,
        val label: String,
        val confidence: Float
    )

    private var boxes: List<Box> = emptyList()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF4FC3F7.toInt() // cyan — matches the "tap for options" accent
    }

    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xC0000000.toInt()
    }

    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    fun setBoxes(newBoxes: List<Box>) {
        boxes = newBoxes
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paddingH = 10f
        val paddingV = 6f
        for (b in boxes) {
            canvas.drawRect(b.rect, stroke)

            val labelStr = if (b.confidence > 0f) {
                "${b.label}  ${(b.confidence * 100).toInt()}%"
            } else {
                b.label
            }
            val textWidth = labelText.measureText(labelStr)
            val textHeight = labelText.descent() - labelText.ascent()
            val tagLeft = b.rect.left
            val tagTop = (b.rect.top - textHeight - paddingV * 2).coerceAtLeast(0f)
            val tagRight = tagLeft + textWidth + paddingH * 2
            val tagBottom = tagTop + textHeight + paddingV * 2
            canvas.drawRect(tagLeft, tagTop, tagRight, tagBottom, labelBg)
            canvas.drawText(
                labelStr,
                tagLeft + paddingH,
                tagBottom - paddingV - labelText.descent(),
                labelText
            )
        }
    }
}
