// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skytrack.glass

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geodesy helpers — convert observer + aircraft geographic coordinates
 * to the (Az, Alt) pair the SkyMap-style projection expects. All
 * inputs in degrees / meters; outputs in degrees.
 *
 *   Az  — degrees clockwise from true north (0 = N, 90 = E)
 *   Alt — degrees above the local horizon (0 = horizon, +90 = zenith)
 *
 * Accuracy target: arc-minute, plenty for a ~30° FOV on glass with
 * triangles ~12 px wide.
 */
object AircraftMath {

    private const val DEG = PI / 180.0
    private const val RAD = 180.0 / PI
    /** Mean Earth radius (meters). WGS84 ellipsoid varies by ~0.3% —
     *  inconsequential at this accuracy target. */
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Observer-relative (Az, Alt) for an aircraft.
     *
     *  Az is initial bearing from observer to aircraft along the
     *  great circle (true-north reference); Alt is the angle the
     *  line-of-sight makes with the local horizontal plane,
     *  accounting for Earth curvature (negligible at typical
     *  flight distances but trivially included).
     *
     *  Returns null when the aircraft has no altitude reading
     *  (we can't compute elevation without it). */
    fun observerToAircraftAzAlt(
        obsLatDeg: Double, obsLonDeg: Double, obsAltM: Double,
        acLatDeg: Double, acLonDeg: Double, acAltM: Double?,
    ): DoubleArray? {
        if (acAltM == null) return null

        val phi1 = obsLatDeg * DEG
        val phi2 = acLatDeg * DEG
        val dLon = (acLonDeg - obsLonDeg) * DEG

        // Great-circle bearing (initial).
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        var bearing = atan2(y, x) * RAD
        if (bearing < 0.0) bearing += 360.0

        // Haversine ground distance (m along Earth surface).
        val a = sin((phi2 - phi1) / 2.0).let { it * it } +
                cos(phi1) * cos(phi2) * sin(dLon / 2.0).let { it * it }
        val groundDist = 2.0 * EARTH_RADIUS_M * asin(sqrt(a).coerceAtMost(1.0))

        // Elevation: angle of line-of-sight above the observer's
        // local horizontal. Approximate — flat-earth atan is good
        // to ~0.05° at 100 km, well below our display precision.
        val altDiff = acAltM - obsAltM
        val elevation = atan2(altDiff, groundDist) * RAD

        return doubleArrayOf(bearing, elevation)
    }

    /** Slant range (meters) from observer to aircraft — used by the
     *  renderer to size triangles inversely with distance. */
    fun slantRangeMeters(
        obsLatDeg: Double, obsLonDeg: Double, obsAltM: Double,
        acLatDeg: Double, acLonDeg: Double, acAltM: Double?,
    ): Double {
        val acAlt = acAltM ?: obsAltM
        val phi1 = obsLatDeg * DEG
        val phi2 = acLatDeg * DEG
        val dLon = (acLonDeg - obsLonDeg) * DEG
        val a = sin((phi2 - phi1) / 2.0).let { it * it } +
                cos(phi1) * cos(phi2) * sin(dLon / 2.0).let { it * it }
        val ground = 2.0 * EARTH_RADIUS_M * asin(sqrt(a).coerceAtMost(1.0))
        val dh = acAlt - obsAltM
        return sqrt(ground * ground + dh * dh)
    }
}
