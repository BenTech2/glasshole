package com.glasshole.glassee2

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * In-process handler for "device" plugin messages. Lifted from the retired
 * plugin-device-glass APK and folded into the base app the same way the
 * gallery plugin was — runs in BluetoothListenerService's context, replies
 * via the injected [send] callback. Removes one launcher tile from the
 * stock Glass directory and one extra APK from the install set.
 */
class DeviceHandler(
    private val context: Context,
    private val send: (type: String, payload: String) -> Unit
) {

    companion object {
        private const val TAG = "DeviceHandler"
        private const val WAKE_DURATION_MS = 5_000L
    }

    private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val power by lazy { context.getSystemService(Context.POWER_SERVICE) as PowerManager }

    fun handleMessage(type: String, payload: String) {
        Log.d(TAG, "Message from phone: type=$type")
        when (type) {
            "GET_STATE" -> sendState()
            "SET_BRIGHTNESS" -> handleSetBrightness(payload)
            "SET_BRIGHTNESS_MODE" -> handleSetBrightnessMode(payload)
            "SET_VOLUME" -> handleSetVolume(payload)
            "SET_TIMEOUT" -> handleSetTimeout(payload)
            "WAKE" -> handleWake()
            "SET_TIME" -> handleSetTime(payload)
            "SET_TIMEZONE" -> handleSetTimezone(payload)
            "SLEEP_NOW" -> handleSleepNow()
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    }

    private fun sendState() {
        val json = JSONObject().apply {
            put("brightness", readBrightness())
            put("brightnessMax", 255)
            put("brightnessAuto", readBrightnessAuto())
            put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            put("volumeMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            put("timeout", readTimeout())
            put("battery", readBatteryPct())
            put("charging", isCharging())
            put("currentTimeMillis", System.currentTimeMillis())
            put("timezone", java.util.TimeZone.getDefault().id)
        }
        send("STATE", json.toString())
    }

    private fun handleSetBrightnessMode(payload: String) {
        try {
            val auto = JSONObject(payload).getBoolean("auto")
            val mode = if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                       else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode
            )
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_BRIGHTNESS_MODE failed: ${e.message}")
        }
    }

    private fun handleSetBrightness(payload: String) {
        try {
            val value = JSONObject(payload).getInt("value").coerceIn(0, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value
            )
            // Nudge the window system to apply the new brightness immediately.
            try {
                @Suppress("DEPRECATION")
                val wl = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    "GlassHoleDevice:BrightnessApply"
                )
                wl.acquire(200)
            } catch (_: Exception) {}
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_BRIGHTNESS failed: ${e.message}")
        }
    }

    private fun handleSetVolume(payload: String) {
        try {
            val value = JSONObject(payload).getInt("value")
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(0, max), 0)
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_VOLUME failed: ${e.message}")
        }
    }

    private fun handleSetTimeout(payload: String) {
        try {
            val value = JSONObject(payload).getInt("value").coerceIn(2_000, 30 * 60_000)
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, value
            )
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_TIMEOUT failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun handleWake() {
        try {
            val wl = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassHoleDevice:Wake"
            )
            wl.acquire(WAKE_DURATION_MS)
        } catch (e: Exception) {
            Log.e(TAG, "WAKE failed: ${e.message}")
        }
    }

    private fun handleSetTime(payload: String) {
        try {
            val targetMillis = JSONObject(payload).getLong("millis")
            val tz = JSONObject(payload).optString("tz", "")
            var success = false
            var method = ""
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setTime(targetMillis)
                if (tz.isNotEmpty()) am.setTimeZone(tz)
                success = true
                method = "AlarmManager"
            } catch (e: Exception) {
                Log.w(TAG, "AlarmManager.setTime blocked: ${e.message}")
            }
            if (!success && trySuSetTime(targetMillis, tz)) {
                success = true
                method = "su"
            }
            if (!success) {
                try {
                    Settings.System.putInt(context.contentResolver, Settings.System.AUTO_TIME, 1)
                    Settings.System.putInt(context.contentResolver, Settings.System.AUTO_TIME_ZONE, 1)
                    method = "auto_time (waiting for network)"
                } catch (_: Exception) {}
            }
            val reply = JSONObject().apply {
                put("success", success)
                put("method", method)
                put("now", System.currentTimeMillis())
            }
            send("TIME_SET_ACK", reply.toString())
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_TIME failed: ${e.message}")
        }
    }

    private fun handleSetTimezone(payload: String) {
        try {
            val tz = JSONObject(payload).optString("tz", "")
            if (tz.isEmpty()) return
            var success = false
            var method = ""
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setTimeZone(tz)
                success = true
                method = "AlarmManager"
            } catch (e: Exception) {
                Log.w(TAG, "AlarmManager.setTimeZone blocked: ${e.message}")
            }
            if (!success && trySuSetTimezone(tz)) {
                success = true
                method = "su"
            }
            val reply = JSONObject().apply {
                put("success", success)
                put("tz", tz)
                put("method", method)
                put("currentTz", java.util.TimeZone.getDefault().id)
                put("now", System.currentTimeMillis())
            }
            send("TIMEZONE_ACK", reply.toString())
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_TIMEZONE failed: ${e.message}")
        }
    }

    /**
     * Force the glass to standby. EE2 / API 28+ blocks PowerManager.goToSleep
     * via reflection, so we momentarily drop SCREEN_OFF_TIMEOUT to 1s, let
     * the system time the screen out, then restore the original. Needs
     * WRITE_SETTINGS, prompted on first run from HomeActivity.
     */
    private fun handleSleepNow() {
        try {
            @Suppress("DEPRECATION")
            val interactive = if (Build.VERSION.SDK_INT >= 20) power.isInteractive
                              else power.isScreenOn
            if (!interactive) return
            val current = Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000
            )
            if (current <= 1500) return
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 1000
            )
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Settings.System.putInt(
                        context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, current
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "SCREEN_OFF_TIMEOUT restore failed: ${e.message}")
                }
            }, 8_000L)
        } catch (e: Exception) {
            Log.e(TAG, "SLEEP_NOW failed: ${e.message}")
        }
    }

    private fun trySuSetTimezone(tz: String): Boolean {
        for (suPath in listOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su")) {
            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf(suPath, "-c", "setprop persist.sys.timezone $tz")
                )
                if (proc.waitFor() == 0) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun trySuSetTime(millis: Long, tz: String): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val fmt = java.text.SimpleDateFormat("yyyyMMdd.HHmmss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val dateStr = fmt.format(cal.time)
        val epochSec = millis / 1000
        val commandSets = listOf(
            "date -u $dateStr",
            "date -u @$epochSec",
            "date -s $dateStr",
            "toybox date -u @$epochSec"
        )
        for (suPath in listOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su")) {
            for (dateCmd in commandSets) {
                val script = buildString {
                    append(dateCmd)
                    if (tz.isNotEmpty()) append(" ; setprop persist.sys.timezone $tz")
                }
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf(suPath, "-c", script))
                    if (proc.waitFor() == 0) return true
                } catch (_: Exception) { break }
            }
        }
        return false
    }

    private fun readBrightness(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Exception) { -1 }

    private fun readBrightnessAuto(): Boolean = try {
        Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Exception) { false }

    private fun readTimeout(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    } catch (_: Exception) { -1 }

    private fun readBatteryPct(): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (_: Exception) { -1 }
    }

    private fun isCharging(): Boolean {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        } catch (_: Exception) { false }
    }
}
