// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skymap.glass

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Spherical astronomy primitives — Julian date, Greenwich mean
 * sidereal time, RA/Dec → AzAlt transform, ecliptic ↔ equatorial.
 * All formulas from Meeus, "Astronomical Algorithms" (2nd ed.).
 * Accuracy target: arc-minute-class — fine for a 360×640 display
 * with a ~30° FOV.
 *
 * Conventions:
 *   - Angles in degrees at the public API; internal helpers may
 *     use radians where it simplifies trig.
 *   - RA: hours in catalog (we accept hours OR degrees and document
 *     each function's contract).
 *   - Az: 0° = North, increasing clockwise (90° = East).
 *   - Alt: 0° = horizon, +90° = zenith.
 *   - Time: UTC milliseconds (System.currentTimeMillis()).
 */
object AstroMath {

    /** J2000.0 epoch — 2000 Jan 1.5 UT (= JD 2451545.0). */
    const val J2000 = 2451545.0

    /** Mean obliquity of the ecliptic at J2000 (degrees).
     *  Time-varying by ~47"/century — fine to keep constant for our
     *  arc-minute target across a decade or two. */
    const val OBLIQUITY_DEG = 23.4393

    private const val DEG = PI / 180.0
    private const val RAD = 180.0 / PI

    /** Convert UTC ms → Julian date. Standard formula. */
    fun julianDate(utcMillis: Long): Double {
        // Days since Unix epoch (1970-01-01 00:00 UT = JD 2440587.5)
        return 2440587.5 + utcMillis / 86_400_000.0
    }

    /** Greenwich mean sidereal time (hours, 0..24) at the given
     *  Julian date. Higher-order terms dropped — gives ~arc-second
     *  accuracy over the next century. */
    fun gmstHours(jd: Double): Double {
        val t = (jd - J2000) / 36525.0
        // Meeus 12.4 — coefficients in degrees, divide by 15 for hours.
        var deg = 280.46061837 +
            360.98564736629 * (jd - J2000) +
            t * t * 0.000387933 -
            t * t * t / 38_710_000.0
        deg = ((deg % 360.0) + 360.0) % 360.0
        return deg / 15.0
    }

    /** Local apparent sidereal time (hours, 0..24) at the given
     *  observer longitude (east-positive degrees). We skip the
     *  nutation correction — its ~0.3" effect on RA is invisible at
     *  our display precision. */
    fun localSiderealHours(jd: Double, lonDeg: Double): Double {
        val gmst = gmstHours(jd)
        val lst = gmst + lonDeg / 15.0
        return ((lst % 24.0) + 24.0) % 24.0
    }

    /** Convert equatorial coordinates (RA hours, Dec degrees) to
     *  horizontal coordinates (Az degrees clockwise from N, Alt
     *  degrees above horizon) for an observer at [latDeg] / [lonDeg]
     *  on Earth at the given [jd]. */
    fun raDecToAzAlt(
        raHours: Double, decDeg: Double,
        latDeg: Double, lonDeg: Double,
        jd: Double,
    ): DoubleArray {
        val lst = localSiderealHours(jd, lonDeg)
        // Hour angle = LST - RA (hours), wrap to -12..12 then to radians.
        var haHours = lst - raHours
        haHours = ((haHours + 12.0) % 24.0 + 24.0) % 24.0 - 12.0
        val haRad = haHours * 15.0 * DEG
        val decRad = decDeg * DEG
        val latRad = latDeg * DEG

        val sinAlt = sin(decRad) * sin(latRad) +
                     cos(decRad) * cos(latRad) * cos(haRad)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        // Standard formula with atan2 for unambiguous quadrant.
        val y = -cos(decRad) * cos(latRad) * sin(haRad)
        val x = sin(decRad) - sin(latRad) * sinAlt
        var az = atan2(y, x) * RAD
        if (az < 0.0) az += 360.0
        return doubleArrayOf(az, alt * RAD)
    }

    /** Convert ecliptic longitude / latitude (degrees) to equatorial
     *  RA (hours) and Dec (degrees) at the J2000 epoch. */
    fun eclipticToEquatorial(lonDeg: Double, latDeg: Double): DoubleArray {
        val lon = lonDeg * DEG
        val lat = latDeg * DEG
        val obl = OBLIQUITY_DEG * DEG
        val sinDec = sin(lat) * cos(obl) + cos(lat) * sin(obl) * sin(lon)
        val dec = asin(sinDec.coerceIn(-1.0, 1.0))
        val y = sin(lon) * cos(obl) - (sin(lat) / cos(lat)) * sin(obl)
        val x = cos(lon)
        var ra = atan2(y, x) * RAD
        if (ra < 0.0) ra += 360.0
        return doubleArrayOf(ra / 15.0, dec * RAD)
    }

    /** Project a point at (Az, Alt) onto a 2D plane centered at the
     *  view direction (centerAz, centerAlt). Returns null if the
     *  point is more than [fovDeg]/2 from center (behind the user
     *  or off-screen). Uses gnomonic (rectilinear) projection —
     *  preserves straight lines at the cost of edge distortion
     *  beyond ~60° FOV. We're well under that.
     *
     *  Output coordinates: (x, y) in degrees from center. Caller
     *  scales to pixels. y > 0 = up, x > 0 = right.
     */
    fun project(
        azDeg: Double, altDeg: Double,
        centerAzDeg: Double, centerAltDeg: Double,
        fovDeg: Double,
    ): DoubleArray? {
        val ra = azDeg * DEG
        val dec = altDeg * DEG
        val raC = centerAzDeg * DEG
        val decC = centerAltDeg * DEG
        // Dot product of unit vectors — gives cos(angular separation).
        val cosSep = sin(decC) * sin(dec) +
                     cos(decC) * cos(dec) * cos(ra - raC)
        if (cosSep <= 0.0) return null  // > 90° away
        val fovRad = fovDeg * DEG
        if (cosSep < cos(fovRad / 2.0 * 1.4)) return null  // tolerance margin
        // Gnomonic projection. Positive x = east of view direction.
        // Caller maps positive x to screen-right, which matches the
        // mirror perspective (east-of-facing appears right when the
        // user looks ahead, the natural AR convention).
        val x = cos(dec) * sin(ra - raC) / cosSep
        val y = (cos(decC) * sin(dec) - sin(decC) * cos(dec) * cos(ra - raC)) / cosSep
        return doubleArrayOf(x * RAD, y * RAD)
    }

    /** Helper: floor of (a/b) with negative-aware behavior, same as
     *  Java's Math.floorDiv but for doubles → ints. */
    fun floorDiv(a: Double, b: Double): Int = floor(a / b).toInt()
}
