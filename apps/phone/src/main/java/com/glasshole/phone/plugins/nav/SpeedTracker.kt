package com.glasshole.phone.plugins.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Phone GPS → SPEED_UPDATE on the "glassnav" plugin id. Runs in the
 * NavPlugin's process (no separate Service) since the only consumer is
 * the on-glass HUD and the Maps notification already keeps phone GPS
 * warm while nav is active. Activated when Maps starts emitting nav
 * notifications, deactivated when Maps closes nav.
 *
 * We prefer Location.getSpeed() (Android's smoothed value, populated
 * on most modern devices) but fall back to a sample-delta computation
 * when the OS returns 0.0 for stationary or just-warming-up fixes.
 */
object SpeedTracker {

    private const val TAG = "SpeedTracker"
    /** 2 s cadence keeps the HUD fresh without burning battery — the
     *  underlying Fused/GPS provider may push less often than this if
     *  the device is stationary. */
    private const val MIN_UPDATE_MS = 2_000L
    /** Skip an update if it'd move us less than this — kills GPS jitter
     *  at standstills. */
    private const val MIN_DISTANCE_M = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var lastLocation: Location? = null
    private var sampleCount = 0

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speedMps = bestSpeedEstimate(location).toFloat()
            val bearing = if (location.hasBearing()) location.bearing else 0f
            val alt = if (location.hasAltitude()) location.altitude else 0.0
            // GlassNav's MainActivity decodes a 32-byte binary envelope:
            //   bytes 0..7   lat   (double, BIG_ENDIAN)
            //   bytes 8..15  lon   (double)
            //   bytes 16..23 alt   (double)
            //   bytes 24..27 speed (float)
            //   bytes 28..31 bearing (float)
            // We pack it here exactly the same way, then base64 it
            // (our BT bridge passes strings, not bytes).
            val buf = java.nio.ByteBuffer.allocate(32)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
            buf.putDouble(location.latitude)
            buf.putDouble(location.longitude)
            buf.putDouble(alt)
            buf.putFloat(speedMps)
            buf.putFloat(bearing)
            val b64 = android.util.Base64.encodeToString(
                buf.array(), android.util.Base64.NO_WRAP
            )
            val sent = com.glasshole.phone.service.BridgeService.instance
                ?.sendPluginMessage("glassnav", "LOC", b64) ?: false
            // Log every N samples so we can verify the pipeline without
            // drowning logcat. Drop once every ~10 s of streaming.
            sampleCount++
            if (sampleCount % 5 == 0) {
                Log.i(TAG, "LOC #$sampleCount " +
                    "${"%.5f".format(location.latitude)}," +
                    "${"%.5f".format(location.longitude)} " +
                    "speed=${"%.1f".format(speedMps)}m/s sent=$sent")
            }
            lastLocation = location
        }
        override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {
            mainHandler.post { stop(p as Any?) }
        }
    }

    /** Use OS-reported speed when present, otherwise infer from
     *  delta_distance / delta_time vs the previous fix. */
    private fun bestSpeedEstimate(loc: Location): Double {
        if (loc.hasSpeed() && loc.speed > 0.1f) return loc.speed.toDouble()
        val prev = lastLocation ?: return 0.0
        val dtMs = loc.time - prev.time
        if (dtMs < 500L) return 0.0
        val meters = loc.distanceTo(prev)
        return (meters / (dtMs / 1000.0))
    }

    fun start(context: Context) {
        if (running) {
            Log.i(TAG, "Speed tracker already running")
            return
        }
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            Log.w(TAG, "Location permission missing — speedometer offline")
            return
        }
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE)
            as? LocationManager ?: return
        try {
            // Both providers — GPS for accuracy when outdoors, NETWORK
            // as a fallback the moment we lose GPS. Speedometer is OK
            // with whichever fires.
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                @Suppress("MissingPermission")
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_UPDATE_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                @Suppress("MissingPermission")
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_UPDATE_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper()
                )
            }
            running = true
            Log.i(TAG, "Speed tracker on")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location request denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Speed tracker start failed: ${e.message}")
        }
    }

    fun stop(context: Any?) {
        if (!running) return
        val ctx = context as? Context ?: return
        val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE)
            as? LocationManager
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        running = false
        lastLocation = null
        Log.i(TAG, "Speed tracker off")
    }
}
