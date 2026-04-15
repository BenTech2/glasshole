package com.glasshole.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.glasshole.phone.model.GlassInfo
import com.glasshole.phone.plugin.PluginDiscovery
import com.glasshole.phone.plugins.device.DeviceActivity
import com.glasshole.phone.service.BridgeService
import com.glasshole.phone.service.NotificationForwardingService
import com.glasshole.phone.service.PluginHostService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST = 1001
        private const val PREFS_NAME = "glasshole_prefs"
        private const val PREF_LAST_DEVICE = "last_device_address"
        private const val PREF_AUTO_CONNECT = "auto_connect_last"
    }

    private lateinit var statusText: TextView
    private lateinit var notifStatusText: TextView
    private lateinit var batteryText: TextView
    private lateinit var glassModelText: TextView
    private lateinit var pluginCountText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var notifAccessButton: Button
    private lateinit var notifAppsButton: Button
    private lateinit var openPluginsButton: Button
    private lateinit var openDeviceButton: Button
    private lateinit var openApkManagerButton: Button
    private lateinit var openDebugButton: Button
    private lateinit var themeToggle: ImageButton
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false
    private var pluginHostService: PluginHostService? = null
    private var pluginHostBound = false
    @Volatile private var isConnected = false

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as BridgeService.LocalBinder).getService()
            bridgeBound = true

            bridgeService?.onLog = { msg -> runOnUiThread { log(msg) } }
            bridgeService?.onConnectionChanged = { connected ->
                isConnected = connected
                runOnUiThread { updateConnectionUI(connected) }
            }
            bridgeService?.onGlassInfo = { info ->
                runOnUiThread { updateGlassInfo(info) }
            }

            // Wire plugin router from host service
            if (pluginHostBound) {
                bridgeService?.pluginRouter = pluginHostService?.pluginRouter
                bridgeService?.let { bridge ->
                    pluginHostService?.onSendPluginMessage = { pluginId, type, payload ->
                        bridge.sendPluginMessage(pluginId, type, payload)
                    }
                    pluginHostService?.onIsGlassConnected = { bridge.isConnected }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bridgeBound = false
        }
    }

    private val pluginHostConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            pluginHostService = (binder as? PluginHostService.LocalBinder)?.getService()
            pluginHostBound = true

            // Wire to bridge if already bound
            bridgeService?.let { bridge ->
                pluginHostService?.let { host ->
                    bridge.pluginRouter = host.pluginRouter
                    host.onSendPluginMessage = { pluginId, type, payload ->
                        bridge.sendPluginMessage(pluginId, type, payload)
                    }
                    host.onIsGlassConnected = { bridge.isConnected }
                }
            }

            updatePluginCount()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pluginHostService = null
            pluginHostBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            statusText.text = "Bluetooth not available"
            connectButton.isEnabled = false
            return
        }

        connectButton.setOnClickListener { toggleConnection() }
        notifAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        notifAppsButton.setOnClickListener {
            startActivity(Intent(this, NotificationAppsActivity::class.java))
        }
        openPluginsButton.setOnClickListener {
            startActivity(Intent(this, PluginsActivity::class.java))
        }
        openDeviceButton.setOnClickListener {
            startActivity(Intent(this, DeviceActivity::class.java))
        }
        openApkManagerButton.setOnClickListener {
            startActivity(Intent(this, ApkManagerActivity::class.java))
        }
        openDebugButton.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
        themeToggle.setOnClickListener { toggleTheme() }

        updateThemeIcon()
        checkPermissionsAndLoadDevices()

        // Route AppLog.log(...) from plugins/services to the on-screen panel.
        AppLog.sink = { msg -> runOnUiThread { log(msg) } }

        // Start plugin host service
        val pluginIntent = Intent(this, PluginHostService::class.java)
        startService(pluginIntent)
        bindService(pluginIntent, pluginHostConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        updateNotifAccessStatus()
        updatePluginCount()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentLog = logText.text.toString()
        val currentStatus = statusText.text.toString()
        val statusColor = statusText.currentTextColor

        setContentView(R.layout.activity_main)
        bindViews()

        statusText.text = currentStatus
        statusText.setTextColor(statusColor)
        logText.text = currentLog
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        updateConnectionUI(isConnected)
        updateNotifAccessStatus()
        updateThemeIcon()
        loadPairedDevices()

        connectButton.setOnClickListener { toggleConnection() }
        notifAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        notifAppsButton.setOnClickListener {
            startActivity(Intent(this, NotificationAppsActivity::class.java))
        }
        openPluginsButton.setOnClickListener {
            startActivity(Intent(this, PluginsActivity::class.java))
        }
        openDeviceButton.setOnClickListener {
            startActivity(Intent(this, DeviceActivity::class.java))
        }
        openApkManagerButton.setOnClickListener {
            startActivity(Intent(this, ApkManagerActivity::class.java))
        }
        openDebugButton.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
        themeToggle.setOnClickListener { toggleTheme() }
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        notifStatusText = findViewById(R.id.notifStatusText)
        batteryText = findViewById(R.id.batteryText)
        glassModelText = findViewById(R.id.glassModelText)
        pluginCountText = findViewById(R.id.pluginCountText)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        connectButton = findViewById(R.id.connectButton)
        notifAccessButton = findViewById(R.id.notifAccessButton)
        notifAppsButton = findViewById(R.id.notifAppsButton)
        openPluginsButton = findViewById(R.id.openPluginsButton)
        openDeviceButton = findViewById(R.id.openDeviceButton)
        openApkManagerButton = findViewById(R.id.openApkManagerButton)
        openDebugButton = findViewById(R.id.openDebugButton)
        themeToggle = findViewById(R.id.themeToggle)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
    }

    // --- Theme ---

    private fun applySavedTheme() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun toggleTheme() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode", true)
        prefs.edit().putBoolean("dark_mode", !isDark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (!isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun updateThemeIcon() {
        val isDark = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("dark_mode", true)
        themeToggle.setImageResource(if (isDark) R.drawable.ic_sun else R.drawable.ic_moon)
    }

    // --- Notification access ---

    private fun updateNotifAccessStatus() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            notifStatusText.text = "Notification access: Enabled"
            notifStatusText.setTextColor(0xFF43A047.toInt())
            notifAccessButton.text = "Notification Access (enabled)"
        } else {
            notifStatusText.text = "Notification access: Disabled"
            notifStatusText.setTextColor(0xFFF44336.toInt())
            notifAccessButton.text = "Enable Notification Access"
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(
            ComponentName(this, NotificationForwardingService::class.java).flattenToString()
        )
    }

    // --- Glass info ---

    private fun updateGlassInfo(info: GlassInfo) {
        val batteryIcon = when {
            info.charging -> "⚡"
            info.battery > 50 -> "🔋"
            info.battery > 20 -> "🪫"
            else -> "🔴"
        }
        batteryText.text = if (info.battery >= 0) {
            "Battery: ${info.battery}% $batteryIcon${if (info.charging) " (charging)" else ""}"
        } else {
            "Battery: --"
        }
        glassModelText.text = if (info.model.isNotEmpty()) {
            "${info.model} (Android ${info.androidVersion})"
        } else {
            "Unknown Glass"
        }
    }

    // --- Plugins ---

    private fun updatePluginCount() {
        val plugins = PluginDiscovery.discoverPlugins(this)
        pluginCountText.text = "Plugins: ${plugins.size} installed"
    }

    // --- Permissions ---

    private fun checkPermissionsAndLoadDevices() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST)
        } else {
            loadPairedDevices()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) loadPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        pairedDevices = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        if (pairedDevices.isEmpty()) {
            log("No paired devices. Pair your Glass first.")
            connectButton.isEnabled = false
            return
        }
        val names = pairedDevices.map { "${it.name ?: "Unknown"} (${it.address})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = adapter
        log("Found ${pairedDevices.size} paired device(s)")

        // Pre-select the last-connected device if it's still paired, and
        // kick off an auto-reconnect once the BridgeService is ready.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastAddr = prefs.getString(PREF_LAST_DEVICE, null)
        if (!lastAddr.isNullOrEmpty()) {
            val idx = pairedDevices.indexOfFirst { it.address == lastAddr }
            if (idx >= 0) {
                deviceSpinner.setSelection(idx)
                if (prefs.getBoolean(PREF_AUTO_CONNECT, true) && !isConnected) {
                    log("Auto-reconnecting to ${pairedDevices[idx].name ?: lastAddr}")
                    deviceSpinner.post { if (!isConnected) connect() }
                }
            }
        }
    }

    // --- Connection ---

    private fun toggleConnection() {
        if (isConnected) disconnect() else connect()
    }

    private fun connect() {
        val idx = deviceSpinner.selectedItemPosition
        if (idx < 0 || idx >= pairedDevices.size) {
            log("No device selected")
            return
        }

        val device = pairedDevices[idx]
        // Persist the selection before attempting — even if the connection
        // fails, the app should remember the user's intent for next launch.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_LAST_DEVICE, device.address)
            .putBoolean(PREF_AUTO_CONNECT, true)
            .apply()

        connectButton.isEnabled = false
        statusText.text = "Connecting..."
        statusText.setTextColor(0xFFFF9800.toInt())

        val serviceIntent = Intent(this, BridgeService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, bridgeConnection, Context.BIND_AUTO_CREATE)

        connectButton.postDelayed({
            bridgeService?.connectBluetooth(device) ?: connectButton.postDelayed({
                bridgeService?.connectBluetooth(device) ?: log("Service not available")
            }, 1000)
        }, 500)
    }

    private fun disconnect() {
        bridgeService?.disconnectBluetooth()
        isConnected = false
        updateConnectionUI(false)
        // User explicitly disconnected — don't auto-reconnect on next launch.
        // Preserve the remembered address so the spinner still pre-selects it.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_AUTO_CONNECT, false)
            .apply()
    }

    private fun updateConnectionUI(connected: Boolean) {
        if (connected) {
            statusText.text = "Connected to Glass"
            statusText.setTextColor(0xFF43A047.toInt())
            connectButton.text = "Disconnect"
        } else {
            statusText.text = "Disconnected"
            statusText.setTextColor(0xFFF44336.toInt())
            connectButton.text = "Connect"
        }
        connectButton.isEnabled = true
    }

    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logText.append("[$ts] $msg\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        AppLog.sink = null
        if (bridgeBound) {
            bridgeService?.onLog = null
            bridgeService?.onConnectionChanged = null
            bridgeService?.onGlassInfo = null
            unbindService(bridgeConnection)
            bridgeBound = false
        }
        if (pluginHostBound) {
            unbindService(pluginHostConnection)
            pluginHostBound = false
        }
        super.onDestroy()
    }
}
