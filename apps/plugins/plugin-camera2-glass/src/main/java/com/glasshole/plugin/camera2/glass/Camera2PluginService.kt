package com.glasshole.plugin.camera2.glass

import android.content.Intent
import android.util.Log
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
 */
class Camera2PluginService : GlassPluginService() {

    companion object {
        private const val TAG = "Camera2Plugin"
        const val PREFS_NAME = "camera2_settings"
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
            else -> Log.d(TAG, "From phone: ${message.type}")
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
