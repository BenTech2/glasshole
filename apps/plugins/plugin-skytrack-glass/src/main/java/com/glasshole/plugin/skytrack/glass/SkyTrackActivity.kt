// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skytrack.glass

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import com.glasshole.glass.sdk.GlassPluginMessage
import org.json.JSONObject

/**
 * SkyTrack — overhead aircraft AR.
 *
 * Same sensor pipeline SkyMap settled on (rotation_vector with
 * AXIS_X / AXIS_Z remap for Glass-worn axes, `altitude = -rawPitch`
 * to undo the prism flip, 0.35 low-pass smoothing). Listens for
 * `AIRCRAFT_UPDATE` payloads from the phone-side plugin and renders
 * each aircraft as a heading-rotated triangle at its observer-
 * relative (Az, Alt).
 *
 * On resume: REQ_TRACKER_START → phone polls OpenSky every
 * `poll_interval_s`. On pause: REQ_TRACKER_STOP → polling stops
 * (saves quota + battery when the glass is asleep).
 */
class SkyTrackActivity : Activity(), SensorEventListener {

    companion object {
        private const val TAG = "SkyTrackActivity"
        private const val LOC_PERM_REQUEST = 4001
        private const val HEADING_SMOOTH = 0.35f
        private const val PITCH_SMOOTH = 0.35f
        private const val DEFAULT_LAT = 38.0
        private const val DEFAULT_LON = -97.0
    }

    private lateinit var aircraftView: AircraftView

    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private val rotationMatrix = FloatArray(16)
    private val remappedMatrix = FloatArray(16)
    private val orientation = FloatArray(3)

    @Volatile private var lastAz: Float = 0f
    @Volatile private var lastAlt: Float = 0f
    @Volatile private var lastRawAz: Float = 0f
    @Volatile private var lastRawPitch: Float = 0f
    @Volatile private var smoothedAz: Float = Float.NaN
    @Volatile private var smoothedAlt: Float = Float.NaN

    private var locationManager: LocationManager? = null
    @Volatile private var glassLat: Double = DEFAULT_LAT
    @Volatile private var glassLon: Double = DEFAULT_LON
    @Volatile private var hasFix: Boolean = false
    @Volatile private var declination: Double = 0.0

    /** Observer-as-reported-by-phone at the moment of the last feed
     *  update. Aircraft Az/Alt are computed against this so the
     *  display is internally consistent even if glass and phone GPS
     *  diverge slightly. Falls back to [glassLat] / [glassLon] when
     *  the phone hasn't sent its location yet. */
    @Volatile private var observerLat: Double = DEFAULT_LAT
    @Volatile private var observerLon: Double = DEFAULT_LON

