// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skymap.glass

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.core.app.ActivityCompat

/**
 * SkyMap — point your head, see the stars / constellations / Moon /
 * planets that are in front of you.
 *
 * No camera; just sensors + GPS + bundled astronomy data + a Canvas
 * overlay. Runs on XE / EE1 / EE2 with the same APK.
 *
 * Orientation pipeline:
 *   TYPE_ROTATION_VECTOR → rotation matrix → remap to landscape →
 *   getOrientation → (azimuth, pitch) → smoothing → AzAlt center
 *
 * Apply Android's GeomagneticField declination so the sensor's
 * magnetic-north heading is corrected to true north (which is what
 * the celestial coordinate transform expects).
 *
 * GPS pipeline:
 *   LocationManager NETWORK + GPS providers; first fix wins. We
 *   accept stale fixes and just show a "fix is fuzzy" status — for
 *   sky math, city-level accuracy is plenty.
 */
class SkyMapActivity : Activity(), SensorEventListener {

    companion object {
        private const val TAG = "SkyMapActivity"
        private const val LOC_PERM_REQUEST = 3001
        // Higher = snappier head tracking, more jitter. 0.35 is the
        // sweet spot we found by feel — low enough to mask sensor
        // noise (especially indoors with magnetic interference)
        // without making the view feel like it's dragging behind
        // the user's head movement.
        private const val HEADING_SMOOTH = 0.35f
        private const val PITCH_SMOOTH = 0.35f

        /** Default observer location if the glass has no GPS fix
         *  yet — center of the continental US, so the constellations
         *  we draw are at least plausibly aligned to "what the user
         *  would see in northern hemisphere mid-latitudes". */
        private const val DEFAULT_LAT = 38.0
        private const val DEFAULT_LON = -97.0
    }

    private lateinit var skyView: SkyView

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
    @Volatile private var observerLat: Double = DEFAULT_LAT
    @Volatile private var observerLon: Double = DEFAULT_LON
    @Volatile private var hasFix: Boolean = false
    /** Declination correction (true minus magnetic, degrees). Set
     *  when we get a GPS fix; applied before passing azimuth to
     *  AstroMath which works in true-north convention. */
    @Volatile private var declination: Double = 0.0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            observerLat = location.latitude
            observerLon = location.longitude
            hasFix = true
            // Refresh declination — varies by ~0.1°/100km, so we
            // update on every fix.
            val gmf = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis(),
            )
            declination = gmf.declination.toDouble()
            Log.i(TAG, "Fix: ${location.latitude}, ${location.longitude} " +
                "(declination=${"%.1f".format(declination)}°)")
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** ~10 Hz render tick. Sensor updates run at sensor cadence
     *  (50+ Hz) but the screen refresh doesn't need to keep up;
     *  10 Hz is plenty smooth for head-tracking-style use. */
    private val renderHandler = Handler(Looper.getMainLooper())
    private val renderTick = object : Runnable {
        override fun run() {
            renderFrame()
            renderHandler.postDelayed(this, 100L)
        }
    }

    // Swipe-down gesture detector — fling-style dismissal mirrors
    // the rest of the GlassHole plugins.
    private lateinit var swipeDownDetector: GestureDetector

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

        skyView = SkyView(this)
        setContentView(skyView)

        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor == null) {
            Log.w(TAG, "No TYPE_ROTATION_VECTOR — pointing direction will be static")
        }

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
                // Toggle debug HUD on every tap. Useful for live
                // calibration of pitch sign / sensor remap.
                skyView.debugOverlay = !skyView.debugOverlay
                skyView.invalidate()
                return true
            }
        })

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
        renderHandler.post(renderTick)
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        renderHandler.removeCallbacks(renderTick)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOC_PERM_REQUEST) {
            startLocationUpdatesIfPermitted()
        }
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
            // Seed from last-known so the first frame has SOMETHING
            // sensible even before the first fresh fix lands.
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

    // --- Sensor ---

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        } catch (_: IllegalArgumentException) {
            return
        }
        // Remap to a coordinate frame aligned with the user's head
        // when Glass is worn — not the phone-landscape frame that
        // Compass uses. AXIS_X, AXIS_Z is the canonical Glass-worn
        // mapping (same one GlassNav uses for head-up map rotation):
        //   - X axis stays "right of user"
        //   - The remap puts Z = "up of user" (was Y before)
        // After this, orientation[1] is the user's actual pitch
        // (looking up/down) rather than the device's natural-frame
        // pitch which traces 0→90→0 across a head-tilt arc.
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X, SensorManager.AXIS_Z,
            remappedMatrix,
        )
        SensorManager.getOrientation(remappedMatrix, orientation)

        // azimuth: orientation[0] — radians, 0 = pointing at magnetic
        //          north, range -π..π. Positive = rotated CW (east).
        // pitch:   orientation[1] — radians, range -π/2..π/2. For our
        //          remapped landscape: positive = tilted upward (looking
        //          at sky); negative = looking at the ground.
        val rawAz = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val rawPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        lastRawAz = rawAz
        lastRawPitch = rawPitch
        // Apply declination so azimuth is TRUE north (what the sky
        // math expects).
        val trueAz = ((rawAz + declination.toFloat() + 360f) % 360f)
        // With the Glass-worn remap, rawPitch is canonical: -90° at
        // the floor, 0° at the horizon, +90° at the zenith.
        //
        // But we negate it before feeding altCenter to the projection
        // because Glass XE's prism reflects the display through a
        // lens that flips the framebuffer vertically against the
        // user's eye. The math-correct mapping (altitude = rawPitch)
        // produces a frame that's optically inverted; negating
        // restores the AR overlay feel — look up, see the upward
        // sky; look down, see nothing (below-horizon).
        val altitude = -rawPitch

        // Low-pass smoothing with shortest-angle wrap for azimuth.
        smoothedAz = if (smoothedAz.isNaN()) {
            trueAz
        } else {
            val delta = ((trueAz - smoothedAz + 540f) % 360f) - 180f
            (smoothedAz + delta * HEADING_SMOOTH + 360f) % 360f
        }
        smoothedAlt = if (smoothedAlt.isNaN()) {
            altitude
        } else {
            smoothedAlt + (altitude - smoothedAlt) * PITCH_SMOOTH
        }
        lastAz = smoothedAz
        lastAlt = smoothedAlt
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun renderFrame() {
        skyView.updateFrame(
            latDeg = observerLat,
            lonDeg = observerLon,
            utcMillis = System.currentTimeMillis(),
            azCenterDeg = lastAz.toDouble(),
            altCenterDeg = lastAlt.toDouble(),
            hasFix = hasFix,
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
