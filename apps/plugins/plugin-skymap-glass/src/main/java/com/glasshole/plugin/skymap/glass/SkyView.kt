// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skymap.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas-based sky renderer. Stateless rendering — feed it
 * observer position + view direction + JD via [updateFrame] and
 * it draws everything to its own bounds.
 *
 * Coordinate system:
 *  - Screen origin at top-left, y grows down
 *  - View direction maps to screen center
 *  - +x of AstroMath.project → screen right
 *  - +y of AstroMath.project → screen up (so we negate at draw time)
 */
class SkyView(context: Context) : View(context) {

    /** FOV (full angular extent across the longer screen dimension).
     *  Glass's actual optical FOV is ~13°. We render a wider sky
     *  window so the screen looks more populated — the user pans
     *  by turning their head. */
    var fovDeg: Double = 30.0

    private var latDeg: Double = 0.0
    private var lonDeg: Double = 0.0
    private var jd: Double = AstroMath.J2000
    private var azCenter: Double = 0.0
    private var altCenter: Double = 0.0
    private var hasFix: Boolean = false

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val constLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val tapeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val statusBg = Paint().apply {
        color = 0xB0000000.toInt()
        style = Paint.Style.FILL
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FF8000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun updateFrame(
        latDeg: Double, lonDeg: Double,
        utcMillis: Long,
        azCenterDeg: Double, altCenterDeg: Double,
        hasFix: Boolean,
    ) {
        this.latDeg = latDeg
        this.lonDeg = lonDeg
        this.jd = AstroMath.julianDate(utcMillis)
        this.azCenter = azCenterDeg
        this.altCenter = altCenterDeg
        this.hasFix = hasFix
        invalidate()
    }

    /** Project a sky point (Az, Alt) to screen pixels (cx + sx, cy - sy).
     *  Returns null if it's off-screen. */
    private fun toScreen(azDeg: Double, altDeg: Double, w: Int, h: Int): FloatArray? {
        val proj = AstroMath.project(azDeg, altDeg, azCenter, altCenter, fovDeg)
            ?: return null
        // Scale: longer screen dimension covers fovDeg.
        val scale = max(w, h) / fovDeg
        val sx = (proj[0] * scale).toFloat()
        val sy = (proj[1] * scale).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // Cull a bit beyond the edges so partially-on-screen dots still render.
        if (sx > w || sx < -w || sy > h || sy < -h) return null
        return floatArrayOf(cx + sx, cy - sy)  // y inversion for screen-down
    }

    override fun onDraw(canvas: Canvas) {
        // Black sky.
        canvas.drawColor(Color.BLACK)
        val w = width; val h = height
        if (w == 0 || h == 0) return

        // Horizon reference — draw the local horizon as a thin orange
        // arc so the user can tell when they're looking down at the
        // ground (where stars never are).
        drawHorizon(canvas, w, h)

        // Stars from the catalog. Skip below-horizon stars to save
        // draw calls.
        val starScreen = HashMap<String, FloatArray>(StarCatalog.stars.size)
        for (star in StarCatalog.stars) {
            val (az, alt) = AstroMath.raDecToAzAlt(
                star.raHours, star.decDeg, latDeg, lonDeg, jd
            ).let { it[0] to it[1] }
            if (alt < -2.0) continue  // below horizon
            val pt = toScreen(az, alt, w, h) ?: continue
            // Size by magnitude. Brightest ~3.5 px radius, dimmest ~0.8 px.
            val mag = star.magnitude.coerceIn(-2.0, 5.0)
            val r = (4.0 - mag * 0.6).coerceIn(0.8, 4.0).toFloat()
            canvas.drawCircle(pt[0], pt[1], r, starPaint)
            starScreen[star.name] = pt
            // Label only the brightest few inside the actual view —
            // too many labels reads as visual noise.
            if (mag < 1.2) {
                canvas.drawText(star.name, pt[0] + r + 2f, pt[1] + 4f, labelPaint)
            }
        }

        // Constellation lines — only draw if BOTH endpoints are
        // on-screen (avoids ugly leader lines off the edge of the
        // view).
        for ((_, lines) in StarCatalog.constellations) {
            for ((a, b) in lines) {
                val pa = starScreen[a] ?: continue
                val pb = starScreen[b] ?: continue
                canvas.drawLine(pa[0], pa[1], pb[0], pb[1], constLinePaint)
            }
        }

        // Moon + planets.
        val moon = SolarSystem.moon(jd)
        drawBody(canvas, moon, w, h, isMoon = true)
        for (p in SolarSystem.planets(jd)) {
            drawBody(canvas, p, w, h, isMoon = false)
        }

        // HUD overlays.
        drawCompassStrip(canvas, w, h)
        drawAltitudeTick(canvas, w, h)
        drawStatusBanner(canvas, w, h)
    }

    private fun drawBody(
        canvas: Canvas, body: SolarSystem.Body,
        w: Int, h: Int, isMoon: Boolean,
    ) {
        val (az, alt) = AstroMath.raDecToAzAlt(
            body.raHours, body.decDeg, latDeg, lonDeg, jd
        ).let { it[0] to it[1] }
        if (alt < -2.0) return
        val pt = toScreen(az, alt, w, h) ?: return
        planetPaint.color = body.color
        val r = if (isMoon) 8f else (5f - body.magnitude.toFloat() * 0.4f).coerceIn(2.5f, 5f)
        canvas.drawCircle(pt[0], pt[1], r, planetPaint)
        // Always label moon + planets so the user knows what each
        // dot is.
        labelPaint.color = body.color
        canvas.drawText(body.name, pt[0] + r + 2f, pt[1] + 4f, labelPaint)
        labelPaint.color = Color.WHITE
    }

    /** Horizon line — a great circle at alt=0, drawn as a series
     *  of line segments across the view. */
    private fun drawHorizon(canvas: Canvas, w: Int, h: Int) {
        var prev: FloatArray? = null
        // Sample 73 points across 360° of azimuth — 5° steps.
        for (i in 0..72) {
            val az = i * 5.0
            val pt = toScreen(az, 0.0, w, h)
            if (pt != null && prev != null) {
                canvas.drawLine(prev[0], prev[1], pt[0], pt[1], horizonPaint)
            }
            prev = pt
        }
    }

    /** Top-of-screen compass strip — N / NE / E / SE / etc tick marks
     *  + the current centerAz numerical readout. */
    private fun drawCompassStrip(canvas: Canvas, w: Int, h: Int) {
        val stripY = 10f
        // Center azimuth label.
        val azLabel = "${centerAzLabel()}  ${formatDeg(azCenter)}"
        val labelW = tapeLabel.measureText(azLabel)
        canvas.drawRect(
            w / 2f - labelW / 2f - 6f, stripY - 12f,
            w / 2f + labelW / 2f + 6f, stripY + 4f,
            statusBg,
        )
        canvas.drawText(azLabel, w / 2f - labelW / 2f, stripY, tapeLabel)
    }

    private fun centerAzLabel(): String {
        val a = ((azCenter % 360.0) + 360.0) % 360.0
        return when {
            a < 22.5 || a >= 337.5 -> "N"
            a < 67.5 -> "NE"
            a < 112.5 -> "E"
            a < 157.5 -> "SE"
            a < 202.5 -> "S"
            a < 247.5 -> "SW"
            a < 292.5 -> "W"
            else -> "NW"
        }
    }

    private fun formatDeg(d: Double): String = "${kotlin.math.round(d).toInt()}°"

    /** Right-edge altitude tick — shows where alt=0 is + current
     *  view altitude. */
    private fun drawAltitudeTick(canvas: Canvas, w: Int, h: Int) {
        val x = w - 18f
        // Vertical line for the tick region.
        canvas.drawLine(x, 20f, x, (h - 20f).toFloat(), tapePaint)
        // Center indicator (the user's current alt).
        canvas.drawLine(x - 5f, h / 2f, x + 5f, h / 2f, tapePaint)
        // Label.
        val txt = formatDeg(altCenter)
        canvas.drawText(txt, x - tapeLabel.measureText(txt) - 7f, h / 2f + 4f, tapeLabel)
    }

    /** Bottom-left status chip — GPS fix state. */
    private fun drawStatusBanner(canvas: Canvas, w: Int, h: Int) {
        val txt = if (hasFix) {
            "GPS ${formatLat()}, ${formatLon()}"
        } else {
            "No GPS — using default location"
        }
        val tw = tapeLabel.measureText(txt)
        canvas.drawRect(
            6f, h - 22f,
            6f + tw + 8f, h - 6f,
            statusBg,
        )
        canvas.drawText(txt, 10f, h - 11f, tapeLabel)
    }

    private fun formatLat(): String {
        val a = kotlin.math.abs(latDeg)
        val hemi = if (latDeg >= 0) "N" else "S"
        return "${"%.2f".format(a)}°$hemi"
    }
    private fun formatLon(): String {
        val a = kotlin.math.abs(lonDeg)
        val hemi = if (lonDeg >= 0) "E" else "W"
        return "${"%.2f".format(a)}°$hemi"
    }
}