    @Volatile private var aircraft: List<Aircraft> = emptyList()
    @Volatile private var lastFeedMs: Long = 0L
    @Volatile private var trackerStatus: String = "Connecting…"

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            glassLat = location.latitude
            glassLon = location.longitude
            hasFix = true
            val gmf = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis(),
            )
            declination = gmf.declination.toDouble()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val renderHandler = Handler(Looper.getMainLooper())
    private val renderTick = object : Runnable {
        override fun run() {
            renderFrame()
            renderHandler.postDelayed(this, 100L)
        }
    }

    private lateinit var swipeDownDetector: GestureDetector

    private val phoneMessageListener = SkyTrackPluginService.Listener { msg ->
        renderHandler.post { handlePhoneMessage(msg) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        aircraftView = AircraftView(this)
        setContentView(aircraftView)

        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager

        swipeDownDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float,
            ): Boolean {
                val dy = if (e1 != null) e2.y - e1.y else 0f
                val dx = if (e1 != null) e2.x - e1.x else 0f
                if (velocityY > 1200 && dy > 80 && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    finish()
                    return true
                }
                return false
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                aircraftView.debugOverlay = !aircraftView.debugOverlay
                aircraftView.invalidate()
                return true
            }
        })

        SkyTrackPluginService.setListener(phoneMessageListener)

        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                LOC_PERM_REQUEST,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        startLocationUpdatesIfPermitted()
        applySettingsToView()
        SkyTrackPluginService.requestStart()
        renderHandler.post(renderTick)
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        SkyTrackPluginService.requestStop()
        renderHandler.removeCallbacks(renderTick)
    }

    override fun onDestroy() {
        SkyTrackPluginService.setListener(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOC_PERM_REQUEST) startLocationUpdatesIfPermitted()
    }

    private fun hasLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdatesIfPermitted() {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: return
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(locationListener::onLocationChanged)
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let(locationListener::onLocationChanged)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 50f, locationListener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 50f, locationListener)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location updates denied: ${e.message}")
        }
    }

    private fun applySettingsToView() {
        val prefs = getSharedPreferences(SkyTrackPluginService.PREFS_NAME, MODE_PRIVATE)
        aircraftView.showLabels = prefs.getBoolean("show_labels", true)
    }

    // --- Sensor ---

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        } catch (_: IllegalArgumentException) {
            return
        }
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X, SensorManager.AXIS_Z,
            remappedMatrix,
        )
        SensorManager.getOrientation(remappedMatrix, orientation)

        val rawAz = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val rawPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        lastRawAz = rawAz
        lastRawPitch = rawPitch
        val trueAz = ((rawAz + declination.toFloat() + 360f) % 360f)
        // See SkyMap copy for the negation rationale (Glass XE prism
        // flips the framebuffer vertically against the user's eye).
        val altitude = -rawPitch

        smoothedAz = if (smoothedAz.isNaN()) trueAz else {
            val delta = ((trueAz - smoothedAz + 540f) % 360f) - 180f
            (smoothedAz + delta * HEADING_SMOOTH + 360f) % 360f
        }
        smoothedAlt = if (smoothedAlt.isNaN()) altitude else {
            smoothedAlt + (altitude - smoothedAlt) * PITCH_SMOOTH
        }
        lastAz = smoothedAz
        lastAlt = smoothedAlt
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- Phone messages ---

    private fun handlePhoneMessage(msg: GlassPluginMessage) {
        when (msg.type) {
            "AIRCRAFT_UPDATE" -> applyFeed(msg.payload)
            "TRACKER_STATUS" -> {
                trackerStatus = try { JSONObject(msg.payload).optString("msg", "") }
                    catch (_: Exception) { msg.payload }
            }
            else -> { /* ignore */ }
        }
    }

    private fun applyFeed(payload: String) {
        try {
            val obj = JSONObject(payload)
            val obs = obj.optJSONObject("obs")
            if (obs != null) {
                observerLat = obs.optDouble("lat", glassLat)
                observerLon = obs.optDouble("lon", glassLon)
            }
            val arr = obj.optJSONArray("ac") ?: return
            val list = mutableListOf<Aircraft>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Aircraft(
                    callsign = o.optString("cs", "").trim(),
                    lat = o.optDouble("lat"),
                    lon = o.optDouble("lon"),
                    altMeters = if (o.has("alt") && !o.isNull("alt")) o.optDouble("alt") else null,
                    headingDeg = if (o.has("hdg") && !o.isNull("hdg")) o.optDouble("hdg") else null,
                    speedMps = if (o.has("spd") && !o.isNull("spd")) o.optDouble("spd") else null,
                    originCountry = o.optString("co", "").takeIf { it.isNotBlank() },
                ))
            }
            aircraft = list
            lastFeedMs = System.currentTimeMillis()
            trackerStatus = "${list.size} aircraft in feed"
        } catch (e: Exception) {
            Log.w(TAG, "AIRCRAFT_UPDATE parse failed: ${e.message}")
        }
    }

    private fun renderFrame() {
        aircraftView.updateFrame(
            obsLat = if (lastFeedMs > 0L) observerLat else glassLat,
            obsLon = if (lastFeedMs > 0L) observerLon else glassLon,
            hasFix = hasFix,
            azCenterDeg = lastAz.toDouble(),
            altCenterDeg = lastAlt.toDouble(),
            aircraft = aircraft,
            lastUpdateMs = lastFeedMs,
            trackerStatus = trackerStatus,
            rawAz = lastRawAz,
            rawPitch = lastRawPitch,
        )
    }

    // --- Input ---

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (swipeDownDetector.onTouchEvent(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
