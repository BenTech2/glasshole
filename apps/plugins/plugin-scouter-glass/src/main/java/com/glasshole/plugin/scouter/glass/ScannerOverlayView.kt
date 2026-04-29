package com.glasshole.plugin.scouter.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
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
        strokeWidth = dp(1.5f)
        color = accent
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = accentDim
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = accent
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accent
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = dp(14f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.15f
    }
    private val powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = dp(28f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.10f
    }

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

        // Pulsing reticle — radius oscillates ±10%.
        val pulse = 1f + 0.10f * sin((elapsed / 250.0)).toFloat()
        canvas.drawCircle(cx, cy, reticleR * pulse, ringPaint)

        // Crosshair — short ticks just outside the reticle.
        val gap = dp(8f)
        val tick = dp(14f)
        canvas.drawLine(cx - reticleR - gap - tick, cy, cx - reticleR - gap, cy, crosshairPaint)
        canvas.drawLine(cx + reticleR + gap, cy, cx + reticleR + gap + tick, cy, crosshairPaint)
        canvas.drawLine(cx, cy - reticleR - gap - tick, cx, cy - reticleR - gap, crosshairPaint)
        canvas.drawLine(cx, cy + reticleR + gap, cx, cy + reticleR + gap + tick, crosshairPaint)

        // Center dot.
        canvas.drawCircle(cx, cy, dp(2f), centerDotPaint)

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
        canvas.drawCircle(cx, cy, dp(2f), centerDotPaint)

        // "TARGET LOCKED" header, top-left.
        canvas.drawText("TARGET LOCKED", dp(16f), dp(28f), labelPaint)

        // Power level — center, big, monospaced, bold.
        val powerText = "%,d".format(powerLevel)
        val powerWidth = powerPaint.measureText(powerText)
        canvas.drawText(
            powerText,
            cx - powerWidth / 2f,
            height - dp(48f),
            powerPaint
        )
        // Classification subtext.
        val klassText = classification.uppercase()
        if (klassText.isNotEmpty()) {
            val klassWidth = labelPaint.measureText(klassText)
            canvas.drawText(
                klassText,
                cx - klassWidth / 2f,
                height - dp(24f),
                labelPaint
            )
        }
    }
}
