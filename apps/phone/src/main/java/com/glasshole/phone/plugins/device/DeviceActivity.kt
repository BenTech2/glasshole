package com.glasshole.phone.plugins.device

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.glasshole.phone.plugins.camera2.Camera2Plugin
import com.glasshole.phone.plugins.camera2.CameraLedStatus
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

    private lateinit var backgroundFadeSeek: SeekBar
    private lateinit var backgroundFadeLabel: TextView
    private lateinit var uploadBackgroundButton: Button
    private lateinit var wallpaperScaleSpinner: Spinner

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
    private lateinit var invertNavSwitch: MaterialSwitch
    private lateinit var stayAwakeWhenChargingSwitch: MaterialSwitch
    private lateinit var wallpaperOnSettingsSwitch: MaterialSwitch
    private lateinit var wallpaperOnAppDrawerSwitch: MaterialSwitch
    private lateinit var showBatteryPercentSwitch: MaterialSwitch
    private lateinit var swapTopBarSwitch: MaterialSwitch
    private lateinit var notifSoundEnabledSwitch: MaterialSwitch
    private lateinit var notifSoundVolumeSeek: SeekBar
    private lateinit var notifSoundVolumeLabel: TextView
    private lateinit var stayAwakeWhenChargingSubtitle: TextView

    private lateinit var wakeButton: Button
    private lateinit var syncTimeButton: Button
    private lateinit var refreshButton: Button
    private lateinit var showWifiIpButton: Button
    private lateinit var glassWifiStatusText: TextView
    private lateinit var glassWifiSwitch: MaterialSwitch
    private lateinit var glassWifiScanButton: Button

    private lateinit var cameraLedSwitch: MaterialSwitch
    private lateinit var cameraLedStatusText: TextView
    private lateinit var cameraLedRequestPermButton: Button

    private lateinit var timezoneCurrentText: TextView
    private lateinit var timezoneSpinner: Spinner
    private lateinit var timezoneApplyButton: Button
    private val timezoneIds: List<String> = buildTimezoneList()

    // Screen timeout slider uses discrete steps (seconds)
    private val timeoutSteps = intArrayOf(2, 5, 10, 15, 30, 60, 120, 300, 600, 1200, 1800)

    // Notification on-screen timeout steps (seconds)
    private val notifTimeoutSteps = intArrayOf(3, 5, 8, 12, 15, 20, 30, 60)
    private val defaultNotifTimeoutMs = 12_000L

    /** Picker for the wallpaper-upload button — registers in onCreate
     *  per AndroidX requirements (before STARTED). */
    private val pickWallpaperLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handlePickedWallpaper(uri)
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)

        brightnessSeek = findViewById(R.id.brightnessSeek)
        brightnessLabel = findViewById(R.id.brightnessLabel)
        brightnessAutoSwitch = findViewById(R.id.brightnessAutoSwitch)
        backgroundFadeSeek = findViewById(R.id.backgroundFadeSeek)
        backgroundFadeLabel = findViewById(R.id.backgroundFadeLabel)
        uploadBackgroundButton = findViewById(R.id.uploadBackgroundButton)
        wallpaperScaleSpinner = findViewById(R.id.wallpaperScaleSpinner)
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
        invertNavSwitch = findViewById(R.id.invertNavSwitch)
        stayAwakeWhenChargingSwitch = findViewById(R.id.stayAwakeWhenChargingSwitch)
        wallpaperOnSettingsSwitch = findViewById(R.id.wallpaperOnSettingsSwitch)
        wallpaperOnAppDrawerSwitch = findViewById(R.id.wallpaperOnAppDrawerSwitch)
        showBatteryPercentSwitch = findViewById(R.id.showBatteryPercentSwitch)
        swapTopBarSwitch = findViewById(R.id.swapTopBarSwitch)
        notifSoundEnabledSwitch = findViewById(R.id.notifSoundEnabledSwitch)
        notifSoundVolumeSeek = findViewById(R.id.notifSoundVolumeSeek)
        notifSoundVolumeLabel = findViewById(R.id.notifSoundVolumeLabel)
        stayAwakeWhenChargingSubtitle = findViewById(R.id.stayAwakeWhenChargingSubtitle)
        wakeButton = findViewById(R.id.wakeButton)
        syncTimeButton = findViewById(R.id.syncTimeButton)
        refreshButton = findViewById(R.id.refreshButton)
        showWifiIpButton = findViewById(R.id.showWifiIpButton)
        glassWifiStatusText = findViewById(R.id.glassWifiStatusText)
        glassWifiSwitch = findViewById(R.id.glassWifiSwitch)
        glassWifiScanButton = findViewById(R.id.glassWifiScanButton)

        cameraLedSwitch = findViewById(R.id.cameraLedSwitch)
        cameraLedStatusText = findViewById(R.id.cameraLedStatusText)
        cameraLedRequestPermButton = findViewById(R.id.cameraLedRequestPermButton)

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

        showWifiIpButton.setOnClickListener {
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected")
                return@setOnClickListener
            }
            showWifiIpButton.isEnabled = false
            showWifiIpButton.text = "Asking glass…"
            bridge.queryWifiIp { ip, ssid, error ->
                runOnUiThread {
                    showWifiIpButton.isEnabled = true
                    showWifiIpButton.text = "Show glass Wi-Fi IP"
                    if (error != null) {
                        toast(error)
                        return@runOnUiThread
                    }
                    showWifiIpDialog(ip, ssid)
                }
            }
        }

        glassWifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            // Echo back fresh state once glass reports back. Swallow
            // local toggle changes during the round-trip so a bounce
            // doesn't double-fire.
            if (!bridge.setGlassWifiEnabled(isChecked)) {
                toast("Send failed")
            }
        }

        glassWifiScanButton.setOnClickListener {
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected")
                return@setOnClickListener
            }
            startActivity(android.content.Intent(this, GlassWifiPickerActivity::class.java))
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

        uploadBackgroundButton.setOnClickListener {
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected")
                return@setOnClickListener
            }
            pickWallpaperLauncher.launch("image/*")
        }

        // Background fade — drives the alpha (0..255) of the black
        // overlay on top of HomeActivity's wallpaper, so UI text
        // stays readable over an arbitrary user image. Routes to the
        // glass base plugin (not device plugin) since it's a base-app
        // UI feature, not a system Settings.System value.
        backgroundFadeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                backgroundFadeLabel.text = "Background fade: $progress / 255"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val value = sb?.progress ?: 0
                getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("background_fade", value).apply()
                val bridge = BridgeService.instance
                if (bridge == null || !bridge.isConnected) {
                    toast("Glass not connected — will apply on next connect")
                    return
                }
                val json = JSONObject().apply { put("value", value) }.toString()
                bridge.sendPluginMessage("base", "SET_BACKGROUND_FADE", json)
            }
        })

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

        val cachedFade = prefs.getInt("background_fade", 0).coerceIn(0, 255)
        backgroundFadeSeek.progress = cachedFade
        backgroundFadeLabel.text = "Background fade: $cachedFade / 255"

        // Wallpaper scale spinner — IDs ("fit"/"zoom"/"stretch") match
        // the glass-side BaseSettings.KEY_WALLPAPER_SCALE_MODE values,
        // shown to the user with friendlier labels.
        val scaleIds = listOf("fit", "zoom", "stretch")
        val scaleLabels = listOf("Center", "Zoom", "Fill screen")
        wallpaperScaleSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, scaleLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val cachedScale = prefs.getString("wallpaper_scale_mode", "fit") ?: "fit"
        wallpaperScaleSpinner.setSelection(
            scaleIds.indexOf(cachedScale).coerceAtLeast(0), false
        )
        wallpaperScaleSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?,
                    position: Int, id: Long
                ) {
                    val mode = scaleIds.getOrNull(position) ?: "fit"
                    if (mode == prefs.getString("wallpaper_scale_mode", "fit")) return
                    prefs.edit().putString("wallpaper_scale_mode", mode).apply()
                    val bridge = BridgeService.instance ?: return
                    if (!bridge.isConnected) return
                    val json = JSONObject().apply { put("mode", mode) }.toString()
                    bridge.sendPluginMessage("base", "SET_WALLPAPER_SCALE_MODE", json)
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }

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

        invertNavSwitch.isChecked = prefs.getBoolean("invert_nav", false)
        invertNavSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("invert_nav", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_INVERT_NAV", json)
            toast(if (sent) "Glass nav direction ${if (isChecked) "inverted" else "normal"}"
                  else "Send failed")
        }

        stayAwakeWhenChargingSwitch.isChecked = prefs.getBoolean("stay_awake_when_charging", false)
        stayAwakeWhenChargingSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("stay_awake_when_charging", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_STAY_AWAKE_WHEN_CHARGING", json)
            toast(if (sent) "Stay awake while charging ${if (isChecked) "enabled" else "disabled"}"
                  else "Send failed")
        }

        wallpaperOnSettingsSwitch.isChecked = prefs.getBoolean("wallpaper_on_settings", false)
        wallpaperOnSettingsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wallpaper_on_settings", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_WALLPAPER_ON_SETTINGS", json)
            toast(if (sent) "Wallpaper on Settings drawer ${if (isChecked) "on" else "off"}"
                  else "Send failed")
        }

        wallpaperOnAppDrawerSwitch.isChecked = prefs.getBoolean("wallpaper_on_app_drawer", false)
        wallpaperOnAppDrawerSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wallpaper_on_app_drawer", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_WALLPAPER_ON_APP_DRAWER", json)
            toast(if (sent) "Wallpaper on App drawer ${if (isChecked) "on" else "off"}"
                  else "Send failed")
        }

        // Home Screen — show battery percent next to the icon. Default on
        // so the time card reads like the stock launcher unless the user
        // explicitly hides it. Pref key matches the glass-side STATE echo.
        showBatteryPercentSwitch.isChecked = prefs.getBoolean("show_battery_percent", true)
        showBatteryPercentSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_battery_percent", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val sent = bridge.setShowBatteryPercent(isChecked)
            toast(if (sent) "Battery percent ${if (isChecked) "on" else "off"}"
                  else "Send failed")
        }

        // Home Screen — mirror battery vs connection icons. Default off
        // matches stock Glass (icons left, battery right).
        swapTopBarSwitch.isChecked = prefs.getBoolean("swap_top_bar", false)
        swapTopBarSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("swap_top_bar", isChecked).apply()
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val sent = bridge.setSwapTopBar(isChecked)
            toast(if (sent) "Top-bar sides ${if (isChecked) "swapped" else "default"}"
                  else "Send failed")
        }

        // Notification sound — master switch + 0..100 volume. The slider
        // stays usable when the switch is off so the user can pre-pick a
        // volume to apply once they flip it back on; we just dim it as a
        // visual hint that it isn't currently audible.
        val cachedNotifSoundEnabled = prefs.getBoolean("notif_sound_enabled", true)
        val cachedNotifSoundVolume = prefs.getInt("notif_sound_volume", 100).coerceIn(0, 100)
        notifSoundEnabledSwitch.isChecked = cachedNotifSoundEnabled
        notifSoundVolumeSeek.progress = cachedNotifSoundVolume
        notifSoundVolumeLabel.text = "Beep volume: $cachedNotifSoundVolume / 100"
        notifSoundVolumeSeek.alpha = if (cachedNotifSoundEnabled) 1f else 0.5f

        notifSoundEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_sound_enabled", isChecked).apply()
            notifSoundVolumeSeek.alpha = if (isChecked) 1f else 0.5f
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            val json = JSONObject().apply { put("enabled", isChecked) }.toString()
            val sent = bridge.sendPluginMessage("base", "SET_NOTIF_SOUND_ENABLED", json)
            toast(if (sent) "Notification beep ${if (isChecked) "on" else "off"}"
                  else "Send failed")
        }

        notifSoundVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                notifSoundVolumeLabel.text = "Beep volume: $progress / 100"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = seekBar?.progress ?: return
                prefs.edit().putInt("notif_sound_volume", value).apply()
                val bridge = BridgeService.instance
                if (bridge == null || !bridge.isConnected) return
                val json = JSONObject().apply { put("value", value) }.toString()
                bridge.sendPluginMessage("base", "SET_NOTIF_SOUND_VOLUME", json)
            }
        })

        // Connection-success notifications — local-only (BridgeService reads
        // the same pref on every connect). No round-trip to glass needed to
        // change the setting itself; the actual notifications are fired on
        // connect.
        connectNotifySwitch.isChecked = prefs.getBoolean("connect_notify_enabled", false)
        connectNotifySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("connect_notify_enabled", isChecked).apply()
            toast("Connection notifications ${if (isChecked) "enabled" else "disabled"}")
        }

        setupCameraLedControls()
    }

    private fun setupCameraLedControls() {
        // Cached UI state — show what we last heard from glass before
        // a fresh status arrives.
        Camera2Plugin.instance?.latestLedStatus?.let { renderLedStatus(it) }
            ?: run { cameraLedStatusText.text = "Status: --" }

        cameraLedSwitch.setOnCheckedChangeListener { _, isChecked ->
            val plugin = Camera2Plugin.instance
            if (plugin == null) {
                toast("GlassHole service not ready")
                return@setOnCheckedChangeListener
            }
            plugin.setDisableLed(isChecked)
            toast(
                if (isChecked) "LED disable requested — check status"
                else "LED disable cleared"
            )
        }

        cameraLedRequestPermButton.setOnClickListener {
            val plugin = Camera2Plugin.instance
            if (plugin == null) {
                toast("GlassHole service not ready")
                return@setOnClickListener
            }
            plugin.requestLedPerm()
            toast("Permission prompt fired on glass")
        }
    }

    private fun renderLedStatus(status: CameraLedStatus) {
        // Set switch without firing the listener.
        cameraLedSwitch.setOnCheckedChangeListener(null)
        cameraLedSwitch.isChecked = status.enabled
        cameraLedSwitch.setOnCheckedChangeListener { _, isChecked ->
            Camera2Plugin.instance?.setDisableLed(isChecked)
        }

        val parts = mutableListOf<String>()
        parts += if (status.permissionGranted) "permission granted"
                 else "permission denied"
        parts += if (status.supported) "HAL supports override"
                 else "HAL doesn't expose LED control"
        cameraLedStatusText.text = "Status: ${parts.joinToString(" · ")}"
        // Hide the request-perm button if we already hold it.
        cameraLedRequestPermButton.isEnabled = !status.permissionGranted
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

        // Camera LED status — subscribe + ask for a fresh value.
        Camera2Plugin.instance?.let { cam ->
            cam.onLedStatus = { st -> runOnUiThread { renderLedStatus(st) } }
            cam.queryLedStatus()
        }
    }

    override fun onStop() {
        super.onStop()
        DevicePlugin.instance?.onStateChanged = null
        DevicePlugin.instance?.onTimeSyncResult = null
        DevicePlugin.instance?.onTimezoneSetResult = null
        Camera2Plugin.instance?.onLedStatus = null
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

    /** Display the glass's Wi-Fi IP + SSID with a copy-to-clipboard for
     *  the matching `adb connect ip:5555` recovery command. Useful when
     *  the glass USB port is broken and you've already enabled adb
     *  tcpip mode from a previously-authorized machine. */
    override fun onResume() {
        super.onResume()
        // Refresh the glass Wi-Fi panel each time the user comes back
        // (e.g. after the picker connects to a new network).
        refreshGlassWifiStatus()
    }

    /** Asks the glass for the current Wi-Fi snapshot and pushes it
     *  into the status text + switch. Skips silently when the glass
     *  isn't reachable so this can be called any time without
     *  surfacing a "not connected" toast. */
    private fun refreshGlassWifiStatus() {
        val bridge = BridgeService.instance ?: return
        if (!bridge.isConnected) {
            glassWifiStatusText.text = "Glass not connected"
            return
        }
        glassWifiStatusText.text = "Asking glass…"
        bridge.queryGlassWifiState { state, err ->
            runOnUiThread {
                if (err != null || state == null) {
                    glassWifiStatusText.text = "Status: ${err ?: "?"}"
                    return@runOnUiThread
                }
                glassWifiStatusText.text = buildString {
                    append("Radio: ").append(if (state.enabled) "on" else "off")
                    if (state.connected) {
                        append("  •  ").append(state.ssid)
                        if (state.ip.isNotEmpty()) append("  •  ").append(state.ip)
                    } else if (state.enabled) {
                        append("  •  not connected")
                    }
                }
                // Update the switch without echoing back to the BT
                // handler — that would re-fire SET_WIFI_ENABLED in a
                // loop on every state refresh.
                glassWifiSwitch.setOnCheckedChangeListener(null)
                glassWifiSwitch.isChecked = state.enabled
                rebindGlassWifiSwitch()
            }
        }
    }

    private fun rebindGlassWifiSwitch() {
        glassWifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            val bridge = BridgeService.instance
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected — will apply on next connect")
                return@setOnCheckedChangeListener
            }
            if (!bridge.setGlassWifiEnabled(isChecked)) toast("Send failed")
        }
    }

    private fun showWifiIpDialog(ip: String, ssid: String) {
        if (ip.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Glass Wi-Fi")
                .setMessage("Glass isn't on Wi-Fi right now. Connect it to a network and try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val connectCmd = "adb connect $ip:5555"
        val ssidLine = if (ssid.isNotEmpty()) "SSID: $ssid\n" else ""
        val message = "${ssidLine}IP: $ip\n\n" +
            "If you've already enabled `adb tcpip 5555` on the glass " +
            "(e.g. from a previously-authorized machine), copy the " +
            "command below and run it on your dev machine.\n\n" +
            connectCmd
        android.app.AlertDialog.Builder(this)
            .setTitle("Glass Wi-Fi")
            .setMessage(message)
            .setPositiveButton("Copy adb connect") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb connect", connectCmd))
                toast("Copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Read the picked image, downscale + JPEG-encode it so we don't
     * push a 10 MB phone-camera photo over the wire, then hand it
     * to BridgeService.uploadWallpaper. Runs the IO + decode on a
     * worker thread and bounces the result toast back to the main
     * thread.
     */
    private fun handlePickedWallpaper(uri: android.net.Uri) {
        uploadBackgroundButton.isEnabled = false
        uploadBackgroundButton.text = "Uploading…"
        toast("Preparing wallpaper…")
        Thread {
            val ok = try {
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("Couldn't open image")
                // Downscale to keep the upload small. EE1's screen is
                // 640×360 — 1920px on the long edge is plenty of
                // headroom and lands the JPEG under ~500KB for most
                // photos.
                val scaled = downscaleToJpeg(raw, maxEdgePx = 1920, quality = 80)
                val filename = pickedFilename(uri) ?: "wallpaper.jpg"
                val bridge = BridgeService.instance
                if (bridge == null) {
                    runOnUiThread { toast("Glass not connected") }
                    false
                } else {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var success = false
                    var message = ""
                    bridge.uploadWallpaper(scaled, filename) { okIn, msg ->
                        success = okIn; message = msg; latch.countDown()
                    }
                    // 90s — wraps the glass-side 60s idle timeout +
                    // POST + write headroom. Server self-tears down
                    // on its own timeout regardless.
                    latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
                    runOnUiThread {
                        toast(if (message.isNotEmpty()) message else if (success) "Done" else "Timed out")
                    }
                    success
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Upload error: ${e.message}") }
                false
            }
            runOnUiThread {
                uploadBackgroundButton.isEnabled = true
                uploadBackgroundButton.text = "Upload wallpaper"
            }
            android.util.Log.i("DeviceActivity", "Wallpaper upload result: $ok")
        }.apply { isDaemon = true; name = "WallpaperPick"; start() }
    }

    private fun downscaleToJpeg(bytes: ByteArray, maxEdgePx: Int, quality: Int): ByteArray {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val srcMax = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (srcMax / sample > maxEdgePx * 2) sample *= 2
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sample
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return bytes // weird format — let glass try the raw bytes
        val finalBmp = if (maxOf(decoded.width, decoded.height) > maxEdgePx) {
            val scale = maxEdgePx.toFloat() / maxOf(decoded.width, decoded.height)
            val w = (decoded.width * scale).toInt()
            val h = (decoded.height * scale).toInt()
            android.graphics.Bitmap.createScaledBitmap(decoded, w, h, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else decoded
        val out = java.io.ByteArrayOutputStream()
        finalBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        finalBmp.recycle()
        return out.toByteArray()
    }

    private fun pickedFilename(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        } catch (_: Exception) { null }
    }
}
