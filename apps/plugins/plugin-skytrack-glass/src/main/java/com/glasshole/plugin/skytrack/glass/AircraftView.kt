// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skytrack.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas-based AR renderer for aircraft. Same gnomonic-projection
 * trick SkyMap uses for stars — observer-relative (Az, Alt) →
 * screen pixels, view direction at center. Triangles are rotated
 * to point in the aircraft's geographic heading.
 *
 * Stateless: feed in observer + view direction + aircraft list via
 * [updateFrame]; the view paints what's in front of the user.
 */
class AircraftView(context: Context) : View(context) {

    var fovDeg: Double = 35.0
    var showLabels: Boolean = true
    var debugOverlay: Boolean = false

    private var obsLat: Double = 0.0
    private var obsLon: Double = 0.0
    private var azCenter: Double = 0.0
    private var altCenter: Double = 0.0
    private var hasFix: Boolean = false
    private var aircraft: List<Aircraft> = emptyList()
    private var lastUpdateMs: Long = 0
    private var trackerStatus: String = "Waiting for feed…"
    private var debugRawAz: Float = 0f
    private var debugRawPitch: Float = 0f
    private var debugCounted: Int = 0

    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7FFF00.toInt()  // chartreuse — visible against any sky
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 1f
    }
    private val planeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF003300.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xBB000000.toInt()
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(2f, 0f, 1f, 0xCC000000.toInt())
    }
    private val tapeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val statusBg = Paint().apply { color = 0xB0000000.toInt() }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FF8000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun updateFrame(
        obsLat: Double, obsLon: Double, hasFix: Boolean,
        azCenterDeg: Double, altCenterDeg: Double,
        aircraft: List<Aircraft>, lastUpdateMs: Long,
        trackerStatus: String,
        rawAz: Float = 0f, rawPitch: Float = 0f,
    ) {
        this.obsLat = obsLat
        this.obsLon = obsLon
        this.hasFix = hasFix
        this.azCenter = azCenterDeg
        this.altCenter = altCenterDeg
        this.aircraft = aircraft
        this.lastUpdateMs = lastUpdateMs
        this.trackerStatus = trackerStatus
        this.debugRawAz = rawAz
        this.debugRawPitch = rawPitch
        invalidate()
    }

    /** Gnomonic projection — same idea SkyMap uses but inlined to
     *  avoid a cross-module dependency. Positive x = east of view,
     *  positive y = above view. */
    private fun project(
        azDeg: Double, altDeg: Double,
    ): DoubleArray? {
        val ra = azDeg * PI / 180.0
        val dec = altDeg * PI / 180.0
        val raC = azCenter * PI / 180.0
        val decC = altCenter * PI / 180.0
        val cosSep = sin(decC) * sin(dec) + cos(decC) * cos(dec) * cos(ra - raC)
        if (cosSep <= 0.0) return null
        val fovRad = fovDeg * PI / 180.0
        if (cosSep < cos(fovRad / 2.0 * 1.4)) return null
        val x = cos(dec) * sin(ra - raC) / cosSep
        val y = (cos(decC) * sin(dec) - sin(decC) * cos(dec) * cos(ra - raC)) / cosSep
        return doubleArrayOf(x * 180.0 / PI, y * 180.0 / PI)
    }

    private fun toScreen(azDeg: Double, altDeg: Double, w: Int, h: Int): FloatArray? {
        val proj = project(azDeg, altDeg) ?: return null
        val scale = max(w, h) / fovDeg
        val sx = (proj[0] * scale).toFloat()
        val sy = (proj[1] * scale).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        if (sx > w || sx < -w || sy > h || sy < -h) return null
        return floatArrayOf(cx + sx, cy - sy)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val w = width; val h = height
        if (w == 0 || h == 0) return

        drawHorizon(canvas, w, h)

        var visibleCount = 0
        for (ac in aircraft) {
            val azAlt = AircraftMath.observerToAircraftAzAlt(
                obsLat, obsLon, 0.0,
                ac.lat, ac.lon, ac.altMeters,
            ) ?: continue
            val az = azAlt[0]
            val alt = azAlt[1]
            val pt = toScreen(az, alt, w, h) ?: continue
            visibleCount++

            // Size by slant range — closer = bigger triangle. Cap so
            // a 5-km low pass doesn't fill the screen.
            val slantKm = AircraftMath.slantRangeMeters(
                obsLat, obsLon, 0.0,
                ac.lat, ac.lon, ac.altMeters,
            ) / 1000.0
            val size = (16.0 - (slantKm / 12.0)).coerceIn(7.0, 16.0).toFloat()

            drawAircraft(canvas, pt[0], pt[1], size, ac.headingDeg, ac.altMeters)

            if (showLabels) {
                val label = buildLabel(ac)
                drawLabelWithShadow(canvas, label, pt[0] + size + 4f, pt[1] + 4f)
            }
        }
        debugCounted = visibleCount

        drawCompassStrip(canvas, w, h)
        drawAltitudeTick(canvas, w, h)
        drawStatusBanner(canvas, w, h)
        if (debugOverlay) drawDebugHud(canvas, w, h)
    }

    private fun drawAircraft(
        canvas: Canvas, cx: Float, cy: Float, size: Float,
        headingDeg: Double?, altMeters: Double?,
    ) {
        // Triangle rotated to point in the aircraft's geographic
        // heading, transformed into screen space by subtracting the
        // view azimuth so a northbound jet renders pointing screen-
        // up when the user is facing north.
        val rotDeg = (headingDeg ?: 0.0) - azCenter
        val rotRad = rotDeg * PI / 180.0
        // Forward / back / wing offsets for an isoceles triangle.
        val ax = 0.0; val ay = -size.toDouble()
        val bx = -size * 0.6; val by = size * 0.55
        val ex = size * 0.6;  val ey = size * 0.55
        val cosT = cos(rotRad); val sinT = sin(rotRad)
        val path = Path().apply {
            moveTo(cx + (ax * cosT - ay * sinT).toFloat(),
                   cy + (ax * sinT + ay * cosT).toFloat())
            lineTo(cx + (bx * cosT - by * sinT).toFloat(),
                   cy + (bx * sinT + by * cosT).toFloat())
            lineTo(cx + (ex * cosT - ey * sinT).toFloat(),
                   cy + (ex * sinT + ey * cosT).toFloat())
            close()
        }
        // Color hint by altitude: low = orange (closer to ground),
        // mid = chartreuse (default), high = pale blue (cruise).
        planePaint.color = when {
            altMeters == null -> 0xFF888888.toInt()
            altMeters < 1500 -> 0xFFFF9933.toInt()  // < ~5,000 ft
            altMeters < 6000 -> 0xFF7FFF00.toInt()  // < ~20,000 ft
            else -> 0xFF66CCFF.toInt()              // ≥ ~20,000 ft
        }
        canvas.drawPath(path, planePaint)
        canvas.drawPath(path, planeOutline)
    }

    private fun buildLabel(ac: Aircraft): String {
        val cs = ac.callsign.takeIf { it.isNotBlank() }
            ?: ac.originCountry?.take(2) ?: "—"
        val altFt = ac.altMeters?.let { (it * 3.281).toInt() }
        return if (altFt != null) {
            "$cs  ${altFt / 1000}k ft"
        } else {
            cs
        }
    }

    private fun drawLabelWithShadow(canvas: Canvas, text: String, x: Float, y: Float) {
        canvas.drawText(text, x + 1f, y + 1f, labelShadow)
        canvas.drawText(text, x, y, labelPaint)
    }

    private fun drawHorizon(canvas: Canvas, w: Int, h: Int) {
        var prev: FloatArray? = null
        for (i in 0..72) {
            val az = i * 5.0
            val pt = toScreen(az, 0.0, w, h)
            if (pt != null && prev != null) {
                canvas.drawLine(prev[0], prev[1], pt[0], pt[1], horizonPaint)
            }
            prev = pt
        }
    }

    private fun drawCompassStrip(canvas: Canvas, w: Int, h: Int) {
        val stripY = 10f
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

    private fun drawAltitudeTick(canvas: Canvas, w: Int, h: Int) {
        val x = w - 18f
        canvas.drawLine(x, 20f, x, (h - 20f), horizonPaint)
        canvas.drawLine(x - 5f, h / 2f, x + 5f, h / 2f, horizonPaint)
        val txt = formatDeg(altCenter)
        canvas.drawText(txt, x - tapeLabel.measureText(txt) - 7f, h / 2f + 4f, tapeLabel)
    }

    private fun drawStatusBanner(canvas: Canvas, w: Int, h: Int) {
        val ageS = if (lastUpdateMs > 0) {
            (System.currentTimeMillis() - lastUpdateMs) / 1000L
        } else -1L
        val txt = when {
            !hasFix -> "No GPS — feed paused"
            ageS < 0 -> trackerStatus
            ageS < 60 -> "${aircraft.size} aircraft · ${ageS}s ago"
            else -> "${aircraft.size} aircraft · ${ageS / 60}m ago"
        }
        val tw = tapeLabel.measureText(txt)
        canvas.drawRect(6f, h - 22f, 6f + tw + 8f, h - 6f, statusBg)
        canvas.drawText(txt, 10f, h - 11f, tapeLabel)
    }

    private fun drawDebugHud(canvas: Canvas, w: Int, h: Int) {
        val lines = listOf(
            "rawAz   = ${"%6.1f".format(debugRawAz.toDouble())}°",
            "rawPitch= ${"%6.1f".format(debugRawPitch.toDouble())}°",
            "azCtr   = ${"%6.1f".format(azCenter)}°",
            "altCtr  = ${"%6.1f".format(altCenter)}°",
            "n_total = ${aircraft.size}",
            "n_shown = $debugCounted",
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFF00.toInt()
            textSize = 16f
            typeface = Typeface.MONOSPACE
        }
        val bg = Paint().apply { color = 0xCC000000.toInt() }
        val pad = 8f
        val lineH = 20f
        val boxW = 220f
        val boxH = lines.size * lineH + pad * 2f
        canvas.drawRect(8f, h / 2f - boxH / 2f, 8f + boxW, h / 2f + boxH / 2f, bg)
        var y = h / 2f - boxH / 2f + pad + 14f
        for (line in lines) {
            canvas.drawText(line, 14f, y, paint)
            y += lineH
        }
    }
}
