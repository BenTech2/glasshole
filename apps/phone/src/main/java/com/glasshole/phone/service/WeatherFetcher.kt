package com.glasshole.phone.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open-Meteo wrapper. Phone fetches weather periodically and BridgeService
 * relays it to glass over BT. Open-Meteo is keyless / signup-less; the
 * forecast endpoint takes a lat/lon and returns current temp + WMO
 * weather code + daily high/low.
 *
 * Coarse location flow: prefer the system's last-known location from the
 * NETWORK provider (Wi-Fi positioning, no GPS draw) and only fall back
 * to GPS if that's stale. We never request live location updates — for
 * weather the ~30 min cadence + last-known is plenty.
 *
 * No external dependencies (HttpURLConnection + JSONObject only); all
 * I/O is on the calling worker thread.
 */
object WeatherFetcher {

    private const val TAG = "WeatherFetcher"
    /** Open-Meteo's free forecast endpoint. We pull current temp,
     *  weather code, daily high/low. timezone=auto so the daily slice
     *  matches the device's local day rather than UTC. */
    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    /** Open-Meteo's keyless air-quality endpoint. Returns the EPA's
     *  US AQI (0-500) which we surface on the chip — same family as
     *  the AirNow scale most US users recognize. european_aqi is also
     *  available but we don't ship it (one number is enough). */
    private const val AQI_ENDPOINT = "https://air-quality-api.open-meteo.com/v1/air-quality"
    /** Treat a location older than this as stale enough to refuse, so
     *  we don't serve days-old fixes when the user travels. */
    private const val STALE_LOCATION_MS = 6L * 60 * 60 * 1000  // 6h

    data class Result(
        val tempCurrent: Double,
        val tempHigh: Double,
        val tempLow: Double,
        val weatherCode: Int,
        val isDay: Boolean,
        val units: String,            // "F" or "C"
        val aqi: Int?,                // US AQI (0..500), null when unavailable
        val fetchedAt: Long,          // ms epoch
    )

    /** Read the device's freshest cached coarse fix. Returns null when
     *  no fix is available (perm missing, fresh boot before any
     *  provider has reported, etc.). Safe to call without holding the
     *  perm — we check first and return null silently. */
    fun lastKnownLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val candidates = listOfNotNull(
            tryGetLastKnown(lm, LocationManager.NETWORK_PROVIDER),
            tryGetLastKnown(lm, LocationManager.GPS_PROVIDER),
            tryGetLastKnown(lm, LocationManager.PASSIVE_PROVIDER),
        )
        if (candidates.isEmpty()) return null
        val now = System.currentTimeMillis()
        return candidates
            .filter { now - it.time < STALE_LOCATION_MS }
            .maxByOrNull { it.time }
    }

    private fun tryGetLastKnown(lm: LocationManager, provider: String): Location? {
        return try {
            @Suppress("MissingPermission") // checked by caller
            lm.getLastKnownLocation(provider)
        } catch (_: SecurityException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    /** Pulls a *fresh* fix from the NETWORK provider (Wi-Fi positioning;
     *  usually settles in 1-3 s) with a bounded timeout. Falls back to
     *  [lastKnownLocation] if no update arrives in time so the weather
     *  fetch can still proceed. Listener is always removed before
     *  invoking the callback so we never leak a registration.
     *
     *  Used by the scheduler so the weather chip actually tracks
     *  movement instead of pinning to whatever the system's location
     *  cache happens to hold. */
    fun requestFreshLocation(
        context: Context,
        timeoutMs: Long = 8_000L,
        onResult: (Location?) -> Unit,
    ) {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { onResult(null); return }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) { onResult(lastKnownLocation(context)); return }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (delivered.compareAndSet(false, true)) {
                    try { lm.removeUpdates(this) } catch (_: Exception) {}
                    onResult(loc)
                }
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }

        handler.postDelayed({
            if (delivered.compareAndSet(false, true)) {
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
                // Timed out — best-effort fall back to the cache so the
                // outer fetch doesn't fail just because the OS hasn't
                // refreshed its fix in the listener window.
                onResult(lastKnownLocation(context))
            }
        }, timeoutMs)

        // Prefer NETWORK (Wi-Fi positioning, no GPS draw). If it's
        // disabled the listener will simply never fire and our timeout
        // path delivers last-known.
        val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            LocationManager.NETWORK_PROVIDER
        else LocationManager.PASSIVE_PROVIDER
        try {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(provider, 0L, 0f, listener, handler.looper)
        } catch (e: Exception) {
            Log.w(TAG, "requestLocationUpdates failed: ${e.message}")
            if (delivered.compareAndSet(false, true)) {
                handler.removeCallbacksAndMessages(null)
                onResult(lastKnownLocation(context))
            }
        }
    }

    /** Air-quality secondary call. Returns null on any failure (the
     *  forecast call already succeeded so a missing AQI just means the
     *  chip drops its AQI line — not the whole weather block). */
    fun fetchAirQuality(lat: Double, lon: Double): Int? {
        val url = "$AQI_ENDPOINT?latitude=${"%.4f".format(lat)}&longitude=${"%.4f".format(lon)}" +
            "&current=us_aqi&timezone=auto"
        val conn: HttpURLConnection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GlassHole/1.0")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AQI URL open failed: ${e.message}")
            return null
        }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).optJSONObject("current") ?: return null
            // us_aqi can be null in regions Open-Meteo doesn't model.
            val raw = current.opt("us_aqi") ?: return null
            (raw as? Number)?.toInt()
        } catch (e: Exception) {
            Log.w(TAG, "AQI fetch/parse failed: ${e.message}")
            null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** Blocking HTTP fetch. Returns null on network failure / parse
     *  failure / non-200; caller's responsible for running this off the
     *  main thread. [units] picks Fahrenheit vs Celsius via Open-Meteo's
     *  temperature_unit parameter so we don't need client-side math. */
    fun fetch(lat: Double, lon: Double, units: String): Result? {
        val tempUnit = if (units.equals("F", ignoreCase = true)) "fahrenheit" else "celsius"
        val url = "$ENDPOINT?latitude=${"%.4f".format(lat)}&longitude=${"%.4f".format(lon)}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&temperature_unit=$tempUnit" +
            "&timezone=auto&forecast_days=1"
        val conn: HttpURLConnection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GlassHole/1.0")
            }
        } catch (e: Exception) {
            Log.w(TAG, "URL open failed: ${e.message}")
            return null
        }
        return try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "Open-Meteo returned HTTP $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body, units)
        } catch (e: Exception) {
            Log.w(TAG, "Fetch/parse failed: ${e.message}")
            null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parse(body: String, units: String): Result? {
        return try {
            val root = JSONObject(body)
            val current = root.optJSONObject("current") ?: return null
            val daily = root.optJSONObject("daily")
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            val isDay = current.optInt("is_day", 1) == 1
            if (temp.isNaN() || code < 0) return null
            val high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, temp) ?: temp
            val low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, temp) ?: temp
            Result(
                tempCurrent = temp,
                tempHigh = high,
                tempLow = low,
                weatherCode = code,
                isDay = isDay,
                units = units.uppercase(),
                aqi = null,
                fetchedAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed: ${e.message}")
            null
        }
    }
}
