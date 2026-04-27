package com.glasshole.plugin.compass.glass

import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * HUD-style horizontal compass tape. Shows ±60° around the current heading
 * so the user glances up and sees the same ribbon a helicopter or FPS game
 * uses — cardinals and degrees slide horizontally as they turn.
 *
 * 3D feel comes from three stacked effects:
 *   1. Camera.rotateX — tilt the tape so its far edges recede slightly.
 *   2. Edge fade via a horizontal alpha gradient — distant ticks fog out.
 *   3. Subtle scale-by-distance in the text weight (major vs minor ticks).
 *
 * Background is transparent by design: on EE2's OLED, black pixels emit
 * no light, so the user sees the real world through them — only the
 * bright tick marks and labels light up the prism. True HUD overlay.
 */
class CompassTapeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var heading: Float = 0f

    // How many degrees of bearing to show on-screen, total. Wider = more
    // context but tighter spacing; narrower = punchier but less preview.
    private val spanDegrees: Float = 120f

    private val camera = Camera()

    private val tickMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val tickCardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val northCardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5252.toInt() // red flag for magnetic north
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val centerMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt() // amber — matches the nav plugin accent
        style = Paint.Style.FILL
    }

    // Gradient that fades the tape out toward the left/right edges. Built
    // lazily at size change so we can key it to the actual view width.
    private var edgeFadePaint: Paint? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fade = Paint(Paint.ANTI_ALIAS_FLAG)
        fade.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Color.BLACK,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.BLACK
            ),
            floatArrayOf(0f, 0.18f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
        fade.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.DST_OUT
        )
        edgeFadePaint = fade
    }

    fun setHeading(degrees: Float) {
        heading = ((degrees % 360f) + 360f) % 360f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val tapeY = h * 0.55f

        numberPaint.textSize = h * 0.14f
        cardinalPaint.textSize = h * 0.22f
        northCardinalPaint.textSize = h * 0.22f

        // We need the tape pixels alpha-faded at the edges but the center
        // marker full-bright, so draw onto an offscreen layer first, apply
        // the fade, then draw the marker on top. The 5-arg saveLayer is
        // API 21+; use the 6-arg overload with ALL_SAVE_FLAG for KitKat
        // compatibility (EE1 / XE both run API 19).
        @Suppress("DEPRECATION")
        val layer = canvas.saveLayer(0f, 0f, w, h, null, Canvas.ALL_SAVE_FLAG)

        // Perspective tilt. Pivot around the tape center so the far edges
        // recede behind the user. rotateX on a 2D projection effectively
        // squashes vertically with a keystone falloff — cheap but reads
        // as depth on a HUD.
        canvas.save()
        camera.save()
        camera.rotateX(-18f)
        val matrix = android.graphics.Matrix()
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-cx, -tapeY)
        matrix.postTranslate(cx, tapeY)
        canvas.concat(matrix)

        val pxPerDegree = w / spanDegrees
        val startDeg = (heading - spanDegrees / 2f).toInt() - 1
        val endDeg = (heading + spanDegrees / 2f).toInt() + 1

        val tickTop = tapeY - h * 0.22f
        val tickBottomMinor = tapeY - h * 0.05f
        val tickBottomMajor = tapeY
        val tickBottomCardinal = tapeY + h * 0.02f

        for (deg in startDeg..endDeg) {
            val norm = ((deg % 360) + 360) % 360
            val x = cx + (deg - heading) * pxPerDegree

            if (norm % 90 == 0) {
                canvas.drawLine(x, tickTop, x, tickBottomCardinal, tickCardinalPaint)
                val label = when (norm) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "?" }
                val paint = if (norm == 0) northCardinalPaint else cardinalPaint
                canvas.drawText(label, x, tickTop - h * 0.03f, paint)
            } else if (norm % 30 == 0) {
                canvas.drawLine(x, tickTop, x, tickBottomMajor, tickMajorPaint)
                canvas.drawText(norm.toString(), x, tickTop - h * 0.03f, numberPaint)
            } else if (norm % 10 == 0) {
                canvas.drawLine(x, tickTop + h * 0.08f, x, tickBottomMinor, tickMinorPaint)
            }
        }

        canvas.restore()

        // Apply the edge fade to everything drawn into the layer so far.
        edgeFadePaint?.let { canvas.drawPaint(it) }

        canvas.restoreToCount(layer)

        // Fixed center marker — a chevron below the tape pointing up at
        // the heading it indicates. Drawn AFTER the layer so the fade
        // doesn't eat into it.
        val chevron = Path().apply {
            val mWidth = h * 0.12f
            val tip = tapeY + h * 0.10f
            val base = tapeY + h * 0.20f
            moveTo(cx, tip)
            lineTo(cx - mWidth, base)
            lineTo(cx + mWidth, base)
            close()
        }
        canvas.drawPath(chevron, centerMarkerPaint)
    }
}
