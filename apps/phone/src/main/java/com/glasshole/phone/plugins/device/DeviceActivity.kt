package com.glasshole.phone.plugins.device

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.glasshole.phone.service.BridgeService
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DeviceActivity : AppCompatActivity() {


    private lateinit var brightnessSeek: SeekBar
    private lateinit var brightnessLabel: TextView
    private lateinit var brightnessAutoSwitch: MaterialSwitch

    private lateinit var volumeSeek: SeekBar
    private lateinit var volumeLabel: TextView

    private lateinit var timeoutSeek: SeekBar
    private lateinit var timeoutLabel: TextView

    private lateinit var notifTimeoutSeek: SeekBar
    private lateinit var notifTimeoutLabel: TextView

    private lateinit var tiltWakeSwitch: MaterialSwitch
    private lateinit var autoStartSwitch: MaterialSwitch
    private lateinit var connectNotifySwitch: MaterialSwitch
    private lateinit var navKeepScreenOnSwitch: MaterialSwitch
    private lateinit var navWakeOnUpdateSwitch: MaterialSwitch
    private lateinit var wakeToTimeCardSwitch: MaterialSwitch

    private lateinit var wakeButton: Button
    private lateinit var syncTimeButton: Button
    private lateinit var refreshButton: Button

    private lateinit var timezoneCurrentText: TextView
    private lateinit var timezoneSpinner: Spinner
    private lateinit var timezoneApplyButton: Button
    private val timezoneIds: List<String> = buildTimezoneList()

    // Screen timeout slider uses discrete steps (seconds)
    private val timeoutSteps = intArrayOf(2, 5, 10, 15, 30, 60, 120, 300, 600, 1200, 1800)

    // Notification on-screen timeout steps (seconds)
    private val notifTimeoutSteps = intArrayOf(3, 5, 8, 12, 15, 20, 30, 60)
    private val defaultNotifTimeoutMs = 12_000L


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)

        brightnessSeek = findViewById(R.id.brightnessSeek)
        brightnessLabel = findViewById(R.id.brightnessLabel)
        brightnessAutoSwitch = findViewById(R.id.brightnessAutoSwitch)
        volumeSeek = findViewById(R.id.volumeSeek)
        volumeLabel = findViewById(R.id.volumeLabel)
        timeoutSeek = findViewById(R.id.timeoutSeek)
        timeoutLabel = findViewById(R.id.timeoutLabel)
        notifTimeoutSeek = findViewById(R.id.notifTimeoutSeek)
        notifTimeoutLabel = findViewById(R.id.notifTimeoutLabel)
        tiltWakeSwitch = findViewById(R.id.tiltWakeSwitch)
        autoStartSwitch = findViewById(R.id.autoStartSwitch)
        connectNotifySwitch = findViewById(R.id.connectNotifySwitch)
        navKeepScreenOnSwitch = findViewById(R.id.navKeepScreenOnSwitch)
        navWakeOnUpdateSwitch = findViewById(R.id.navWakeOnUpdateSwitch)
        wakeToTimeCardSwitch = findViewById(R.id.wakeToTimeCardSwitch)
        wakeButton = findViewById(R.id.wakeButton)
        syncTimeButton = findViewById(R.id.syncTimeButton)
        refreshButton = findViewById(R.id.refreshButton)

        timezoneCurrentText = findViewById(R.id.timezoneCurrentText)
        timezoneSpinner = findViewById(R.id.timezoneSpinner)
        timezoneApplyButton = findViewById(R.id.timezoneApplyButton)
        timezoneSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, timezoneIds
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // Default selection = phone's current timezone
        val phoneTz = TimeZone.getDefault().id
        timezoneIds.indexOf(phoneTz).takeIf { it >= 0 }
            ?.let { timezoneSpinner.setSelection(it) }
        timezoneApplyButton.setOnClickListener {
            val tz = timezoneIds.getOrNull(timezoneSpinner.selectedItemPosition) ?: return@setOnClickListener
            val sent = DevicePlugin.instance?.setTimezone(tz) ?: false
            toast(if (sent) "Timezone set: $tz" else "Glass not connected")
        }

        timeoutSeek.max = timeoutSteps.size - 1

        wakeButton.setOnClickListener {
            val sent = DevicePlugin.instance?.wakeGlass() ?: false
            toast(if (sent) "Wake sent" else "Glass not connected")
        }

        syncTimeButton.setOnClickListener {
            val sent = DevicePlugin.instance?.syncTime() ?: false
            toast(if (sent) "Time sync sent" else "Glass not connected")
        }

        refreshButton.setOnClickListener {
            val sent = DevicePlugin.instance?.requestState() ?: false
            if (!sent) toast("Glass not connected")
        }

        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                brightnessLabel.text = "Brightness: $progress / 255"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Moving the slider implicitly disables auto mode
                DevicePlugin.instance?.setBrightness(sb?.progress ?: 0)
            }
        })

        brightnessAutoSwitch.setOnCheckedChangeListener { _, isChecked ->
            DevicePlugin.instance?.setBrightnessAuto(isChecked)
            brightnessSeek.isEnabled = !isChecked
        }

        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val max = DevicePlugin.instance?.latestState?.volumeMax ?: 15
                volumeLabel.text = "Volume: $progress / $max"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                DevicePlugin.instance?.setVolume(sb?.progress ?: 0)
            }
        })

        timeoutSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = timeoutSteps[progress.coerceIn(0, timeoutSteps.size - 1)]
                timeoutLabel.text = "Screen timeout: ${formatTimeout(seconds)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val seconds = timeoutSteps[(sb?.progress ?: 0).coerceIn(0, timeoutSteps.size - 1)]
                DevicePlugin.instance?.setTimeout(seconds * 1000)
            }
        })

        // Notification timeout — local-only; read by NotificationForwardingService
        // on every forwarded notification, so no round-trip to the glass is needed.
        val notifPrefs = getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)
        notifTimeoutSeek.max = notifTimeoutSteps.size - 1
        val savedNotifMs = notifPrefs.getLong("notif_timeout_ms", defaultNotifTimeoutMs)
        val savedNotifSec = (savedNotifMs / 1000L).toInt()
        val initialNotifIdx = notifTimeoutSteps.indices.minByOrNull {
            kotlin.math.abs(notifTimeoutSteps[it] - savedNotifSec)
        } ?: 3
        notifTimeoutSeek.progress = initialNotifIdx
        notifTimeoutLabel.text =
            "Notification timeout: ${formatTimeout(notifTimeoutSteps[initialNotifIdx])}"

        notifTimeoutSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = notifTimeoutSteps[progress.coerceIn(0, notifTimeoutSteps.size - 1)]
                notifTimeoutLabel.text = "Notification timeout: ${formatTimeout(seconds)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val seconds = notifTimeoutSteps[
                    (sb?.progress ?: 0).coerceIn(0, notifTimeoutSteps.size - 1)
                ]
                notifPrefs.edit()
                    .putLong("notif_timeout_ms", seconds * 1000L)
                    .apply()
            }
        })

        // Base-app toggles — go to the glass base app (pluginId "base"),
        // not through the device plugin. Cache UI state locally so the
        // switch shows correctly before the glass reports back.
        val prefs = getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)

        tiltWakeSwitch.isChecked = prefs.getBoolean("tilt_wake_enabled", false)
        tiltWakeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("tilt_wake_enabled", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_TILT_WAKE", json)
            toast(if (sent) "Tilt to wake ${if (isChecked) "enabled" else "disabled"}"
                  else "Send failed")
        }

        autoStartSwitch.isChecked = prefs.getBoolean("auto_start_enabled", true)
        autoStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_start_enabled", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_AUTO_START", json)
            toast(if (sent) "Auto-start ${if (isChecked) "enabled" else "disabled"}"
                  else "Send failed")
        }

        navKeepScreenOnSwitch.isChecked = prefs.getBoolean("nav_keep_screen_on", false)
        navKeepScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("nav_keep_screen_on", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_NAV_KEEP_SCREEN_ON", json)
            toast(if (sent) "Keep screen on during nav ${if (isChecked) "enabled" else "disabled"}"
                  else "Send failed")
        }

        navWakeOnUpdateSwitch.isChecked = prefs.getBoolean("nav_wake_on_update", false)
        navWakeOnUpdateSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("nav_wake_on_update", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_NAV_WAKE_ON_UPDATE", json)
            toast(if (sent) "Wake screen on nav update ${if (isChecked) "enabled" else "disabled"}"
                  else "Send failed")
        }

        wakeToTimeCardSwitch.isChecked = prefs.getBoolean("wake_to_time_card", false)
        wakeToTimeCardSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wake_to_time_card", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_WAKE_TO_TIME_CARD", json)
            toast(if (sent) "Show time card on wake ${if (isChecked) "enabled" else "disabled"}"
                  else "Send failed")
        }

        // Connection-success notifications — local-only (BridgeService reads
        // the same pref on every connect). No round-trip to glass needed to
        // change the setting itself; the actual notifications are fired on
        // connect.
        connectNotifySwitch.isChecked = prefs.getBoolean("connect_notify_enabled", false)
        connectNotifySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("connect_notify_enabled", isChecked).apply()
            toast("Connection notifications ${if (isChecked) "enabled" else "disabled"}")
        }
    }

    override fun onStart() {
        super.onStart()
        val plugin = DevicePlugin.instance
        if (plugin == null) {
            Toast.makeText(this, "GlassHole service not ready", Toast.LENGTH_SHORT).show()
            return
        }
        plugin.onStateChanged = { state -> runOnUiThread { renderState(state) } }
        plugin.onTimeSyncResult = { success, method ->
            runOnUiThread {
                val msg = when {
                    success && method.isNotEmpty() -> "Time synced via $method"
                    success -> "Time synced"
                    else -> "Time sync unavailable (needs root or signed system app)"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        plugin.onTimezoneSetResult = { success, tz, method ->
            runOnUiThread {
                val msg = when {
                    success && method.isNotEmpty() -> "Timezone $tz set via $method"
                    success -> "Timezone $tz set"
                    else -> "Timezone change failed (needs root or signed system app)"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        plugin.latestState?.let(::renderState)
        plugin.requestState()
    }

    override fun onStop() {
        super.onStop()
        DevicePlugin.instance?.onStateChanged = null
        DevicePlugin.instance?.onTimeSyncResult = null
        DevicePlugin.instance?.onTimezoneSetResult = null
    }

    private fun renderState(state: DeviceState) {
        brightnessSeek.max = state.brightnessMax.coerceAtLeast(1)
        if (state.brightness in 0..state.brightnessMax) {
            brightnessSeek.progress = state.brightness
            brightnessLabel.text = "Brightness: ${state.brightness} / ${state.brightnessMax}"
        }
        brightnessSeek.isEnabled = !state.brightnessAuto
        // Avoid firing the listener while syncing state from glass
        brightnessAutoSwitch.setOnCheckedChangeListener(null)
        brightnessAutoSwitch.isChecked = state.brightnessAuto
        brightnessAutoSwitch.setOnCheckedChangeListener { _, isChecked ->
            DevicePlugin.instance?.setBrightnessAuto(isChecked)
            brightnessSeek.isEnabled = !isChecked
        }

        volumeSeek.max = state.volumeMax.coerceAtLeast(1)
        volumeSeek.progress = state.volume.coerceIn(0, state.volumeMax)
        volumeLabel.text = "Volume: ${state.volume} / ${state.volumeMax}"

        val currentSeconds = state.timeoutMs / 1000
        val closestIndex = timeoutSteps.indices.minByOrNull {
            kotlin.math.abs(timeoutSteps[it] - currentSeconds)
        } ?: 0
        timeoutSeek.progress = closestIndex
        timeoutLabel.text = "Screen timeout: ${formatTimeout(timeoutSteps[closestIndex])}"

        timezoneCurrentText.text = if (state.timezone.isNotEmpty()) {
            "Current on glass: ${state.timezone}"
        } else "Current on glass: --"
    }

    // IANA timezone IDs for the spinner. Filters out Java's 3-letter aliases
    // (EST, PST, …) and the SystemV legacy zones to keep the list readable,
    // always keeps UTC + GMT pinned at the top.
    private fun buildTimezoneList(): List<String> {
        val all = TimeZone.getAvailableIDs()
            .filter { it.contains('/') && !it.startsWith("SystemV/") }
            .sorted()
        return listOf("UTC", "GMT") + all
    }

    private fun formatTimeout(seconds: Int): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m".trim()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
