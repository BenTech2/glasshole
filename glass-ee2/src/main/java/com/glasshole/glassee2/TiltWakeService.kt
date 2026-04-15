package com.glasshole.glassee2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Foreground service that reads the accelerometer, detects a "look up"
 * head tilt while the screen is off, and briefly wakes the display —
 * mimicking the head-up gesture Glass XE/EE1 had baked into their system
 * image but which Google stripped from EE2's AOSP build.
 *
 * Detection is deliberately simple: track pitch (forward/back head lean)
 * from the gravity component of the accelerometer, remember the lowest
 * pitch seen in the last ~1.5s window, and fire a wake when current pitch
 * rises at least LOOKUP_DELTA_DEG above that minimum within that window.
 *
 * Runs under a PARTIAL_WAKE_LOCK so sensor events keep arriving when the
 * CPU is suspended after the screen times out. Battery hit is real —
 * opt-in only.
 */
class TiltWakeService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "TiltWake"
        private const val FG_ID = 42
        private const val CHANNEL_ID = "glasshole_tilt_wake"

        // Gesture thresholds (degrees of pitch, 0 = looking straight ahead,
        // negative = head tilted down). Calibrated conservatively so casual
        // head motion while looking at the timeline doesn't trigger.
        private const val LOOKDOWN_THRESHOLD = -25.0   // below this = considered "head down"
        private const val LOOKUP_DELTA_DEG = 20.0       // must rise this much from minimum
        private const val GESTURE_WINDOW_MS = 1500L     // from lookdown to lookup
        private const val WAKE_COOLDOWN_MS = 3000L      // ignore retriggers for this long
        private const val WAKE_DURATION_MS = 4000L      // how long to keep screen on
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    // Low-pass-filtered gravity
    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    // Sliding window: lowest pitch seen and its timestamp
    private var lowestPitch: Double = 999.0
    private var lowestPitchTime: Long = 0L

    // Most recent wake firing — used for cooldown debounce
    private var lastWakeTime: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(FG_ID, buildNotif("Tilt-to-wake active"))

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer — stopping")
            stopSelf()
            return
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassHole::TiltWake")
        cpuWakeLock?.acquire()

        Log.i(TAG, "TiltWakeService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Log.i(TAG, "TiltWakeService stopping")
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        cpuWakeLock?.let { if (it.isHeld) it.release() }
        cpuWakeLock = null
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // Low-pass filter for gravity (recommended Android pattern)
        val alpha = 0.85f
        if (!gravityInitialized) {
            gravity[0] = event.values[0]
            gravity[1] = event.values[1]
            gravity[2] = event.values[2]
            gravityInitialized = true
        } else {
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
        }

        // Pitch around the device's X axis. When the glass is worn and the
        // user looks straight ahead, the Z axis points forward out of the
        // lens and the Y axis points up, so gravity projects onto negative Y
        // (≈ -9.8 m/s² on Y). Tilting the head *down* rotates gravity toward
        // the +Z axis, so Z grows. Use atan2 to get signed degrees:
        //   head level:  pitch ≈ 0
        //   head down:   pitch negative
        //   head up:     pitch positive
        val gx = gravity[0].toDouble()
        val gy = gravity[1].toDouble()
        val gz = gravity[2].toDouble()
        val pitchRad = Math.atan2(-gz, Math.sqrt(gx * gx + gy * gy))
        val pitch = Math.toDegrees(pitchRad)

        val now = SystemClock.uptimeMillis()

        // Track rolling minimum pitch within the gesture window
        if (pitch < lowestPitch || (now - lowestPitchTime) > GESTURE_WINDOW_MS) {
            lowestPitch = pitch
            lowestPitchTime = now
        }

        // Gesture: we saw a sufficiently-low pitch recently, and current
        // pitch has risen LOOKUP_DELTA_DEG above that minimum.
        if (lowestPitch <= LOOKDOWN_THRESHOLD &&
            (pitch - lowestPitch) >= LOOKUP_DELTA_DEG &&
            (now - lowestPitchTime) <= GESTURE_WINDOW_MS &&
            (now - lastWakeTime) >= WAKE_COOLDOWN_MS
        ) {
            lastWakeTime = now
            lowestPitch = 999.0
            Log.i(TAG, "Tilt-up gesture — waking screen")
            wakeScreen()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun wakeScreen() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isInteractive) return  // already on — nothing to do
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "GlassHole::TiltWakeScreen"
            )
            wl.acquire(WAKE_DURATION_MS)
        } catch (e: Exception) {
            Log.e(TAG, "wakeScreen failed: ${e.message}")
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Tilt to wake",
                NotificationManager.IMPORTANCE_MIN
            )
        )
    }

    private fun buildNotif(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GlassHole")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
