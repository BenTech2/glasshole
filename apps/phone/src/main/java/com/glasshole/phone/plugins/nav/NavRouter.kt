package com.glasshole.phone.plugins.nav

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Phone-side OSM routing helper. Two free, key-less services:
 *
 *   - **Nominatim** (https://nominatim.openstreetmap.org/) — geocode
 *     a free-text destination ("Pike Place Market") to lat/lon.
 *   - **OSRM demo** (https://router.project-osrm.org/) — route a
 *     pair of coordinates and return the GeoJSON polyline.
 *
 * Both are run by the OSM community and have a fair-use policy; this
 * code only hits them once per share-to-Glass action (not periodic),
 * keeps a descriptive User-Agent, and sets reasonable timeouts.
 *
 * All I/O blocks the calling thread — invoke from a daemon worker.
 */
object NavRouter {

    private const val TAG = "NavRouter"
    private const val USER_AGENT =
        "GlassHole-GlassNav/1.0 (+https://github.com/BenTech2/glasshole)"

    data class Point(val lat: Double, val lon: Double)

    data class RouteResult(
        val coords: List<Point>,
        val distanceMeters: Double,
        val durationSec: Double,
    )

    /** Resolve a destination string to lat/lon. Tries, in order:
     *    1. Inline coords ("47.609,-122.342" / "geo:47.609,-122.342")
     *    2. Maps URL coord extraction (after redirect-resolve).
     *       Maps URLs embed the destination's lat/lon as
     *       "@LAT,LON,ZOOMz" or "!3dLAT!4dLON" — authoritative even
     *       for businesses Nominatim has never heard of (Nominatim
     *       only knows OSM data, which misses most private business
     *       listings — that's why "Vista IT Group" returns nothing).
     *    3. Nominatim free-text geocode — last resort for proper
     *       address strings ("85 Pike St Seattle"). */
    fun resolveDestination(text: String): Point? {
        val trimmed = text.trim()
        parseInlineCoords(trimmed)?.let { return it }

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val resolved = followRedirects(trimmed, maxHops = 6) ?: trimmed
            Log.i(TAG, "URL resolved to: $resolved")
            extractCoordsFromMapsUrl(resolved)?.let {
                Log.i(TAG, "Coords from URL: ${it.lat},${it.lon}")
                return it
            }
        }
        return geocode(trimmed)
    }

    private fun parseInlineCoords(text: String): Point? {
        val raw = if (text.startsWith("geo:")) text.removePrefix("geo:") else text
        val parts = raw.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lon = parts[1].trim().toDoubleOrNull()
            if (lat != null && lon != null &&
                lat in -90.0..90.0 && lon in -180.0..180.0
            ) return Point(lat, lon)
        }
        return null
    }

    /** `@LAT,LON,ZOOMz` (path segment in /maps/place/...) or
     *  `!3dLAT!4dLON` (data-blob coords in newer place URLs). */
    private fun extractCoordsFromMapsUrl(url: String): Point? {
        Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)").find(url)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null) return Point(lat, lon)
        }
        Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)").find(url)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null) return Point(lat, lon)
        }
        return null
    }

    /** Follow up to [maxHops] HTTP redirects manually. */
    private fun followRedirects(start: String, maxHops: Int): String? {
        var current = start
        for (hop in 0 until maxHops) {
            val next = try {
                val conn = (java.net.URL(current).openConnection()
                    as java.net.HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                try {
                    val code = conn.responseCode
                    if (code in 300..399) conn.getHeaderField("Location") else null
                } finally { try { conn.disconnect() } catch (_: Exception) {} }
            } catch (e: Exception) {
                Log.w(TAG, "Redirect hop $hop ($current) failed: ${e.message}")
                if (hop == 0) return null else break
            }
            if (next.isNullOrBlank()) break
            Log.i(TAG, "Redirect hop ${hop + 1}: $current → $next")
            current = next
        }
        return current
    }

    data class GeoResult(
        val displayName: String,
        val shortName: String,
        val lat: Double,
        val lon: Double,
    )

    /** Multi-result geocode for the companion's search list. Up to
     *  [limit] hits, ordered by Nominatim's relevance ranking. */
    fun search(query: String, limit: Int = 8): List<GeoResult> {
        val url = "https://nominatim.openstreetmap.org/search" +
            "?q=" + URLEncoder.encode(query, "UTF-8") +
            "&format=json&limit=" + limit +
            "&addressdetails=1"
        val (code, body) = get(url) ?: return emptyList()
        if (code != 200) {
            Log.w(TAG, "Nominatim search HTTP $code for query=$query")
            return emptyList()
        }
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<GeoResult>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val display = o.optString("display_name", "")
                val short = display.substringBefore(",").trim()
                    .ifBlank { display.take(40) }
                out.add(
                    GeoResult(
                        displayName = display,
                        shortName = short,
                        lat = o.getString("lat").toDouble(),
                        lon = o.getString("lon").toDouble(),
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim search parse failed: ${e.message}")
            emptyList()
        }
    }

    private fun geocode(query: String): Point? {
        val url = "https://nominatim.openstreetmap.org/search" +
            "?q=" + URLEncoder.encode(query, "UTF-8") +
            "&format=json&limit=1"
        val (code, body) = get(url) ?: return null
        if (code != 200) {
            Log.w(TAG, "Nominatim HTTP $code for query=$query")
            return null
        }
        return try {
            val arr = JSONArray(body)
            if (arr.length() == 0) {
                Log.w(TAG, "Nominatim: no result for query=$query")
                return null
            }
            val obj = arr.getJSONObject(0)
            Point(
                lat = obj.getString("lat").toDouble(),
                lon = obj.getString("lon").toDouble(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim parse failed: ${e.message}")
            null
        }
    }

    /** OSRM driving route — overview=full so we get the dense polyline
     *  the glass needs to render an accurate route; geometries=geojson
     *  for trivial parsing (no polyline-encoding decode needed). */
    fun route(from: Point, to: Point, mode: String = "driving"): RouteResult? {
        val url = "https://router.project-osrm.org/route/v1/$mode/" +
            "${from.lon},${from.lat};${to.lon},${to.lat}" +
            "?overview=full&geometries=geojson"
        val (code, body) = get(url) ?: return null
        if (code != 200) {
            Log.w(TAG, "OSRM HTTP $code")
            return null
        }
        return try {
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") {
                Log.w(TAG, "OSRM code=${root.optString("code")} ${root.optString("message")}")
                return null
            }
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val r0 = routes.getJSONObject(0)
            val geom = r0.optJSONObject("geometry") ?: return null
            val coordsJson = geom.optJSONArray("coordinates") ?: return null
            val pts = ArrayList<Point>(coordsJson.length())
            for (i in 0 until coordsJson.length()) {
                val p = coordsJson.optJSONArray(i) ?: continue
                // GeoJSON ordering is [lon, lat].
                pts.add(Point(lat = p.getDouble(1), lon = p.getDouble(0)))
            }
            RouteResult(
                coords = pts,
                distanceMeters = r0.optDouble("distance", 0.0),
                durationSec = r0.optDouble("duration", 0.0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "OSRM parse failed: ${e.message}")
            null
        }
    }

    private fun get(url: String): Pair<Int, String>? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                code to body
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP GET failed: ${e.message}")
            null
        }
    }
}
