package com.glasshole.phone.plugindir.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.glasshole.phone.AppLog
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

/**
 * Stream phone GPS fixes to a glass plugin on demand. The glass side
 * opens its activity and sends a `start_trigger` message; we flip on
 * FusedLocationProviderClient updates and emit one `emit_type` message
 * per fix. A `stop_trigger` (or stop()) releases the GPS so it isn't
 * running when nothing's watching.
 *
 * Expected params JSON:
 * ```
 * {
 *   "start_trigger": "START",          # glass→phone message that arms updates
 *   "stop_trigger":  "STOP",           # glass→phone message that disarms
 *   "emit_type":     "LOCATION_UPDATE",# type used for outbound fixes
 *   "interval_ms":   3000,             # desired cadence (optional)
 *   "min_interval_ms": 1500            # fastest cadence we'll accept (optional)
 * }
 * ```
 *
 * Emitted payload shape (same as the old hard-coded CompassPlugin so
 * existing glass code keeps working):
 * ```
 * {"has_fix": true, "lat": ..., "lon": ..., "alt": ..., "acc": ..., "speed": ...}
 * ```
 * Missing fields are omitted; on permission denial a single
 * `{"has_fix": false}` is emitted so the glass can show "No GPS fix".
 */
class LocationStreamPrimitive : WorkerPrimitive {

    companion object {
        private const val TAG = "LocationStreamPrim"
        private const val DEFAULT_INTERVAL_MS = 3_000L
        private const val DEFAULT_MIN_INTERVAL_MS = 1_500L
    }

    private var startTrigger: String = "START"
    private var stopTrigger: String = "STOP"
    private var emitType: String = "LOCATION_UPDATE"
    private var intervalMs: Long = DEFAULT_INTERVAL_MS
    private var minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS

    private var appContext: Context? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var emit: ((type: String, payload: String) -> Unit)? = null
    private var running: Boolean = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            emitLocation(loc)
        }
    }

    override fun start(
        context: Context,
        params: JSONObject,
        emit: (type: String, payload: String) -> Unit
    ) {
        this.appContext = context.applicationContext
        this.startTrigger = params.optString("start_trigger", "START")
        this.stopTrigger = params.optString("stop_trigger", "STOP")
        this.emitType = params.optString("emit_type", "LOCATION_UPDATE")
        this.intervalMs = params.optLong("interval_ms", DEFAULT_INTERVAL_MS)
        this.minIntervalMs = params.optLong("min_interval_ms", DEFAULT_MIN_INTERVAL_MS)
        this.emit = emit
        this.fusedClient = LocationServices.getFusedLocationProviderClient(appContext!!)
        Log.i(TAG, "armed: start=$startTrigger stop=$stopTrigger emit=$emitType interval=${intervalMs}ms")
    }

    override fun onMessage(type: String, payload: String) {
        when (type) {
            startTrigger -> startUpdates()
            stopTrigger -> stopUpdates()
        }
    }

    override fun stop() {
        stopUpdates()
        emit = null
        fusedClient = null
        appContext = null
    }

    private fun startUpdates() {
        if (running) return
        val client = fusedClient ?: return
        val ctx = appContext ?: return

        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            AppLog.log(TAG, "location permission not granted — open GlassHole Phone once to grant it")
            emitNoFix()
            return
        }

        val request = LocationRequest.Builder(intervalMs)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(minIntervalMs)
            .build()

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            running = true
            AppLog.log(TAG, "location updates started")
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) emitLocation(loc)
            }
        } catch (e: SecurityException) {
            AppLog.log(TAG, "requestLocationUpdates denied: ${e.message}")
            emitNoFix()
        }
    }

    private fun stopUpdates() {
        if (!running) return
        running = false
        try { fusedClient?.removeLocationUpdates(callback) } catch (_: Exception) {}
        AppLog.log(TAG, "location updates stopped")
    }

    private fun emitLocation(loc: Location) {
        val json = JSONObject().apply {
            put("has_fix", true)
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            if (loc.hasAltitude()) put("alt", loc.altitude)
            if (loc.hasAccuracy()) put("acc", loc.accuracy.toDouble())
            if (loc.hasSpeed()) put("speed", loc.speed.toDouble())
        }.toString()
        emit?.invoke(emitType, json)
    }

    private fun emitNoFix() {
        val json = JSONObject().apply { put("has_fix", false) }.toString()
        emit?.invoke(emitType, json)
    }
}
