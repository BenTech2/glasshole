// SPDX-License-Identifier: MIT
package com.glasshole.phone.plugins.skytrack

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos

/**
 * Tiny client for OpenSky Network's public ADS-B feed.
 * https://openskynetwork.github.io/opensky-api/rest.html
 *
 * Anonymous tier: 400 calls/day, 10 sec resolution. Registered free
 * tier: 4000 calls/day. At our 30-second default polling we get
 * ~2,880/day — comfortably inside the registered limit, just over
 * the anonymous limit, so a registered account is recommended for
 * heavy use. The client doesn't require credentials — anonymous
 * works out of the box.
 *
 * No external HTTP library — uses the platform HttpURLConnection
 * directly so we don't bloat the phone APK for one endpoint.
 */
class OpenSkyClient {

    companion object {
        private const val TAG = "OpenSkyClient"
        private const val USER_AGENT = "GlassHole-SkyTrack/1.0 (+https://github.com)"

        /** Earth-flatten conversion: how many degrees of latitude
         *  correspond to one km. Constant (well, varies <1% across
         *  the planet). */
        private const val LAT_DEG_PER_KM = 1.0 / 111.32

        /** Build the bounding box (lamin, lomin, lamax, lomax) for a
         *  given observer + radius in km. Longitude needs to be
         *  scaled by cos(lat) to give an actual km box rather than
         *  a degree box. */
        fun bbox(latDeg: Double, lonDeg: Double, radiusKm: Double): DoubleArray {
            val latSpan = radiusKm * LAT_DEG_PER_KM
            val lonSpan = radiusKm * LAT_DEG_PER_KM /
                cos(latDeg * Math.PI / 180.0).coerceAtLeast(0.01)
            return doubleArrayOf(
                latDeg - latSpan,   // lamin
                lonDeg - lonSpan,   // lomin
                latDeg + latSpan,   // lamax
                lonDeg + lonSpan,   // lomax
            )
        }
    }

    data class State(
        val icao24: String,
        val callsign: String,
        val originCountry: String,
        val lat: Double,
        val lon: Double,
        val altBaro: Double?,
        val altGeo: Double?,
        val headingDeg: Double?,
        val velocityMps: Double?,
    ) {
        /** Best altitude estimate — prefer geometric (GPS), fall
         *  back to barometric, then null. */
        val altMeters: Double? get() = altGeo ?: altBaro
    }

    /** Fetch all aircraft inside the bounding box. Returns null on
     *  network error / HTTP non-200 / parse failure (caller logs +
     *  retries on the next tick). */
    fun fetchStates(
        latMin: Double, lonMin: Double,
        latMax: Double, lonMax: Double,
    ): List<State>? {
        val url = URL(
            "https://opensky-network.org/api/states/all" +
            "?lamin=${"%.4f".format(latMin)}" +
            "&lomin=${"%.4f".format(lonMin)}" +
            "&lamax=${"%.4f".format(latMax)}" +
            "&lomax=${"%.4f".format(lonMax)}"
        )
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8_000
                readTimeout = 12_000
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    Log.w(TAG, "OpenSky returned HTTP $code")
                    return null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseStates(body)
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "OpenSky fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Parse the OpenSky `/states/all` response. The schema is a
     *  fixed-position array per state:
     *    [0]  icao24
     *    [1]  callsign
     *    [2]  origin_country
     *    [3]  time_position
     *    [4]  last_contact
     *    [5]  longitude
     *    [6]  latitude
     *    [7]  baro_altitude (m)
     *    [8]  on_ground (bool)
     *    [9]  velocity (m/s)
     *    [10] true_track (heading deg)
     *    [11] vertical_rate
     *    [13] geo_altitude (m)
     *    ... */
    private fun parseStates(body: String): List<State> {
        val root = JSONObject(body)
        val states = root.optJSONArray("states") ?: return emptyList()
        val out = mutableListOf<State>()
        for (i in 0 until states.length()) {
            val s = states.optJSONArray(i) ?: continue
            val lat = s.optDoubleOrNull(6) ?: continue
            val lon = s.optDoubleOrNull(5) ?: continue
            // Skip on-ground (index 8 == true) — we don't want
            // taxiing planes cluttering the AR view.
            if (s.optBoolean(8, false)) continue
            out.add(State(
                icao24 = s.optString(0, "").trim(),
                callsign = s.optString(1, "").trim(),
                originCountry = s.optString(2, "").trim(),
                lat = lat,
                lon = lon,
                altBaro = s.optDoubleOrNull(7),
                altGeo = s.optDoubleOrNull(13),
                headingDeg = s.optDoubleOrNull(10),
                velocityMps = s.optDoubleOrNull(9),
            ))
        }
        return out
    }

    /** JSONArray.optDouble that returns null instead of NaN when the
     *  slot is JSON null (OpenSky uses null liberally for missing
     *  altitude / heading). */
    private fun JSONArray.optDoubleOrNull(index: Int): Double? {
        return if (isNull(index)) null
        else optDouble(index).takeIf { !it.isNaN() }
    }
}
