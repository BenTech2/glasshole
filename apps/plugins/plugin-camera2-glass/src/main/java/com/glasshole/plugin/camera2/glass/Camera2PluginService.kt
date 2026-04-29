package com.glasshole.plugin.camera2.glass

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler
import org.json.JSONObject

/**
 * Routes SCHEMA_REQ / CONFIG_READ / CONFIG_WRITE for the dynamic
 * settings UI. Values land in [PREFS_NAME] so [CameraActivity] can
 * read them directly.
 *
 * Also accepts remote-trigger messages from the phone — CAPTURE_STILL
 * fires a still capture, RECORD_VIDEO starts a timed recording. Both
 * launch [CameraActivity] in one-shot mode so the activity finishes
 * itself after saving and the user lands back where they were.
 *
 * The "disable camera privacy LED" feature also rides this AIDL channel:
 *   SET_DISABLE_LED  {enabled:bool}  → persist preference, ack via LED_STATUS
 *   REQUEST_LED_PERM ""              → launch CameraLedPermissionActivity
 *   GET_LED_STATUS   ""              → reply with LED_STATUS
 *   LED_STATUS       {enabled:bool, permission_granted:bool, supported:bool}
 *
 * `supported` reports whether the camera HAL advertises LED_AVAILABLE_LEDS
 * — on stock Glass EE2 this is false (the LED is hard-wired through the
 * camera HAL), so the toggle is a no-op there. The phone UI surfaces both
 * flags so users see exactly why the LED stays on.
 */
class Camera2PluginService : GlassPluginService() {

    companion object {
        private const val TAG = "Camera2Plugin"
        const val PREFS_NAME = "camera2_settings"
        const val KEY_DISABLE_LED = "disable_led"
        const val PERM_DISABLE_LED = "android.permission.CAMERA_DISABLE_TRANSMIT_LED"

        /**
         * Internal action that other components (e.g.
         * CameraLedPermissionActivity after a grant attempt) use to
         * ask the running plugin service to push a fresh LED_STATUS to
         * the phone. Service-internal — not exported.
         */
        const val ACTION_BROADCAST_LED_STATUS =
            "com.glasshole.plugin.camera2.glass.action.LED_STATUS_BROADCAST"

        fun requestLedStatusBroadcast(context: Context) {
            val intent = Intent(ACTION_BROADCAST_LED_STATUS).apply {
                setClass(context, Camera2PluginService::class.java)
            }
            try { context.startService(intent) }
            catch (_: Exception) {}
        }
    }

    override val pluginId: String = "camera2"

    private val configHandler by lazy {
        PluginConfigHandler(
            context = this,
            prefsName = PREFS_NAME,
            schemaResId = R.raw.plugin_schema,
            send = { type, payload ->
                sendToPhone(GlassPluginMessage(type, payload))
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_BROADCAST_LED_STATUS) {
            sendLedStatus()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        if (configHandler.handle(message)) return
        when (message.type) {
            "CAPTURE_STILL" -> {
                Log.i(TAG, "Phone-triggered still capture")
                launchCamera(Intent.ACTION_CAMERA_BUTTON, null)
            }
            "RECORD_VIDEO" -> {
                val durationMs = try {
                    JSONObject(message.payload.ifEmpty { "{}" }).optLong("duration_ms", 5_000L)
                } catch (_: Exception) { 5_000L }
                Log.i(TAG, "Phone-triggered timed recording: ${durationMs}ms")
                launchCamera(CameraActivity.ACTION_RECORD_TIMED, durationMs)
            }
            "SET_DISABLE_LED" -> handleSetDisableLed(message.payload)
            "REQUEST_LED_PERM" -> launchLedPermissionActivity()
            "GET_LED_STATUS" -> sendLedStatus()
            else -> Log.d(TAG, "From phone: ${message.type}")
        }
    }

    private fun handleSetDisableLed(payload: String) {
        val enabled = try {
            JSONObject(payload.ifEmpty { "{}" }).optBoolean("enabled", false)
        } catch (_: Exception) { false }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_DISABLE_LED, enabled).apply()
        Log.i(TAG, "LED disable preference: $enabled")
        sendLedStatus()
    }

    private fun sendLedStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_DISABLE_LED, false)
        val granted = ContextCompat.checkSelfPermission(this, PERM_DISABLE_LED) ==
            PackageManager.PERMISSION_GRANTED
        val supported = isLedTransmitAdvertised()
        val json = JSONObject().apply {
            put("enabled", enabled)
            put("permission_granted", granted)
            put("supported", supported)
        }.toString()
        sendToPhone(GlassPluginMessage("LED_STATUS", json))
    }

    /**
     * True if the camera HAL advertises LED_TRANSMIT in
     * CameraCharacteristics.LED_AVAILABLE_LEDS. On stock EE2 this is
     * always false (the privacy LED isn't exposed as a controllable
     * camera LED). Used by the phone UI to set expectations honestly.
     */
    private fun isLedTransmitAdvertised(): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val charKeyClass = Class.forName("android.hardware.camera2.CameraCharacteristics")
            val ledAvailableField = charKeyClass.getDeclaredField("LED_AVAILABLE_LEDS")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val key = ledAvailableField.get(null)
                as android.hardware.camera2.CameraCharacteristics.Key<ByteArray>
            for (id in cm.cameraIdList) {
                val leds = cm.getCameraCharacteristics(id).get(key) ?: continue
                // value 0 == LED_TRANSMIT in AOSP enums
                if (leds.any { it.toInt() == 0 }) return true
            }
            false
        } catch (e: Exception) {
            Log.d(TAG, "isLedTransmitAdvertised: ${e.message}")
            false
        }
    }

    private fun launchLedPermissionActivity() {
        val intent = Intent(this, CameraLedPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) }
        catch (e: Exception) {
            Log.w(TAG, "Failed to launch LED permission activity: ${e.message}")
        }
    }

    private fun launchCamera(action: String, durationMs: Long?) {
        val intent = Intent(action).apply {
            setPackage(packageName)
            setClass(this@Camera2PluginService, CameraActivity::class.java)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            if (durationMs != null) putExtra(CameraActivity.EXTRA_DURATION_MS, durationMs)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "launchCamera($action) failed: ${e.message}")
        }
    }
}
