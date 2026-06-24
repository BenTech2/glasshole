// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skymap.glass

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-precision positions for the Sun, Moon, and naked-eye planets.
 * Accuracy target: arc-minute class, easily good enough for our 30°
 * FOV display.
 *
 * Sun: Meeus chapter 25 low-precision formula.
 * Moon: Meeus chapter 47 low-precision (5 terms).
 * Planets: simple Keplerian orbit propagation from J2000 mean
 *          elements, with linear motion of the elements. No
 *          perturbations — good to ~few arc-minutes for years
 *          around present, which is what we care about.
 */
object SolarSystem {

    private const val DEG = PI / 180.0
    private const val RAD = 180.0 / PI

    /** Result of a body computation — equatorial coordinates ready
     *  for the same AzAlt transform we use on catalog stars. */
    data class Body(
        val name: String,
        val raHours: Double,
        val decDeg: Double,
        /** Estimated apparent magnitude — used for screen-render
         *  size. Approximate; we want the planet to stand out from
         *  catalog stars, not be photometrically perfect. */
        val magnitude: Double,
        /** Tinge color hint for the renderer. */
        val color: Int,
    )

    /** Sun's ecliptic longitude (degrees) at the given Julian date. */
    private fun sunEclipticLongitude(jd: Double): Double {
        val n = jd - AstroMath.J2000
        // Mean longitude + mean anomaly (Meeus 25.2 / 25.3).
        var L = 280.460 + 0.9856474 * n
        var g = 357.528 + 0.9856003 * n
        L = ((L % 360.0) + 360.0) % 360.0
        g = ((g % 360.0) + 360.0) % 360.0
        // Apparent ecliptic longitude (single-term equation of center).
        return L + 1.915 * sin(g * DEG) + 0.020 * sin(2 * g * DEG)
    }

    /** Sun at the given UTC time. */
    fun sun(jd: Double): Body {
        val lon = sunEclipticLongitude(jd)
        val (raH, decD) = AstroMath.eclipticToEquatorial(lon, 0.0).let { it[0] to it[1] }
        return Body("Sun", raH, decD, -26.7, 0xFFFFE680.toInt())
    }

    /** Moon — Meeus low-precision (chapter 47).
     *  Returns equatorial coords. Accuracy ~5'-10'. */
    fun moon(jd: Double): Body {
        val t = (jd - AstroMath.J2000) / 36525.0
        val Lp = 218.3164591 + 481_267.88134236 * t -
                 0.0013268 * t * t  // mean longitude
        val D = 297.8502042 + 445_267.1115168 * t -
                0.00163 * t * t    // mean elongation
        val M = 357.5291092 + 35_999.0502909 * t -
                0.0001536 * t * t   // sun mean anomaly
        val Mp = 134.9634114 + 477_198.8676313 * t +
                 0.008997 * t * t    // moon mean anomaly
        val F = 93.2720993 + 483_202.0175273 * t -
                0.0034029 * t * t    // arg of latitude

        // Largest periodic terms (degrees). Truncated set — good to ~5'.
        val Lr = Lp +
            6.289 * sin(Mp * DEG) +
            -1.274 * sin((Mp - 2 * D) * DEG) +
            0.658 * sin(2 * D * DEG) +
            0.214 * sin(2 * Mp * DEG) +
            -0.186 * sin(M * DEG) +
            -0.059 * sin((2 * Mp - 2 * D) * DEG) +
            -0.057 * sin((Mp - 2 * D + M) * DEG)
        val betaDeg = 0.0 +
            5.128 * sin(F * DEG) +
            0.281 * sin((Mp + F) * DEG) +
            -0.278 * sin((Mp - F) * DEG) +
            -0.173 * sin((2 * D - F) * DEG)

        val (raH, decD) = AstroMath.eclipticToEquatorial(Lr, betaDeg).let { it[0] to it[1] }
        return Body("Moon", raH, decD, -12.7, 0xFFE0E0E0.toInt())
    }

    // --- Planetary Keplerian elements at J2000.0 + linear rates ---
    //
    // Format: (a, e, i, L, ϖ, Ω) plus their per-century rates.
    //   a = semi-major axis (AU)
    //   e = eccentricity
    //   i = inclination (deg)
    //   L = mean longitude (deg)
    //   ϖ = longitude of perihelion (deg)
    //   Ω = longitude of ascending node (deg)
    //
    // Source: JPL Standish, "Keplerian Elements for Approximate
    // Positions of the Major Planets" (good for 1800–2050).
    private data class Elements(
        val a0: Double, val aDot: Double,
        val e0: Double, val eDot: Double,
        val i0: Double, val iDot: Double,
        val L0: Double, val LDot: Double,
        val w0: Double, val wDot: Double,
        val O0: Double, val ODot: Double,
        val name: String,
        val magBase: Double,
        val color: Int,
    )

