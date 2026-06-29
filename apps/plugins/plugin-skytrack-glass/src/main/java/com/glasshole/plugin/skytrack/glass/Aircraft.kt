// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skytrack.glass

/**
 * One aircraft from the phone-side ADS-B feed (OpenSky state vector).
 * Coordinates in geographic; altitude in meters above mean sea level.
 * Heading in degrees clockwise from true north. Speed in m/s.
 *
 * Field choices match OpenSky's `/api/states/all` schema, trimmed to
 * what the glass renderer actually uses.
 */
data class Aircraft(
    val callsign: String,
    val lat: Double,
    val lon: Double,
    /** Meters MSL. Null when OpenSky doesn't report (often for
     *  ground vehicles or aircraft without geometric altitude). */
    val altMeters: Double?,
    /** Degrees clockwise from true north (0 = north, 90 = east). */
    val headingDeg: Double?,
    /** Meters per second. */
    val speedMps: Double?,
    /** ISO country code from OpenSky `origin_country` — useful for
     *  the on-glass label when callsign is empty. */
    val originCountry: String?,
)
