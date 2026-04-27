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
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.glasshole.phone.model.GlassInfo
import com.glasshole.phone.plugins.device.DeviceActivity
import com.glasshole.phone.plugins.stream.PlaybackState
import com.glasshole.phone.plugins.stream.StreamPlugin
import com.glasshole.phone.service.BridgeService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.glasshole.phone.service.NotificationForwardingService
import com.glasshole.phone.service.PluginHostService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST = 1001
        private const val PREFS_NAME = "glasshole_prefs"
        private const val PREF_LAST_DEVICE = "last_device_address"
        private const val PREF_AUTO_CONNECT = "auto_connect_last"

        private val CORE_PLUGIN_PACKAGES = setOf(
            "com.glasshole.plugin.device.glass",
            "com.glasshole.plugin.gallery.glass"
        )
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
    private lateinit var openGalleryButton: Button
    private lateinit var openPluginsButton: Button
    private lateinit var openDeviceButton: Button
    private lateinit var openApkManagerButton: Button
    private lateinit var openDebugButton: Button
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
                runOnUiThread {
                    updateConnectionUI(connected)
                    refreshPluginCount()
                }
            }
            bridgeService?.onGlassInfo = { info ->
                runOnUiThread { updateGlassInfo(info) }
            }
            bridgeService?.onPackageList = { json ->
                runOnUiThread { handlePackageListForCount(json) }
            }
            // Kick an initial list request if the glass is already connected.
            if (bridgeService?.isConnected == true) {
                bridgeService?.requestPackageList()
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

            refreshPluginCount()
            // PluginHostService just instantiated all built-in plugins
            // (including StreamPlugin), so its companion `instance` is now
            // non-null. Re-run the Now Playing wiring — the first attempt
            // in onCreate ran before the host bound, so StreamPlugin.instance
            // was null and the callback never got registered.
            bindNowPlayingCard()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pluginHostService = null
            pluginHostBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        openGalleryButton.setOnClickListener {
            startActivity(Intent(this, com.glasshole.phone.plugins.gallery.GalleryActivity::class.java))
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
        // Re-install our onPackageList callback — ApkManager / Plugins screens
        // may have overwritten it while they were in front.
        bridgeService?.onPackageList = { json ->
            runOnUiThread { handlePackageListForCount(json) }
        }
        refreshPluginCount()
        // Same idempotent re-bind as in pluginHostConnection — covers the
        // case where another activity overwrote the callback or the
        // StreamPlugin instance arrived after onCreate.
        bindNowPlayingCard()
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
        loadPairedDevices()

        connectButton.setOnClickListener { toggleConnection() }
        notifAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        notifAppsButton.setOnClickListener {
            startActivity(Intent(this, NotificationAppsActivity::class.java))
        }
        openGalleryButton.setOnClickListener {
            startActivity(Intent(this, com.glasshole.phone.plugins.gallery.GalleryActivity::class.java))
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
        // Layout was re-inflated — re-bind the Now Playing card so the
        // StreamPlugin callback points at the freshly-inflated views.
        // Without this, fold→unfold (or vice versa) leaves the listener
        // pointing at detached views from the old layout and the card
        // never updates again until the activity is recreated.
        bindNowPlayingCard()
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
        openGalleryButton = findViewById(R.id.openGalleryButton)
        openPluginsButton = findViewById(R.id.openPluginsButton)
        openDeviceButton = findViewById(R.id.openDeviceButton)
        openApkManagerButton = findViewById(R.id.openApkManagerButton)
        openDebugButton = findViewById(R.id.openDebugButton)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        findViewById<TextView>(R.id.versionLabel)?.text = "v${BuildConfig.VERSION_NAME}"
        bindNowPlayingCard()
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
    //
    // Plugins live on the glass. We count them off the LIST_PACKAGES response
    // using the same filter as PluginsActivity: packages matching
    // com.glasshole.plugin.* minus the core set (device / stream / photo-sync
    // gallery). When the glass isn't connected or we haven't received a list
    // yet, the counter shows a neutral dash.

    private fun refreshPluginCount() {
        if (bridgeService?.isConnected == true) {
            bridgeService?.requestPackageList()
        } else {
            pluginCountText.text = "Plugins: —"
        }
    }

    private fun handlePackageListForCount(json: String) {
        var count = 0
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val pkg = arr.getJSONObject(i).optString("pkg")
                if (!pkg.startsWith("com.glasshole.plugin.")) continue
                if (pkg in CORE_PLUGIN_PACKAGES) continue
                count++
            }
        } catch (_: Exception) { /* leave count at 0 */ }
        pluginCountText.text = "Plugins: $count installed"
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
        StreamPlugin.instance?.onPlaybackState = null
        nowPlayingHideHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- Now Playing card ---

    private val nowPlayingHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Cached duration for the latest state, used to format SeekBar drags
     *  while the user is dragging (and we're not pulling fresh values). */
    @Volatile private var lastDurationMs: Long = 0L
    @Volatile private var seekBarTracking = false

    private fun bindNowPlayingCard() {
        val card = findViewById<MaterialCardView?>(R.id.nowPlayingCard) ?: return
        val title = findViewById<TextView>(R.id.nowPlayingTitle)
        val subtitle = findViewById<TextView>(R.id.nowPlayingSubtitle)
        val pos = findViewById<TextView>(R.id.nowPlayingPosition)
        val dur = findViewById<TextView>(R.id.nowPlayingDuration)
        val seek = findViewById<SeekBar>(R.id.nowPlayingSeek)
        val playPause = findViewById<MaterialButton>(R.id.nowPlayingPlayPause)
        val rewind = findViewById<MaterialButton>(R.id.nowPlayingRewind)
        val forward = findViewById<MaterialButton>(R.id.nowPlayingForward)
        val prev = findViewById<MaterialButton>(R.id.nowPlayingPrev)
        val next = findViewById<MaterialButton>(R.id.nowPlayingNext)
        val close = findViewById<MaterialButton>(R.id.nowPlayingClose)

        playPause.setOnClickListener {
            val plugin = StreamPlugin.instance ?: return@setOnClickListener
            if (plugin.lastState?.isPlaying == true) plugin.pause() else plugin.play()
        }
        rewind.setOnClickListener { StreamPlugin.instance?.seekRelative(-10_000L) }
        forward.setOnClickListener { StreamPlugin.instance?.seekRelative(10_000L) }
        prev.setOnClickListener { StreamPlugin.instance?.prev() }
        next.setOnClickListener { StreamPlugin.instance?.next() }
        close.setOnClickListener { StreamPlugin.instance?.stop() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar?) { seekBarTracking = true }
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && lastDurationMs > 0) {
                    val ms = (progress.toLong() * lastDurationMs / 1000L)
                    pos.text = formatDuration(ms)
                }
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekBarTracking = false
                if (lastDurationMs > 0) {
                    val ms = (sb!!.progress.toLong() * lastDurationMs / 1000L)
                    StreamPlugin.instance?.seekTo(ms)
                }
            }
        })

        // Render whatever the plugin already has (in case we missed earlier
        // ticks before the activity bound) and subscribe for updates.
        renderNowPlaying(StreamPlugin.instance?.lastState, card, title, subtitle,
            pos, dur, seek, playPause, rewind, forward, prev, next)
        StreamPlugin.instance?.onPlaybackState = { state ->
            runOnUiThread {
                renderNowPlaying(state, card, title, subtitle,
                    pos, dur, seek, playPause, rewind, forward, prev, next)
            }
        }
    }

    private fun renderNowPlaying(
        state: PlaybackState?,
        card: MaterialCardView,
        titleView: TextView,
        subtitle: TextView,
        pos: TextView,
        dur: TextView,
        seek: SeekBar,
        playPause: MaterialButton,
        rewind: MaterialButton,
        forward: MaterialButton,
        prev: MaterialButton,
        next: MaterialButton
    ) {
        nowPlayingHideHandler.removeCallbacksAndMessages(null)
        if (state == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        titleView.text = state.title.ifEmpty { "Streaming" }
        subtitle.text = when {
            state.isLive -> "Live"
            state.hasQueue -> "Track ${state.cursor + 1} of ${state.queueSize}"
            else -> if (state.isPlaying) "Playing" else "Paused"
        }

        lastDurationMs = state.durationMs
        if (!seekBarTracking) {
            if (state.durationMs > 0) {
                seek.progress = ((state.positionMs.toDouble() / state.durationMs) * 1000)
                    .toInt().coerceIn(0, 1000)
            } else {
                seek.progress = 0
            }
        }
        seek.isEnabled = state.canSeek
        pos.text = formatDuration(state.positionMs)
        dur.text = if (state.durationMs > 0) formatDuration(state.durationMs) else "—"

        val playIcon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        playPause.setIconResource(playIcon)

        rewind.isEnabled = state.canSeek
        forward.isEnabled = state.canSeek
        prev.visibility = if (state.hasQueue) View.VISIBLE else View.GONE
        next.visibility = if (state.hasQueue) View.VISIBLE else View.GONE

        // Auto-hide if the glass goes silent (e.g. we missed PLAYBACK_END
        // because BT dropped). The state tick is 3s, so 10s of no inbound
        // state = three missed ticks → safe to hide.
        nowPlayingHideHandler.postDelayed({
            card.visibility = View.GONE
        }, 10_000L)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
               else String.format("%d:%02d", m, s)
    }
}