    private val planetElements = listOf(
        Elements(
            a0 = 0.38709927, aDot =  0.00000037,
            e0 = 0.20563593, eDot =  0.00001906,
            i0 = 7.00497902, iDot = -0.00594749,
            L0 = 252.25032350, LDot = 149472.67411175,
            w0 = 77.45779628, wDot = 0.16047689,
            O0 = 48.33076593, ODot = -0.12534081,
            name = "Mercury", magBase = -0.5, color = 0xFFAAAAAA.toInt(),
        ),
        Elements(
            a0 = 0.72333566, aDot =  0.00000390,
            e0 = 0.00677672, eDot = -0.00004107,
            i0 = 3.39467605, iDot = -0.00078890,
            L0 = 181.97909950, LDot = 58517.81538729,
            w0 = 131.60246718, wDot = 0.00268329,
            O0 = 76.67984255, ODot = -0.27769418,
            name = "Venus", magBase = -4.0, color = 0xFFFFF5C0.toInt(),
        ),
        // Earth needed only as the observer's heliocentric position.
        Elements(
            a0 = 1.00000261, aDot =  0.00000562,
            e0 = 0.01671123, eDot = -0.00004392,
            i0 = -0.00001531, iDot = -0.01294668,
            L0 = 100.46457166, LDot = 35999.37244981,
            w0 = 102.93768193, wDot = 0.32327364,
            O0 = 0.0, ODot = 0.0,
            name = "Earth", magBase = 0.0, color = 0,
        ),
        Elements(
            a0 = 1.52371034, aDot =  0.00001847,
            e0 = 0.09339410, eDot =  0.00007882,
            i0 = 1.84969142, iDot = -0.00813131,
            L0 = -4.55343205, LDot = 19140.30268499,
            w0 = -23.94362959, wDot = 0.44441088,
            O0 = 49.55953891, ODot = -0.29257343,
            name = "Mars", magBase = -2.0, color = 0xFFFF6644.toInt(),
        ),
        Elements(
            a0 = 5.20288700, aDot = -0.00011607,
            e0 = 0.04838624, eDot = -0.00013253,
            i0 = 1.30439695, iDot = -0.00183714,
            L0 = 34.39644051, LDot = 3034.74612775,
            w0 = 14.72847983, wDot = 0.21252668,
            O0 = 100.47390909, ODot = 0.20469106,
            name = "Jupiter", magBase = -2.5, color = 0xFFFFE4A0.toInt(),
        ),
        Elements(
            a0 = 9.53667594, aDot = -0.00125060,
            e0 = 0.05386179, eDot = -0.00050991,
            i0 = 2.48599187, iDot = 0.00193609,
            L0 = 49.95424423, LDot = 1222.49362201,
            w0 = 92.59887831, wDot = -0.41897216,
            O0 = 113.66242448, ODot = -0.28867794,
            name = "Saturn", magBase = -0.5, color = 0xFFE0CC80.toInt(),
        ),
    )

    /** Solve Kepler's equation E - e*sin(E) = M for E (radians).
     *  Newton-Raphson, converges in 3-5 iterations at our eccentricities. */
    private fun solveKepler(M: Double, e: Double): Double {
        var E = M
        repeat(8) {
            val dE = (E - e * sin(E) - M) / (1.0 - e * cos(E))
            E -= dE
            if (kotlin.math.abs(dE) < 1e-9) return E
        }
        return E
    }

    /** Heliocentric (x, y, z) AU coordinates from Keplerian elements. */
    private fun heliocentricXYZ(el: Elements, t: Double): DoubleArray {
        // Linear element values at this time.
        val a = el.a0 + el.aDot * t
        val e = el.e0 + el.eDot * t
        val i = (el.i0 + el.iDot * t) * DEG
        val L = (el.L0 + el.LDot * t) * DEG
        val w = (el.w0 + el.wDot * t) * DEG  // longitude of perihelion
        val O = (el.O0 + el.ODot * t) * DEG  // longitude of ascending node

        // Mean anomaly = mean longitude - longitude of perihelion.
        var M = L - w
        // Wrap to -PI..PI for solveKepler convergence.
        M = ((M + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI
        val E = solveKepler(M, e)

        // Position in the orbital plane.
        val xPrime = a * (cos(E) - e)
        val yPrime = a * sqrt(1.0 - e * e) * sin(E)
        // Argument of perihelion = w - O (longitude of perihelion -
        // longitude of node), then full rotation to ecliptic XYZ.
        val omega = w - O
        val cosO = cos(O); val sinO = sin(O)
        val cosOmega = cos(omega); val sinOmega = sin(omega)
        val cosI = cos(i); val sinI = sin(i)
        val x = (cosOmega * cosO - sinOmega * sinO * cosI) * xPrime +
                (-sinOmega * cosO - cosOmega * sinO * cosI) * yPrime
        val y = (cosOmega * sinO + sinOmega * cosO * cosI) * xPrime +
                (-sinOmega * sinO + cosOmega * cosO * cosI) * yPrime
        val z = (sinOmega * sinI) * xPrime + (cosOmega * sinI) * yPrime
        return doubleArrayOf(x, y, z)
    }

    /** Geocentric equatorial position of a planet at the given JD. */
    fun planet(name: String, jd: Double): Body? {
        val el = planetElements.firstOrNull { it.name == name } ?: return null
        if (name == "Earth") return null
        val earth = planetElements.first { it.name == "Earth" }
        val t = (jd - AstroMath.J2000) / 36525.0  // Julian centuries
        val pHelio = heliocentricXYZ(el, t)
        val eHelio = heliocentricXYZ(earth, t)
        // Geocentric ecliptic XYZ.
        val gx = pHelio[0] - eHelio[0]
        val gy = pHelio[1] - eHelio[1]
        val gz = pHelio[2] - eHelio[2]
        // Convert to ecliptic lon, lat (degrees).
        val lon = atan2(gy, gx) * RAD
        val lat = atan2(gz, sqrt(gx * gx + gy * gy)) * RAD
        val (raH, decD) = AstroMath.eclipticToEquatorial(lon, lat).let { it[0] to it[1] }
        return Body(el.name, raH, decD, el.magBase, el.color)
    }

    /** All visible planets (Mercury → Saturn). */
    fun planets(jd: Double): List<Body> =
        listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn")
            .mapNotNull { planet(it, jd) }
}
