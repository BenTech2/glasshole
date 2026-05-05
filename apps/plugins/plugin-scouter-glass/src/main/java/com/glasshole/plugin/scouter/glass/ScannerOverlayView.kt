package com.glasshole.plugin.scouter.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Saiyan scouter HUD overlay. Three modes driven by [setMode]:
 *
 *   SCANNING   — rotating outer ring with tick marks, pulsing reticle,
 *                "SCANNING…" label. Drives a frame-pump invalidate so
 *                everything animates.
 *   ANALYZING  — frozen reticle, glitchy crosshair flicker, "ANALYZING"
 *                label. Brief — a couple of seconds before the reveal.
 *   LOCKED     — static reticle, "POWER LEVEL: <n>" reveal. No frame
 *                pump, draws once and stops.
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { SCANNING, ANALYZING, LOCKED }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** Cyan / scouter green — the canonical reticle tint. */
    private val accent = Color.parseColor("#5BFF8B")
    private val accentDim = Color.parseColor("#2A8048")

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = accent
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = accentDim
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = accent
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accent
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = dp(15f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        // setLetterSpacing was added in API 21 — Glass XE / EE1 are
        // API 19 and would crash with NoSuchMethodError on init.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            letterSpacing = 0.15f
        }
    }
    private val powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = dp(34f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            letterSpacing = 0.10f
        }
    }
    /** Translucent black plate drawn behind text in LOCKED mode so the
     *  reveal stays legible against bright backgrounds (sky, snow,
     *  white walls). Same plate for the top-left header and the
     *  bottom-center power-level/classification stack. */
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(0xC8, 0, 0, 0)
    }
    private val backdropRect = RectF()

    @Volatile private var mode: Mode = Mode.SCANNING
    @Volatile private var powerLevel: Long = 0L
    @Volatile private var classification: String = ""
    private var startedAtMs = System.currentTimeMillis()

    fun setMode(newMode: Mode) {
        mode = newMode
        startedAtMs = System.currentTimeMillis()
        invalidate()
    }

    fun setLockedReveal(power: Long, klass: String) {
        powerLevel = power
        classification = klass
        setMode(Mode.LOCKED)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val elapsed = System.currentTimeMillis() - startedAtMs

        // Outer ring radius — fits within the screen height with margin.
        val outerR = (h / 2f) - dp(20f)
        val innerR = outerR - dp(8f)
        val reticleR = outerR * 0.55f

        when (mode) {
            Mode.SCANNING -> drawScanning(canvas, cx, cy, outerR, innerR, reticleR, elapsed)
            Mode.ANALYZING -> drawAnalyzing(canvas, cx, cy, outerR, innerR, reticleR, elapsed)
            Mode.LOCKED -> drawLocked(canvas, cx, cy, outerR, reticleR)
        }
    }

    private fun drawScanning(
        canvas: Canvas, cx: Float, cy: Float,
        outerR: Float, innerR: Float, reticleR: Float, elapsed: Long
    ) {
        // Static outer ring.
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        // Rotating tick band — 24 ticks around an inner ring, rotation
        // tied to elapsed time.
        val rotDeg = (elapsed / 12.0) % 360.0
        val ticks = 24
        for (i in 0 until ticks) {
            val ang = Math.toRadians((i * 360.0 / ticks) + rotDeg)
            val x1 = cx + cos(ang).toFloat() * innerR
            val y1 = cy + sin(ang).toFloat() * innerR
            val x2 = cx + cos(ang).toFloat() * (innerR - dp(6f))
            val y2 = cy + sin(ang).toFloat() * (innerR - dp(6f))
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // Pulsing reticle — radius oscillates ±15%.
        val pulse = 1f + 0.15f * sin((elapsed / 220.0)).toFloat()
        canvas.drawCircle(cx, cy, reticleR * pulse, ringPaint)

        // Crosshair — slightly longer ticks for a chunkier read.
        val gap = dp(8f)
        val tick = dp(18f)
        canvas.drawLine(cx - reticleR - gap - tick, cy, cx - reticleR - gap, cy, crosshairPaint)
        canvas.drawLine(cx + reticleR + gap, cy, cx + reticleR + gap + tick, cy, crosshairPaint)
        canvas.drawLine(cx, cy - reticleR - gap - tick, cx, cy - reticleR - gap, crosshairPaint)
        canvas.drawLine(cx, cy + reticleR + gap, cx, cy + reticleR + gap + tick, crosshairPaint)

        // Center dot.
        canvas.drawCircle(cx, cy, dp(3f), centerDotPaint)

        // Status label, top-left.
        val dots = (elapsed / 333) % 4
        val label = "SCANNING" + ".".repeat(dots.toInt())
        canvas.drawText(label, dp(16f), dp(28f), labelPaint)

        postInvalidateOnAnimation()
    }

    private fun drawAnalyzing(
        canvas: Canvas, cx: Float, cy: Float,
        outerR: Float, innerR: Float, reticleR: Float, elapsed: Long
    ) {
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        canvas.drawCircle(cx, cy, reticleR, ringPaint)

        // Glitchy horizontal scan line ripping across the reticle.
        val scanY = cy - reticleR + ((elapsed % 600) / 600f) * (reticleR * 2f)
        canvas.drawLine(cx - reticleR, scanY, cx + reticleR, scanY, crosshairPaint)

        canvas.drawCircle(cx, cy, dp(2f), centerDotPaint)

        val flicker = (elapsed / 90) % 2 == 0L
        val text = if (flicker) "ANALYZING" else "ANALYZING_"
        canvas.drawText(text, dp(16f), dp(28f), labelPaint)

        postInvalidateOnAnimation()
    }

    private fun drawLocked(
        canvas: Canvas, cx: Float, cy: Float, outerR: Float, reticleR: Float
    ) {
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        canvas.drawCircle(cx, cy, reticleR, ringPaint)
        canvas.drawCircle(cx, cy, dp(3f), centerDotPaint)

        // "TARGET LOCKED" header — backdrop plate top-left.
        val header = "TARGET LOCKED"
        val headerWidth = labelPaint.measureText(header)
        val headerY = dp(28f)
        val padX = dp(8f)
        val padY = dp(6f)
        backdropRect.set(
            dp(16f) - padX,
            headerY + labelPaint.fontMetrics.ascent - padY,
            dp(16f) + headerWidth + padX,
            headerY + labelPaint.fontMetrics.descent + padY
        )
        canvas.drawRoundRect(backdropRect, dp(4f), dp(4f), backdropPaint)
        canvas.drawText(header, dp(16f), headerY, labelPaint)

        // Bottom-center stack: power level + classification, sharing
        // one backdrop plate sized to the wider of the two so they
        // read as a single unit even on a noisy / bright scene.
        val powerText = "%,d".format(powerLevel)
        val powerWidth = powerPaint.measureText(powerText)
        val klassText = classification.uppercase()
        val klassWidth = if (klassText.isNotEmpty()) labelPaint.measureText(klassText) else 0f
        val maxWidth = maxOf(powerWidth, klassWidth)

        val powerY = height - dp(48f)
        val klassY = height - dp(24f)
        val plateTop = powerY + powerPaint.fontMetrics.ascent - padY
        val plateBottom = (if (klassText.isNotEmpty()) klassY else powerY) +
            (if (klassText.isNotEmpty()) labelPaint.fontMetrics.descent else powerPaint.fontMetrics.descent) +
            padY
        backdropRect.set(
            cx - maxWidth / 2f - padX,
            plateTop,
            cx + maxWidth / 2f + padX,
            plateBottom
        )
        canvas.drawRoundRect(backdropRect, dp(6f), dp(6f), backdropPaint)

        canvas.drawText(powerText, cx - powerWidth / 2f, powerY, powerPaint)
        if (klassText.isNotEmpty()) {
            canvas.drawText(klassText, cx - klassWidth / 2f, klassY, labelPaint)
        }
    }
}
