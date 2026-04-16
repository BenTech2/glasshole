package com.glasshole.plugin.device.glass

import android.app.AlarmManager
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import org.json.JSONObject

class DeviceGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "DeviceGlassPlugin"
        private const val WAKE_DURATION_MS = 5_000L
    }

    override val pluginId: String = "device"

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d(TAG, "Message from phone: type=${message.type}")
        when (message.type) {
            "GET_STATE" -> sendState()
            "SET_BRIGHTNESS" -> handleSetBrightness(message.payload)
            "SET_BRIGHTNESS_MODE" -> handleSetBrightnessMode(message.payload)
            "SET_VOLUME" -> handleSetVolume(message.payload)
            "SET_TIMEOUT" -> handleSetTimeout(message.payload)
            "WAKE" -> handleWake()
            "SET_TIME" -> handleSetTime(message.payload)
            "SET_TIMEZONE" -> handleSetTimezone(message.payload)
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
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
        sendToPhone(GlassPluginMessage("STATE", json.toString()))
    }

    private fun handleSetBrightnessMode(payload: String) {
        try {
            val auto = JSONObject(payload).getBoolean("auto")
            val mode = if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                       else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            Settings.System.putInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode
            )
            Log.i(TAG, "Brightness mode set to ${if (auto) "auto" else "manual"}")
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_BRIGHTNESS_MODE failed: ${e.message}")
        }
    }

    private fun handleSetBrightness(payload: String) {
        try {
            val value = JSONObject(payload).getInt("value").coerceIn(0, 255)
            // Setting a manual value implicitly flips to manual mode
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS, value
            )
            // Nudge the window system to apply the new brightness immediately
            // by touching a transient setting. On API 19 a short wake lock does it.
            try {
                val wl = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    "GlassHoleDevice:BrightnessApply"
                )
                wl.acquire(200)
            } catch (_: Exception) {}
            Log.i(TAG, "Brightness set to $value (mode=manual)")
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_BRIGHTNESS failed: ${e.message}")
        }
    }

    private fun handleSetVolume(payload: String) {
        try {
            val value = JSONObject(payload).getInt("value")
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                value.coerceIn(0, max),
                0
            )
            Log.i(TAG, "Volume set to $value / $max")
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_VOLUME failed: ${e.message}")
        }
    }

    private fun handleSetTimeout(payload: String) {
        try {
            val value = JSONObject(payload).getInt("value").coerceIn(5_000, 30 * 60_000)
            Settings.System.putInt(
                contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, value
            )
            Log.i(TAG, "Screen timeout set to ${value}ms")
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
            Log.i(TAG, "Wake lock acquired for ${WAKE_DURATION_MS}ms")
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

            // Path 1: AlarmManager.setTime (needs SET_TIME = signature|system — usually denied)
            try {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setTime(targetMillis)
                if (tz.isNotEmpty()) am.setTimeZone(tz)
                success = true
                method = "AlarmManager"
            } catch (e: Exception) {
                Log.w(TAG, "AlarmManager.setTime blocked: ${e.message}")
            }

            // Path 2: su shell fallback (rooted devices only)
            if (!success) {
                if (trySuSetTime(targetMillis, tz)) {
                    success = true
                    method = "su"
                }
            }

            // Path 3: enable Android automatic network time. Glass then NTP-syncs
            // itself on its own. Harmless if already enabled; no-op without network.
            if (!success) {
                try {
                    Settings.System.putInt(contentResolver, Settings.System.AUTO_TIME, 1)
                    Settings.System.putInt(contentResolver, Settings.System.AUTO_TIME_ZONE, 1)
                    method = "auto_time (waiting for network)"
                    Log.i(TAG, "Enabled Settings.System.AUTO_TIME fallback")
                } catch (e: Exception) {
                    Log.w(TAG, "AUTO_TIME fallback failed: ${e.message}")
                }
            }

            val reply = JSONObject().apply {
                put("success", success)
                put("method", method)
                put("now", System.currentTimeMillis())
            }
            sendToPhone(GlassPluginMessage("TIME_SET_ACK", reply.toString()))
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

            // Path 1: AlarmManager.setTimeZone (needs SET_TIME_ZONE = signature|privileged on API 23+)
            try {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setTimeZone(tz)
                success = true
                method = "AlarmManager"
            } catch (e: Exception) {
                Log.w(TAG, "AlarmManager.setTimeZone blocked: ${e.message}")
            }

            // Path 2: su shell — setprop persist.sys.timezone
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
            sendToPhone(GlassPluginMessage("TIMEZONE_ACK", reply.toString()))
            sendState()
        } catch (e: Exception) {
            Log.e(TAG, "SET_TIMEZONE failed: ${e.message}")
        }
    }

    private fun trySuSetTimezone(tz: String): Boolean {
        val suPaths = listOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su")
        val script = "setprop persist.sys.timezone $tz"
        for (suPath in suPaths) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(suPath, "-c", script))
                val exit = proc.waitFor()
                if (exit == 0) {
                    Log.i(TAG, "Timezone set via $suPath: $tz")
                    return true
                }
                Log.d(TAG, "$suPath $script exit=$exit")
            } catch (e: Exception) {
                Log.d(TAG, "$suPath not usable: ${e.message}")
            }
        }
        return false
    }

    private fun trySuSetTime(millis: Long, tz: String): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        val fmt = java.text.SimpleDateFormat("yyyyMMdd.HHmmss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val dateStr = fmt.format(cal.time)
        val epochSec = millis / 1000

        // Commands to try, in order of preference
        val commandSets = listOf(
            "date -u $dateStr",            // toolbox style
            "date -u @$epochSec",          // busybox/toybox style
            "date -s $dateStr",            // GNU-style local
            "toybox date -u @$epochSec"    // explicit toybox
        )

        // Binaries that might be su on this device
        val suPaths = listOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su")

        for (suPath in suPaths) {
            for (dateCmd in commandSets) {
                val script = buildString {
                    append(dateCmd)
                    if (tz.isNotEmpty()) append(" ; setprop persist.sys.timezone $tz")
                }
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf(suPath, "-c", script))
                    val exit = proc.waitFor()
                    if (exit == 0) {
                        Log.i(TAG, "Time set via $suPath: $dateCmd")
                        return true
                    }
                    Log.d(TAG, "$suPath $dateCmd exit=$exit")
                } catch (e: Exception) {
                    Log.d(TAG, "$suPath not usable: ${e.message}")
                    break  // this su path isn't valid, skip remaining commands for it
                }
            }
        }
        return false
    }

    private fun readBrightness(): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { -1 }
    }

    private fun readBrightnessAuto(): Boolean {
        return try {
            Settings.System.getInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Exception) { false }
    }

    private fun readTimeout(): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        } catch (_: Exception) { -1 }
    }

    private fun readBatteryPct(): Int {
        return try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val intent = registerReceiver(null, filter)
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (_: Exception) { -1 }
    }

    private fun isCharging(): Boolean {
        return try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val intent = registerReceiver(null, filter)
            (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        } catch (_: Exception) { false }
    }
}
