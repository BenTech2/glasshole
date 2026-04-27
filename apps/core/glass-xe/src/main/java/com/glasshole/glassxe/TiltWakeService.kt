package com.glasshole.glassxe

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
import android.os.Build
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

        private const val LOOKDOWN_THRESHOLD = -25.0
        private const val LOOKUP_DELTA_DEG = 20.0
        private const val GESTURE_WINDOW_MS = 1500L
        private const val WAKE_COOLDOWN_MS = 3000L
        private const val WAKE_DURATION_MS = 4000L
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    private var lowestPitch: Double = 999.0
    private var lowestPitchTime: Long = 0L
    private var lastWakeTime: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
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

        val gx = gravity[0].toDouble()
        val gy = gravity[1].toDouble()
        val gz = gravity[2].toDouble()
        val pitchRad = Math.atan2(-gz, Math.sqrt(gx * gx + gy * gy))
        val pitch = Math.toDegrees(pitchRad)

        val now = SystemClock.uptimeMillis()

        if (pitch < lowestPitch || (now - lowestPitchTime) > GESTURE_WINDOW_MS) {
            lowestPitch = pitch
            lowestPitchTime = now
        }

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
            // isInteractive is API 20+; EE1 targets API 19 so fall back to the
            // deprecated isScreenOn (same semantics on 4.4.4 hardware).
            @Suppress("DEPRECATION")
            val alreadyOn = if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else pm.isScreenOn
            if (alreadyOn) return
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

    private fun createChannelIfNeeded() {
        // Notification channels are API 26+. Below that the channel id on
        // the Builder is ignored and the notification renders normally.
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Tilt to wake",
                NotificationManager.IMPORTANCE_MIN
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotif(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return b.setContentTitle("GlassHole")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
