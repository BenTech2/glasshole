package com.glasshole.phone.plugins.camera2

import android.content.Context
import android.util.Log
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONObject

/**
 * Phone-side companion to the glass Camera2 plugin. Right now its
 * only job is to relay LED_STATUS replies to the Device-settings UI;
 * config / capture-trigger writes go through the existing
 * BridgeService.sendPluginMessage path (no separate plumbing needed).
 */
data class CameraLedStatus(
    val enabled: Boolean,
    val permissionGranted: Boolean,
    /** Whether the camera HAL advertises LED_TRANSMIT in
     *  CameraCharacteristics.LED_AVAILABLE_LEDS. False on stock
     *  Glass EE2 — the privacy LED is hard-wired through the HAL
     *  rather than exposed as a controllable Camera2 LED. */
    val supported: Boolean
)

class Camera2Plugin : PhonePlugin {

    companion object {
        private const val TAG = "Camera2Plugin"

        @Volatile var instance: Camera2Plugin? = null
            private set
    }

    override val pluginId: String = "camera2"

    private lateinit var sender: PluginSender

    /** Subscribed by DeviceActivity to drive the Camera-LED card. */
    var onLedStatus: ((CameraLedStatus) -> Unit)? = null
    @Volatile var latestLedStatus: CameraLedStatus? = null
        private set

    override fun onCreate(context: Context, sender: PluginSender) {
        this.sender = sender
        instance = this
    }

    override fun onDestroy() {
        instance = null
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "LED_STATUS" -> handleLedStatus(message.payload)
            else -> Log.d(TAG, "Unhandled type: ${message.type}")
        }
    }

    private fun handleLedStatus(payload: String) {
        try {
            val o = JSONObject(payload)
            val status = CameraLedStatus(
                enabled = o.optBoolean("enabled", false),
                permissionGranted = o.optBoolean("permission_granted", false),
                supported = o.optBoolean("supported", false)
            )
            latestLedStatus = status
            onLedStatus?.invoke(status)
        } catch (e: Exception) {
            Log.w(TAG, "Bad LED_STATUS payload: ${e.message}")
        }
    }

    fun setDisableLed(enabled: Boolean) {
        sender(PluginMessage("SET_DISABLE_LED", JSONObject().apply {
            put("enabled", enabled)
        }.toString()))
    }

    fun requestLedPerm() {
        sender(PluginMessage("REQUEST_LED_PERM", ""))
    }

    fun queryLedStatus() {
        sender(PluginMessage("GET_LED_STATUS", ""))
    }
}
