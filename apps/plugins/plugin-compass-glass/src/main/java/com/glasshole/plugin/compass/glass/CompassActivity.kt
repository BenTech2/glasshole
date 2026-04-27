package com.glasshole.plugin.compass.glass

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.TextView

/**
 * On-glass compass UI. Heading is computed locally from the glass's
 * ROTATION_VECTOR sensor — Glass EE2 / EE1 / XE all ship an IMU with a
 * magnetometer, so we don't need the phone for azimuth. The phone side
 * of this plugin streams lat / lon / altitude / speed via its GPS.
 *
 * On open we ping the phone with "START" so it can begin location
 * updates; on close/destroy we send "STOP" so it can release the GPS.
 */
class CompassActivity : Activity(), SensorEventListener {

    companion object {
        private const val TAG = "CompassActivity"
        // Low-pass filter the heading so the needle doesn't jitter at ±2°
        // per frame from sensor noise.
        private const val HEADING_SMOOTHING = 0.15f
    }

    private lateinit var tape: CompassTapeView
    private lateinit var headingText: TextView
    private lateinit var cardinalText: TextView
    private lateinit var altitudeText: TextView
    private lateinit var latLonText: TextView
    private lateinit var speedText: TextView

    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private var smoothedHeading: Float = Float.NaN

    // Swipe-down-to-close (gesture same as other plugins).
    private var downX = 0f
    private var downY = 0f

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applyLocation(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compass)
        tape = findViewById(R.id.tape)
        headingText = findViewById(R.id.headingText)
        cardinalText = findViewById(R.id.cardinalText)
        altitudeText = findViewById(R.id.altitudeText)
        latLonText = findViewById(R.id.latLonText)
        speedText = findViewById(R.id.speedText)

        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val filter = IntentFilter(CompassGlassPluginService.ACTION_LOCATION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(locationReceiver, filter)
        }

        sendToPhone("START", "")
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroy() {
        try { unregisterReceiver(locationReceiver) } catch (_: Exception) {}
        sendToPhone("STOP", "")
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // Remap to match Glass's landscape display orientation — otherwise
        // the azimuth rotates opposite to how the user is actually facing.
        val rotation = windowManager.defaultDisplay.rotation
        val (axisX, axisY) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
        SensorManager.getOrientation(remapped, orientation)

        val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val normalized = (azimuthDeg + 360f) % 360f

        smoothedHeading = if (smoothedHeading.isNaN()) {
            normalized
        } else {
            // Low-pass with shortest-angle wrap — otherwise crossing 0°/360°
            // flings the needle around the long way.
            val delta = ((normalized - smoothedHeading + 540f) % 360f) - 180f
            (smoothedHeading + delta * HEADING_SMOOTHING + 360f) % 360f
        }

        tape.setHeading(smoothedHeading)
        val whole = smoothedHeading.toInt()
        headingText.text = String.format("%03d°", whole)
        cardinalText.text = degreesToCardinal(smoothedHeading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun applyLocation(intent: Intent) {
        val hasFix = intent.getBooleanExtra(CompassGlassPluginService.EXTRA_HAS_FIX, false)
        if (!hasFix) {
            altitudeText.text = "Alt: —"
            latLonText.text = "Lat: —\nLon: —"
            speedText.text = "No GPS fix"
            return
        }
        val lat = intent.getDoubleExtra(CompassGlassPluginService.EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(CompassGlassPluginService.EXTRA_LON, Double.NaN)
        val alt = intent.getDoubleExtra(CompassGlassPluginService.EXTRA_ALT, Double.NaN)
        val speed = intent.getDoubleExtra(CompassGlassPluginService.EXTRA_SPEED, Double.NaN)

        altitudeText.text = if (alt.isNaN()) "Alt: —" else String.format("Alt: %.0f m", alt)
        latLonText.text = if (lat.isNaN() || lon.isNaN()) "Lat: —\nLon: —"
            else String.format("Lat: %.5f\nLon: %.5f", lat, lon)
        // Speed from FusedLocationProviderClient is m/s. Converting to km/h
        // and mph side by side so hikers and drivers can both glance.
        speedText.text = if (speed.isNaN() || speed < 0.3) "" // filter GPS drift when stationary
            else String.format("%.1f km/h · %.1f mph", speed * 3.6, speed * 2.23694)
    }

    private fun degreesToCardinal(deg: Float): String {
        val dirs = arrayOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        )
        val idx = (((deg + 11.25f) % 360f) / 22.5f).toInt()
        return dirs[idx.coerceIn(0, dirs.size - 1)]
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; return true
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.y - downY
                val absDy = kotlin.math.abs(dy)
                val absDx = kotlin.math.abs(event.x - downX)
                if (dy > 120 && absDy > absDx * 1.3f) { finish(); return true }
            }
        }
        return false
    }

    private fun sendToPhone(type: String, payload: String) {
        try {
            val intent = Intent("com.glasshole.glass.MESSAGE_TO_PHONE").apply {
                putExtra("plugin_id", "compass")
                putExtra("message_type", type)
                putExtra("payload", payload)
            }
            for (pkg in listOf(
                "com.glasshole.glassee1",
                "com.glasshole.glassxe",
                "com.glasshole.glassee2"
            )) {
                intent.setPackage(pkg)
                sendBroadcast(intent)
            }
        } catch (_: Exception) {}
    }
}
