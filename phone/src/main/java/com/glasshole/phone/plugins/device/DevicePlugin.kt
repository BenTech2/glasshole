package com.glasshole.phone.plugins.device

import android.content.Context
import android.util.Log
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONObject
import java.util.TimeZone

data class DeviceState(
    val brightness: Int,
    val brightnessMax: Int,
    val brightnessAuto: Boolean,
    val volume: Int,
    val volumeMax: Int,
    val timeoutMs: Int,
    val battery: Int,
    val charging: Boolean,
    val glassTimeMillis: Long
)

class DevicePlugin : PhonePlugin {

    companion object {
        private const val TAG = "DevicePlugin"

        @Volatile
        var instance: DevicePlugin? = null
            private set
    }

    override val pluginId: String = "device"

    private lateinit var sender: PluginSender

    @Volatile
    var latestState: DeviceState? = null
        private set

    // Activity subscribes for live updates
    var onStateChanged: ((DeviceState) -> Unit)? = null

    // Activity subscribes for time-sync result
    var onTimeSyncResult: ((success: Boolean, method: String) -> Unit)? = null

    override fun onCreate(context: Context, sender: PluginSender) {
        this.sender = sender
        instance = this
    }

    override fun onDestroy() {
        instance = null
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "STATE" -> handleState(message.payload)
            "TIME_SET_ACK" -> handleTimeSetAck(message.payload)
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    override fun onGlassConnectionChanged(connected: Boolean) {
        if (connected) {
            Log.i(TAG, "Glass connected — syncing time and fetching state")
            syncTime()
            requestState()
        }
    }

    private fun handleState(payload: String) {
        try {
            val json = JSONObject(payload)
            val state = DeviceState(
                brightness = json.optInt("brightness", -1),
                brightnessMax = json.optInt("brightnessMax", 255),
                brightnessAuto = json.optBoolean("brightnessAuto", false),
                volume = json.optInt("volume", 0),
                volumeMax = json.optInt("volumeMax", 15),
                timeoutMs = json.optInt("timeout", 60_000),
                battery = json.optInt("battery", -1),
                charging = json.optBoolean("charging", false),
                glassTimeMillis = json.optLong("currentTimeMillis", 0L)
            )
            latestState = state
            onStateChanged?.invoke(state)
        } catch (e: Exception) {
            Log.e(TAG, "Bad STATE payload: ${e.message}")
        }
    }

    private fun handleTimeSetAck(payload: String) {
        try {
            val json = JSONObject(payload)
            val success = json.optBoolean("success", false)
            val method = json.optString("method", "")
            Log.i(TAG, "Time sync ack: success=$success method=$method")
            onTimeSyncResult?.invoke(success, method)
        } catch (_: Exception) {}
    }

    fun requestState(): Boolean =
        sender(PluginMessage("GET_STATE", ""))

    fun setBrightness(value: Int): Boolean {
        val json = JSONObject().apply { put("value", value) }.toString()
        return sender(PluginMessage("SET_BRIGHTNESS", json))
    }

    fun setBrightnessAuto(auto: Boolean): Boolean {
        val json = JSONObject().apply { put("auto", auto) }.toString()
        return sender(PluginMessage("SET_BRIGHTNESS_MODE", json))
    }

    fun setVolume(value: Int): Boolean {
        val json = JSONObject().apply { put("value", value) }.toString()
        return sender(PluginMessage("SET_VOLUME", json))
    }

    fun setTimeout(millis: Int): Boolean {
        val json = JSONObject().apply { put("value", millis) }.toString()
        return sender(PluginMessage("SET_TIMEOUT", json))
    }

    fun wakeGlass(): Boolean =
        sender(PluginMessage("WAKE", ""))

    fun syncTime(): Boolean {
        val json = JSONObject().apply {
            put("millis", System.currentTimeMillis())
            put("tz", TimeZone.getDefault().id)
        }.toString()
        return sender(PluginMessage("SET_TIME", json))
    }
}
