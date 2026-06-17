package com.glasshole.glassee1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginConstants
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.IGlassPluginCallback
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothListenerService : Service() {

    companion object {
        private const val TAG = "GlassHoleBT"
        /**
         * Plugin IDs the base app owns via the Home card surface. External
         * plugin APKs that claim these IDs are ignored — we route messages
         * directly to HomeActivity instead.
         */
        private val HOME_OWNED_PLUGIN_IDS = setOf("media", "nav")
        /**
         * Plugin IDs handled by a base-app service helper (no Home card, no
         * external APK). Lives on the BT thread; replies via sendPluginMessage.
         */
        private val BASE_SERVICE_PLUGIN_IDS = setOf("gallery", "device")
        val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val SERVICE_NAME = "GlassHole"
        private const val FOREGROUND_ID = 1
        private const val CHANNEL_ID = "glasshole_bt"

        @Volatile var instance: BluetoothListenerService? = null
    }

    interface MessageListener {
        fun onMessageReceived(message: String)
        fun onConnectionStateChanged(connected: Boolean)
    }

    private val binder = LocalBinder()
    var messageListener: MessageListener? = null

    val isPhoneConnected: Boolean
        get() = clientSocket?.isConnected == true && outputStream != null
    var lastMessage: String = ""
        private set

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    // Serializes every write to outputStream. Without this, the BT
    // reader thread (PONG), the main thread (PluginMessageReceiver →
    // sendPluginMessage), AIDL plugin callbacks, and worker threads can
    // all hit the socket simultaneously and interleave bytes mid-frame —
    // the phone parses garbage and tears the link.
    private val writeLock = Any()
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Plugin callbacks (AIDL) registered via PluginHostService or direct bind.
    private val pluginCallbacks = ConcurrentHashMap<String, IGlassPluginCallback>()

    // Base-app passive services (formerly separate plugin APKs).
    private val galleryHandler: GalleryHandler by lazy {
        GalleryHandler(this) { type, payload ->
            sendPluginMessage("gallery", type, payload)
        }
    }
    private val deviceHandler: DeviceHandler by lazy {
        DeviceHandler(this) { type, payload ->
            sendPluginMessage("device", type, payload)
        }
    }

    // Debug live-stream camera feature. Lazily-initialised so we don't
    // open a socket / camera unless the phone actually asks for a
    // stream. EE1 has no MediaProjection (API 19), so screen mirror
    // does not exist on this edition.
    private val cameraLiveSession by lazy {
        // EE1 sensor is mounted 90° off the display — rotate at the
        // source so the phone viewer always shows an upright frame.
        com.glasshole.glass.sdk.CameraLiveSession(this, rotationDegrees = 90)
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothListenerService = this@BluetoothListenerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true

        createChannelIfNeeded()
        startForeground(FOREGROUND_ID, buildForegroundNotif())

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassHole::BT")
        wakeLock?.acquire()

        // Self-heal pathologically short SCREEN_OFF_TIMEOUT. handleSleepNow
        // drops it to 1000ms to force-sleep on swipe-down then restores 8s
        // later; if our process gets killed inside that window (e.g. a
        // `pm install` of the launcher) the restore never runs and the
        // setting is stuck at 1000ms forever. Anything below 2s on startup
        // is the orphaned-override value (phone-side slider min is 2s).
        try {
            val cur = android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1
            )
            if (cur in 0..1_999) {
                android.provider.Settings.System.putInt(
                    contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 60_000
                )
                Log.w(TAG, "SCREEN_OFF_TIMEOUT was $cur — reset to 60000")
            }
        } catch (_: Exception) {}

        // Let the manifest-declared PluginMessageReceiver forward plugin
        // broadcasts to us even when this service wasn't the broadcast target.
        PluginMessageReceiver.btService = this

        // Bind PluginHostService so it runs discoverAndBindPlugins — which
        // binds every installed glasshole plugin service via AIDL so they
        // register their callbacks with this service. Without this kick,
        // plugin services stay dormant after a reinstall or reboot.
        startPluginHost()

        // Launcher flavor: bypass HOME chooser/resolver by re-launching
        // HomeActivity ourselves on every screen-on. Android 4.4 on EE1
        // ignores third-party HOME-filter priority over the system-marked
        // nowtown home, so wake-from-sleep would otherwise dump us back to
        // the stock launcher. The launch lands on the existing singleTask
        // HomeActivity instance.
        if (BuildConfig.FLAVOR == "launcher") {
            try {
                registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            } catch (e: Exception) {
                Log.w(TAG, "screenOnReceiver register failed: ${e.message}")
            }
        }

        // Re-assert the user's stay-awake-while-charging preference —
        // see EE2 for the rationale.
        val basePrefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
        applyStayAwakeWhenCharging(
            basePrefs.getBoolean(BaseSettings.KEY_STAY_AWAKE_WHEN_CHARGING, false)
        )
        try {
            registerReceiver(powerStateReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
        } catch (e: Exception) {
            Log.w(TAG, "powerStateReceiver register failed: ${e.message}")
        }

        // React the moment the BT adapter actually flips on — clears
        // our retry-backoff so the listen thread can stop sleeping and
        // immediately re-open the server socket, instead of waiting
        // out the remainder of the current backoff window.
        try {
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
        } catch (e: Exception) {
            Log.w(TAG, "bluetoothStateReceiver register failed: ${e.message}")
        }

        startListening()
    }

    // Set whenever this service is about to launch (or route to something
    // that will launch) a foreground activity that wakes the screen —
    // stream's PLAY_URL → PlayerActivity, and notification arrivals →
    // NotificationDisplayActivity. The SCREEN_ON receiver checks this so
    // it doesn't race those launches with our own HomeActivity relaunch.
    @Volatile private var lastForegroundLaunchMs: Long = 0L
    private val FOREGROUND_LAUNCH_QUIET_MS = 4_000L

    private val screenOnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            val sinceLaunch = android.os.SystemClock.elapsedRealtime() - lastForegroundLaunchMs
            if (sinceLaunch < FOREGROUND_LAUNCH_QUIET_MS) {
                Log.i(TAG, "SCREEN_ON: skipping HomeActivity (foreground launch ${sinceLaunch}ms ago)")
                return
            }
            try {
                startActivity(
                    Intent(this@BluetoothListenerService,
                        com.glasshole.glassee1.home.HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Launch HomeActivity on SCREEN_ON failed: ${e.message}")
            }
        }
    }

    private var pluginHostBound = false
    private val pluginHostConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            pluginHostBound = true
            Log.i(TAG, "PluginHostService bound — discovery will run")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            pluginHostBound = false
        }
    }

    private fun startPluginHost() {
        try {
            bindService(
                Intent(this, PluginHostService::class.java),
                pluginHostConnection,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind PluginHostService: ${e.message}")
        }
    }

    override fun onDestroy() {
        running = false
        instance = null
        PluginMessageReceiver.btService = null
        if (pluginHostBound) {
            try { unbindService(pluginHostConnection) } catch (_: Exception) {}
            pluginHostBound = false
        }
        if (BuildConfig.FLAVOR == "launcher") {
            try { unregisterReceiver(screenOnReceiver) } catch (_: Exception) {}
        }
        closeAll()
        wakeLock?.release()
        wakeLock = null
        try { unregisterReceiver(powerStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) {}
        try { if (stayAwakeWakeLock.isHeld) stayAwakeWakeLock.release() } catch (_: Exception) {}
        stopForeground(true)
        super.onDestroy()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "GlassHole Bluetooth",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun buildForegroundNotif(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return b.setContentTitle("GlassHole")
            .setContentText("Listening for phone connection")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — service continues running")
        val restartIntent = Intent(this, BluetoothListenerService::class.java)
        // startForegroundService is API 26+; EE1 targets API 19, fall back
        // to plain startService there.
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun closeAll() {
        try { outputStream?.close() } catch (_: IOException) {}
        try { clientSocket?.close() } catch (_: IOException) {}
        try { serverSocket?.close() } catch (_: IOException) {}
        outputStream = null
        clientSocket = null
        serverSocket = null
        try { cameraLiveSession.stop() } catch (_: Exception) {}
    }

    // --- Outbound writer ---

    /**
     * Sends one already-newline-terminated frame to the phone, holding
     * [writeLock] for the duration of the write+flush so concurrent
     * callers can't interleave bytes on the wire.
     */
    private fun writeRaw(line: String): Boolean {
        return synchronized(writeLock) {
            val os = outputStream ?: return@synchronized false
            try {
                os.write(line.toByteArray(Charsets.UTF_8))
                os.flush()
                true
            } catch (e: IOException) {
                Log.e(TAG, "BT write failed: ${e.message}")
                false
            }
        }
    }

    // --- Live stream debug feature ---

    private fun sendLine(prefix: String, value: String) {
        writeRaw("$prefix:$value\n")
    }

    private fun handleLiveCamStart() {
        Thread {
            when (val st = cameraLiveSession.start()) {
                is com.glasshole.glass.sdk.CameraLiveSession.Status.Started -> {
                    sendLine("LIVE_CAM_URL", st.url)
                    Log.i(TAG, "LIVE_CAM_START → ${st.url}")
                }
                com.glasshole.glass.sdk.CameraLiveSession.Status.NoWifi ->
                    sendLine("LIVE_CAM_ERR", "no_wifi")
                com.glasshole.glass.sdk.CameraLiveSession.Status.CameraFailed ->
                    sendLine("LIVE_CAM_ERR", "camera_busy")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleLiveCamStop() {
        cameraLiveSession.stop()
        Log.i(TAG, "LIVE_CAM_STOP")
    }

    // --- Plugin registration (AIDL) ---

    fun registerPlugin(pluginId: String, callback: IGlassPluginCallback) {
        pluginCallbacks[pluginId] = callback
        Log.i(TAG, "Glass plugin registered: $pluginId")
    }

    fun unregisterPlugin(pluginId: String) {
        pluginCallbacks.remove(pluginId)
        Log.i(TAG, "Glass plugin unregistered: $pluginId")
    }

    // --- Send methods ---

    fun sendReply(message: String): Boolean {
        val escaped = message.replace("\\", "\\\\").replace("\n", "\\n")
        return writeRaw("REPLY:$escaped\n")
    }

    fun sendPluginMessage(pluginId: String, type: String, payload: String): Boolean {
        val escaped = payload.replace("\\", "\\\\").replace("\n", "\\n")
        return writeRaw("PLUGIN:$pluginId:$type:$escaped\n")
    }

    fun sendNotifAction(notifKey: String, actionId: String, replyText: String? = null): Boolean {
        val obj = JSONObject().apply {
            put("key", notifKey)
            put("id", actionId)
            if (replyText != null) put("text", replyText)
        }
        val escaped = obj.toString().replace("\\", "\\\\").replace("\n", "\\n")
        val ok = writeRaw("NOTIF_ACTION:$escaped\n")
        if (ok) Log.i(TAG, "NOTIF_ACTION sent: $actionId")
        return ok
    }

    fun sendNotifDismiss(notifKey: String): Boolean {
        val escaped = notifKey.replace("\\", "\\\\").replace("\n", "\\n")
        val ok = writeRaw("NOTIF_DISMISS:$escaped\n")
        if (ok) Log.i(TAG, "NOTIF_DISMISS sent: $notifKey")
        return ok
    }

    fun playStreamLocally(url: String) {
        val payload = JSONObject().apply { put("url", url) }.toString()
        routeToPlugin("stream", "PLAY_URL", payload)
    }

    /**
     * Handle settings owned by the base app itself (tilt-to-wake, nav keep-
     * screen-on, etc.). These don't route through an external plugin APK so
     * they work on a stock glasshole install.
     */
    private fun handleBaseMessage(type: String, payload: String) {
        when (type) {
            "SET_TILT_WAKE" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", false)
                } catch (_: Exception) { false }
                val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                prefs.edit().putBoolean(BaseSettings.KEY_TILT_WAKE, enabled).apply()
                val svc = Intent(this, TiltWakeService::class.java)
                try {
                    if (enabled) {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc)
                        else startService(svc)
                    } else {
                        stopService(svc)
                    }
                    Log.i(TAG, "Tilt wake ${if (enabled) "enabled" else "disabled"}")
                } catch (e: Exception) {
                    Log.e(TAG, "Tilt wake toggle failed: ${e.message}")
                }
                sendBaseStateToPhone()
            }
            "SET_AUTO_START" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", true)
                } catch (_: Exception) { true }
                val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                prefs.edit().putBoolean(BaseSettings.KEY_AUTO_START, enabled).apply()
                Log.i(TAG, "Auto-start ${if (enabled) "enabled" else "disabled"}")
                sendBaseStateToPhone()
            }
            "SET_NAV_KEEP_SCREEN_ON" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", false)
                } catch (_: Exception) { false }
                val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                prefs.edit().putBoolean(BaseSettings.KEY_NAV_KEEP_SCREEN_ON, enabled).apply()
                Log.i(TAG, "Nav keep-screen-on ${if (enabled) "enabled" else "disabled"}")
                sendBaseStateToPhone()
            }
            "SET_NAV_WAKE_ON_UPDATE" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", false)
                } catch (_: Exception) { false }
                val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                prefs.edit().putBoolean(BaseSettings.KEY_NAV_WAKE_ON_UPDATE, enabled).apply()
                Log.i(TAG, "Nav wake-on-update ${if (enabled) "enabled" else "disabled"}")
                sendBaseStateToPhone()
            }
            "SET_WAKE_TO_TIME_CARD" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", false)
                } catch (_: Exception) { false }
                val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                prefs.edit().putBoolean(BaseSettings.KEY_WAKE_TO_TIME_CARD, enabled).apply()
                Log.i(TAG, "Wake-to-time-card ${if (enabled) "enabled" else "disabled"}")
                sendBaseStateToPhone()
            }
            "SET_INVERT_NAV" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", false)
                } catch (_: Exception) { false }
                val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                prefs.edit().putBoolean(BaseSettings.KEY_INVERT_NAV, enabled).apply()
                Log.i(TAG, "Invert nav ${if (enabled) "enabled" else "disabled"}")
                sendBaseStateToPhone()
            }
            "SET_STAY_AWAKE_WHEN_CHARGING" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", false)
                } catch (_: Exception) { false }
                val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                prefs.edit().putBoolean(BaseSettings.KEY_STAY_AWAKE_WHEN_CHARGING, enabled).apply()
                applyStayAwakeWhenCharging(enabled)
                sendBaseStateToPhone()
            }
            "SET_BACKGROUND_FADE" -> {
                val value = try {
                    JSONObject(payload).optInt("value", 0).coerceIn(0, 255)
                } catch (_: Exception) { 0 }
                getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                    .edit().putInt(BaseSettings.KEY_BACKGROUND_FADE, value).apply()
                Log.i(TAG, "Background fade=$value")
                sendBaseStateToPhone()
            }
            "SET_WALLPAPER_ON_SETTINGS" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", false)
                } catch (_: Exception) { false }
                getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(BaseSettings.KEY_WALLPAPER_ON_SETTINGS, enabled).apply()
                Log.i(TAG, "Wallpaper on Settings drawer=$enabled")
                sendBaseStateToPhone()
            }
            "SET_WALLPAPER_ON_APP_DRAWER" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", false)
                } catch (_: Exception) { false }
                getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(BaseSettings.KEY_WALLPAPER_ON_APP_DRAWER, enabled).apply()
                Log.i(TAG, "Wallpaper on App drawer=$enabled")
                sendBaseStateToPhone()
            }
            "SET_NOTIF_SOUND_ENABLED" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", true)
                } catch (_: Exception) { true }
                getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(BaseSettings.KEY_NOTIF_SOUND_ENABLED, enabled).apply()
                Log.i(TAG, "Notification sound=$enabled")
                sendBaseStateToPhone()
            }
            "SET_NOTIF_SOUND_VOLUME" -> {
                val value = try {
                    JSONObject(payload).optInt("value", 100).coerceIn(0, 100)
                } catch (_: Exception) { 100 }
                getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                    .edit().putInt(BaseSettings.KEY_NOTIF_SOUND_VOLUME, value).apply()
                Log.i(TAG, "Notification volume=$value")
                sendBaseStateToPhone()
            }
            "BG_UPLOAD_REQ" -> handleBgUploadReq(payload)
            "LAUNCH_PACKAGE" -> handleLaunchPackage(payload)
            "GET_STATE" -> sendBaseStateToPhone()
            "SHOW_CONNECT_NOTIF" -> showConnectToast()
            "GET_WIFI_IP" -> sendWifiIpToPhone()
            else -> Log.d(TAG, "Unknown base message: $type")
        }
    }

    private fun maybeWakeForNavUpdate() {
        val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(BaseSettings.KEY_NAV_WAKE_ON_UPDATE, false)) {
            Log.d(TAG, "Nav wake: skipped (toggle off)")
            return
        }
        Log.i(TAG, "Nav wake: firing wakelock")
        @Suppress("DEPRECATION")
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            // FULL_WAKE_LOCK is deprecated but more reliable than
            // SCREEN_BRIGHT on KitKat-era Glass firmwares — some OEM
            // builds silently no-op the lighter lock. ON_AFTER_RELEASE
            // keeps the display lit briefly after the 3 s timeout so
            // HomeActivity has time to take over with FLAG_KEEP_SCREEN_ON
            // (when the user also has that toggle on).
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "GlassHole:NavWake"
            )
            wl.acquire(3_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Nav wake lock failed: ${e.message}")
        }
    }

    /** Reply with the current wlan0 IP + SSID so the phone can show
     *  the user an `adb connect ip:5555` recovery affordance when USB
     *  isn't working. Both fields may be empty when Wi-Fi is off. */
    private fun sendWifiIpToPhone() {
        val ip: String = try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val raw = wifi.connectionInfo?.ipAddress ?: 0
            if (raw == 0) "" else android.text.format.Formatter.formatIpAddress(raw)
        } catch (_: Exception) { "" }
        val ssid: String = try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifi.connectionInfo?.ssid.orEmpty().trim('"')
        } catch (_: Exception) { "" }
        val json = JSONObject().apply {
            put("ip", ip)
            put("ssid", ssid)
        }.toString()
        sendPluginMessage("base", "WIFI_IP", json)
    }

    private fun showConnectToast() {
        @Suppress("DEPRECATION")
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassHole:ConnectToast"
            )
            wl.acquire(500L)
        } catch (e: Exception) {
            Log.w(TAG, "Connect wake lock failed: ${e.message}")
        }
        try {
            val intent = Intent(this, ConnectToastActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Connect toast activity launch failed: ${e.message}")
        }
        // Lower SCREEN_OFF_TIMEOUT via the device plugin so the banner's
        // FLAG_KEEP_SCREEN_ON release can immediately sleep the display.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            routeToPlugin("device", "SLEEP_NOW", "")
        }, 200L)
    }

    /**
     * Public hook so SleepAccessibilityService's swipe-down gesture can
     * trigger the same screen-off path the connect banner uses.
     */
    fun sleepGlass() {
        routeToPlugin("device", "SLEEP_NOW", "")
    }

    private fun sendBaseStateToPhone() {
        val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
        val json = JSONObject().apply {
            put("tiltWake", prefs.getBoolean(BaseSettings.KEY_TILT_WAKE, false))
            put("autoStart", prefs.getBoolean(BaseSettings.KEY_AUTO_START, true))
            put("navKeepScreenOn", prefs.getBoolean(BaseSettings.KEY_NAV_KEEP_SCREEN_ON, false))
            put("navWakeOnUpdate", prefs.getBoolean(BaseSettings.KEY_NAV_WAKE_ON_UPDATE, false))
            put("wakeToTimeCard", prefs.getBoolean(BaseSettings.KEY_WAKE_TO_TIME_CARD, false))
            put("invertNav", prefs.getBoolean(BaseSettings.KEY_INVERT_NAV, false))
            put("stayAwakeWhenCharging", prefs.getBoolean(BaseSettings.KEY_STAY_AWAKE_WHEN_CHARGING, false))
            put("stayAwakeWhenChargingGranted", canWriteSecureSettings())
            put("backgroundFade", prefs.getInt(BaseSettings.KEY_BACKGROUND_FADE, 0))
            put("wallpaperOnSettings", prefs.getBoolean(BaseSettings.KEY_WALLPAPER_ON_SETTINGS, false))
            put("wallpaperOnAppDrawer", prefs.getBoolean(BaseSettings.KEY_WALLPAPER_ON_APP_DRAWER, false))
            put("notifSoundEnabled", prefs.getBoolean(BaseSettings.KEY_NOTIF_SOUND_ENABLED, true))
            put("notifSoundVolume", prefs.getInt(BaseSettings.KEY_NOTIF_SOUND_VOLUME, 100))
        }.toString()
        sendPluginMessage("base", "STATE", json)
    }

    private fun canWriteSecureSettings(): Boolean =
        packageManager.checkPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS,
            packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Active wallpaper upload server. Built on-demand on the first
     *  BG_UPLOAD_REQ and stopped after the single upload completes
     *  (or its idle timeout fires); see WallpaperUploadServer for
     *  the protocol. Held here so a subsequent BG_UPLOAD_REQ during
     *  an in-flight upload can return the existing URL rather than
     *  spinning up a second server. */
    private var wallpaperUploadServer: WallpaperUploadServer? = null

    private fun handleBgUploadReq(payload: String) {
        // Sanity-check the requested size up-front so we can fail
        // fast over BT instead of having the phone discover the
        // 413 after streaming the body.
        val size = try { JSONObject(payload).optLong("size", -1L) } catch (_: Exception) { -1L }
        if (size <= 0L) {
            sendPluginMessage("base", "BG_UPLOAD_ERR", JSONObject().apply {
                put("reason", "bad_size")
            }.toString())
            return
        }
        if (size > WallpaperUploadServer.MAX_SIZE_BYTES) {
            sendPluginMessage("base", "BG_UPLOAD_ERR", JSONObject().apply {
                put("reason", "too_large")
                put("max", WallpaperUploadServer.MAX_SIZE_BYTES)
            }.toString())
            return
        }

        // Spin up (or re-use) the upload server. Reusing on retry
        // keeps the same URL valid for phone-side retries inside
        // the idle window.
        val existing = wallpaperUploadServer
        val server = existing ?: WallpaperUploadServer(
            this,
            onComplete = { filename, bytes -> writeUploadedWallpaper(filename, bytes) },
            onError = { reason ->
                sendPluginMessage("base", "BG_UPLOAD_ERR", JSONObject().apply {
                    put("reason", reason)
                }.toString())
                wallpaperUploadServer = null
            }
        ).also { wallpaperUploadServer = it }

        val url = server.start()
        if (url == null) {
            Log.w(TAG, "BG_UPLOAD_REQ: no Wi-Fi LAN to advertise")
            sendPluginMessage("base", "BG_UPLOAD_ERR", JSONObject().apply {
                put("reason", "no_wifi")
            }.toString())
            wallpaperUploadServer = null
            return
        }
        sendPluginMessage("base", "BG_UPLOAD_OPEN", JSONObject().apply {
            put("url", url)
        }.toString())
    }

    private fun writeUploadedWallpaper(filename: String, bytes: ByteArray) {
        // Sanitize filename — keep only what looks like a basename so
        // a malicious phone can't path-traverse into / etc. Also pin
        // to .jpg if the phone sent something weird; HomeActivity
        // accepts jpg/jpeg/png.
        val safeName = filename
            .substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifEmpty { "wallpaper.jpg" }
            .let {
                val ext = it.substringAfterLast('.', "").lowercase()
                if (ext in setOf("jpg", "jpeg", "png")) it else "$it.jpg"
            }
        val dir = java.io.File("/sdcard/GlassHole/backgrounds")
        try {
            if (!dir.exists()) dir.mkdirs()
            val target = java.io.File(dir, safeName)
            target.outputStream().use { it.write(bytes) }
            Log.i(TAG, "Wallpaper written: ${target.absolutePath} (${bytes.size} bytes)")
            // Only keep the latest wallpaper — older uploads waste space
            // on the glass. Delete anything in the dir that isn't the
            // file we just wrote.
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.absolutePath != target.absolutePath) {
                    if (f.delete()) Log.i(TAG, "Pruned old wallpaper: ${f.name}")
                    else Log.w(TAG, "Failed to delete old wallpaper: ${f.name}")
                }
            }
            // Local broadcast so a foregrounded HomeActivity can pick
            // up the new wallpaper without the user backing out and
            // re-opening Home.
            sendBroadcast(
                Intent("com.glasshole.glass.WALLPAPER_CHANGED").setPackage(packageName)
            )
            sendPluginMessage("base", "BG_UPLOAD_DONE", JSONObject().apply {
                put("filename", safeName)
                put("size", bytes.size)
            }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Wallpaper write failed: ${e.message}")
            sendPluginMessage("base", "BG_UPLOAD_ERR", JSONObject().apply {
                put("reason", "write_failed")
                put("detail", e.message ?: "")
            }.toString())
        } finally {
            wallpaperUploadServer = null
        }
    }

    private fun handleLaunchPackage(payload: String) {
        val pkg = try { JSONObject(payload).optString("pkg") } catch (_: Exception) { "" }
        if (pkg.isEmpty()) return
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            Log.w(TAG, "LAUNCH_PACKAGE: no launcher intent for $pkg")
            return
        }
        wakeScreen(reason = "launch:$pkg")
        try {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            // Mark as a foreground launch so the SCREEN_ON receiver in
            // launcher mode doesn't race-relaunch HomeActivity over us.
            lastForegroundLaunchMs = android.os.SystemClock.elapsedRealtime()
            startActivity(launchIntent)
            Log.i(TAG, "Launched $pkg")
        } catch (e: Exception) {
            Log.w(TAG, "LAUNCH_PACKAGE failed for $pkg: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(reason: String) {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassHole:LaunchWake"
            )
            wl.acquire(3_000L)
        } catch (e: Exception) {
            Log.w(TAG, "wakeScreen($reason) failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private val stayAwakeWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "GlassHole:StayAwakeCharging"
        ).apply { setReferenceCounted(false) }
    }

    private val powerStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshStayAwakeWakeLock()
        }
    }

    /** Resets the BT-enable backoff when the system observes
     *  STATE_ON, so the listen-thread can immediately re-attempt
     *  the server socket once the adapter is actually usable
     *  again — typically after a manual toggle in Settings or
     *  the BT stack finishing its own error recovery. */
    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
            )
            if (state == BluetoothAdapter.STATE_ON && bluetoothEnableAttempts > 0) {
                Log.i(TAG, "ACTION_STATE_CHANGED: STATE_ON — clearing enable backoff")
                bluetoothEnableAttempts = 0
                bluetoothNextEnableElapsedMs = 0L
            }
        }
    }

    private fun applyStayAwakeWhenCharging(enabled: Boolean) {
        val flags = if (enabled) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC or
            android.os.BatteryManager.BATTERY_PLUGGED_USB or
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS
        } else 0
        try {
            android.provider.Settings.Global.putInt(
                contentResolver,
                android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                flags
            )
            Log.i(TAG, "STAY_ON_WHILE_PLUGGED_IN = $flags")
        } catch (e: SecurityException) {
            Log.w(TAG, "Need WRITE_SECURE_SETTINGS — adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS")
        } catch (e: Exception) {
            Log.w(TAG, "STAY_ON_WHILE_PLUGGED_IN write failed: ${e.message}")
        }
        refreshStayAwakeWakeLock()
    }

    /** Wakelock backup for OEMs that don't honor STAY_ON_WHILE_PLUGGED_IN
     *  — see EE2 for the rationale. */
    private fun refreshStayAwakeWakeLock() {
        val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
        val enabled = prefs.getBoolean(BaseSettings.KEY_STAY_AWAKE_WHEN_CHARGING, false)
        val plugged = isPlugged()
        val shouldHold = enabled && plugged
        try {
            if (shouldHold && !stayAwakeWakeLock.isHeld) {
                stayAwakeWakeLock.acquire()
                Log.i(TAG, "stay-awake-while-charging wakelock acquired")
            } else if (!shouldHold && stayAwakeWakeLock.isHeld) {
                stayAwakeWakeLock.release()
                Log.i(TAG, "stay-awake-while-charging wakelock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "stay-awake wakelock toggle failed: ${e.message}")
        }
    }

    private fun isPlugged(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val plugged = status.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
        return plugged != 0
    }

    private fun sendInfo() {
        try {
            // API 19 has no BatteryManager.getIntProperty; use sticky broadcast.
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val battery = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val charging = plugged != 0

            val json = JSONObject().apply {
                put("battery", battery)
                put("charging", charging)
                put("model", Build.MODEL)
                put("android", Build.VERSION.RELEASE)
                @Suppress("DEPRECATION")
                put("serial", Build.SERIAL)
                // Echo the glass-side build so the phone's connection card
                // can show what's actually running on the headset. Flavor
                // distinguishes a launcher install (replaces stock Glass
                // home) from a standalone install (sits alongside it).
                put("app_version", BuildConfig.VERSION_NAME)
                put("flavor", BuildConfig.FLAVOR)
            }
            writeRaw("INFO:$json\n")
            // Don't piggyback PLUGIN_LIST onto every INFO response — the
            // phone heartbeats every 10s, and the directory is ~8KB. That
            // saturates the BT RFCOMM buffer and queues notifications
            // behind it. PLUGIN_LIST is sent once on connect (see
            // handleConnection) and re-sent only when plugin packages
            // change.
        } catch (e: Exception) {
            Log.e(TAG, "Send info failed: ${e.message}")
        }
    }

    /**
     * Heavyweight device-info dump for the phone's Glass Device Info
     * page. Gathered on demand via DEVICE_INFO_REQ — kept off the
     * heartbeat so we don't burn ~3KB of bandwidth every 10s.
     */
    private fun sendDeviceInfo() {
        // Catch Throwable, not just Exception — gather methods reference
        // Build/VERSION fields that may be missing on older glass
        // editions, and an unhandled Error would propagate up to the BT
        // thread's run() and tear the connection down (which is what
        // happened on EE1 with SECURITY_PATCH).
        try {
            val json = gatherDeviceInfo().toString()
            writeRaw("DEVICE_INFO:$json\n")
        } catch (e: Throwable) {
            Log.e(TAG, "Send device info failed: ${e.message}")
        }
    }

    /**
     * Battery-only refresh — just the battery section, used for the
     * live graph in the phone's Glass Device Info page. Tiny payload
     * (~200 bytes) so polling every 5s costs nothing.
     */
    private fun sendBatteryInfo() {
        try {
            val json = gatherBatteryInfo().toString()
            writeRaw("BATTERY_INFO:$json\n")
        } catch (e: Throwable) {
            Log.e(TAG, "Send battery info failed: ${e.message}")
        }
    }

    private fun gatherDeviceInfo(): JSONObject {
        val root = JSONObject()
        root.put("hardware", gatherHardwareInfo())
        root.put("os", gatherOsInfo())
        root.put("network", gatherNetworkInfo())
        root.put("battery", gatherBatteryInfo())
        root.put("storage", gatherStorageInfo())
        root.put("memory", gatherMemoryInfo())
        root.put("misc", gatherMiscInfo())
        return root
    }

    private fun gatherHardwareInfo(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("board", Build.BOARD)
        put("hardware", Build.HARDWARE)
        put("bootloader", Build.BOOTLOADER)
        put("serial", try { @Suppress("DEPRECATION") Build.SERIAL } catch (_: Exception) { "?" })
        put("supported_abis",
            if (Build.VERSION.SDK_INT >= 21) Build.SUPPORTED_ABIS.joinToString()
            else @Suppress("DEPRECATION") "${Build.CPU_ABI}, ${Build.CPU_ABI2}"
        )
        val dm = resources.displayMetrics
        put("display_density_dpi", dm.densityDpi)
        put("display_width_px", dm.widthPixels)
        put("display_height_px", dm.heightPixels)
    }

    private fun gatherOsInfo(): JSONObject = JSONObject().apply {
        put("android_version", Build.VERSION.RELEASE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("codename", Build.VERSION.CODENAME)
        put("build_id", Build.ID)
        put("build_type", Build.TYPE)
        put("build_tags", Build.TAGS)
        put("fingerprint", Build.FINGERPRINT)
        put("display_id", Build.DISPLAY)
        put("incremental", Build.VERSION.INCREMENTAL)
        // SECURITY_PATCH was added in API 23. On EE1/XE (API 19) the field
        // doesn't exist and *just referencing it* throws NoSuchFieldError —
        // which is an Error, not Exception, so a plain try/catch (Exception)
        // wouldn't have caught it. Guard with SDK_INT and catch Throwable
        // as belt-and-braces in case another API gets ambushed by a future
        // additions to this method.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { put("security_patch", Build.VERSION.SECURITY_PATCH) } catch (_: Throwable) {}
        }
        try {
            val kernel = java.io.File("/proc/version").readText().trim()
            put("kernel", kernel)
        } catch (_: Exception) {}
    }

    private fun gatherNetworkInfo(): JSONObject = JSONObject().apply {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifi.connectionInfo
            if (info != null) {
                val ip = info.ipAddress
                if (ip != 0) {
                    put("wifi_ip", android.text.format.Formatter.formatIpAddress(ip))
                }
                put("wifi_ssid", info.ssid?.removeSurrounding("\""))
                put("wifi_bssid", info.bssid)
                put("wifi_link_speed_mbps", info.linkSpeed)
                put("wifi_rssi_dbm", info.rssi)
                if (Build.VERSION.SDK_INT >= 21) {
                    try { put("wifi_frequency_mhz", info.frequency) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        try {
            for (iface in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback) continue
                val name = iface.name
                val mac = iface.hardwareAddress?.joinToString(":") { "%02x".format(it) } ?: ""
                if (mac.isNotEmpty() && mac != "00:00:00:00:00:00") {
                    put("${name}_mac", mac)
                }
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val key = if (addr is java.net.Inet6Address) "${name}_ipv6" else "${name}_ipv4"
                    put(key, addr.hostAddress?.substringBefore('%'))
                }
            }
        } catch (_: Exception) {}
    }

    private fun gatherBatteryInfo(): JSONObject = JSONObject().apply {
        try {
            // BatteryManager.getIntProperty / isCharging arrived in API 21
            // and 23 respectively. EE1/XE are API 19 — they get only the
            // sticky-broadcast extras (which exist on every Android since
            // forever).
            if (Build.VERSION.SDK_INT >= 21) {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                try { put("percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)) } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= 23) {
                    try { put("charging", bm.isCharging) } catch (_: Exception) {}
                }
                arrayOf(
                    "current_now_uA" to BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
                    "current_avg_uA" to BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
                    "charge_counter_uAh" to BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
                ).forEach { (key, prop) ->
                    try {
                        val v = bm.getIntProperty(prop)
                        if (v != Int.MIN_VALUE) put(key, v)
                    } catch (_: Exception) {}
                }
                try {
                    val energy = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                    if (energy != Long.MIN_VALUE) put("energy_counter_nWh", energy)
                } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        val rem = bm.computeChargeTimeRemaining()
                        if (rem > 0) put("charge_time_remaining_ms", rem)
                    } catch (_: Exception) {}
                }
            }
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) {
                val voltage = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                if (voltage > 0) put("voltage_mV", voltage)
                val temp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (temp >= 0) put("temperature_c", temp / 10.0)
                put("health", batteryHealthString(
                    sticky.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                ))
                sticky.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.let {
                    put("technology", it)
                }
                put("plugged", batteryPluggedString(
                    sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                ))
                val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0) put("level", level)
                if (scale > 0) put("scale", scale)
            }
        } catch (_: Exception) {}
    }

    private fun gatherStorageInfo(): JSONObject = JSONObject().apply {
        try {
            val internal = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
            put("internal_total_bytes", internal.totalBytes)
            put("internal_available_bytes", internal.availableBytes)
        } catch (_: Exception) {}
        try {
            val ext = android.os.Environment.getExternalStorageDirectory()
            if (ext != null) {
                val externalFs = android.os.StatFs(ext.absolutePath)
                put("external_total_bytes", externalFs.totalBytes)
                put("external_available_bytes", externalFs.availableBytes)
            }
        } catch (_: Exception) {}
    }

    private fun gatherMemoryInfo(): JSONObject = JSONObject().apply {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            put("total_bytes", mi.totalMem)
            put("available_bytes", mi.availMem)
            put("low_memory", mi.lowMemory)
            put("threshold_bytes", mi.threshold)
        } catch (_: Exception) {}
    }

    private fun gatherMiscInfo(): JSONObject = JSONObject().apply {
        put("uptime_ms", android.os.SystemClock.elapsedRealtime())
        put("flavor", BuildConfig.FLAVOR)
        put("app_version", BuildConfig.VERSION_NAME)
        put("timezone", java.util.TimeZone.getDefault().id)
    }

    private fun batteryHealthString(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "unknown"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        else -> "unknown ($health)"
    }

    private fun batteryPluggedString(plugged: Int): String = when (plugged) {
        0 -> "not plugged"
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> "unknown ($plugged)"
    }

    private fun sendPluginList() {
        try {
            val entries = com.glasshole.glass.sdk.PluginDirectoryScanner.scan(this)
            val json = com.glasshole.glass.sdk.PluginDirectoryScanner.toJson(entries)
            val escaped = json.replace("\\", "\\\\").replace("\n", "\\n")
            if (writeRaw("PLUGIN_LIST:$escaped\n")) {
                Log.i(TAG, "PLUGIN_LIST sent (${entries.size} plugins)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Send plugin list failed: ${e.message}")
        }
    }

    // --- BT Server ---

    /** Backoff state for the "BT is off, please turn on" recovery path.
     *  When the Glass BT stack falls into ENABLE_TIMEOUT /
     *  recoverBluetoothServiceFromError, calling adapter.enable()
     *  every retry just keeps the BT manager spinning. Track
     *  consecutive failures and slow our retries down. */
    private var bluetoothEnableAttempts: Int = 0
    private var bluetoothNextEnableElapsedMs: Long = 0L

    private fun startListening() {
        Thread {
            while (running) {
                try {
                    Log.d(TAG, "Opening server socket...")
                    @Suppress("DEPRECATION")
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
                        Log.e(TAG, "No Bluetooth adapter")
                        return@Thread
                    }

                    if (!adapter.isEnabled) {
                        // Backoff so we don't hammer adapter.enable() every
                        // 6 s while the BT stack is stuck in error recovery.
                        // Schedule grows on each consecutive failure; resets
                        // to 0 the moment we observe STATE_ON.
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now < bluetoothNextEnableElapsedMs) {
                            val sleep = (bluetoothNextEnableElapsedMs - now).coerceAtMost(2_000L)
                            try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
                            continue
                        }
                        if (bluetoothEnableAttempts == 0) {
                            Log.w(TAG, "Bluetooth is off — attempting to enable")
                        } else {
                            Log.d(TAG, "Bluetooth still off — re-attempting enable (#${bluetoothEnableAttempts + 1})")
                        }
                        try { adapter.enable() } catch (_: Exception) {}
                        // Wait for STATE_ON; up to ~6s before falling through
                        // to the listen attempt (which will fail and retry).
                        var waited = 0
                        while (running && !adapter.isEnabled && waited < 6_000) {
                            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                            waited += 500
                        }
                        if (!adapter.isEnabled) {
                            bluetoothEnableAttempts++
                            val backoffMs = when (bluetoothEnableAttempts) {
                                1 -> 5_000L
                                2 -> 15_000L
                                3 -> 30_000L
                                else -> 60_000L
                            }
                            bluetoothNextEnableElapsedMs =
                                android.os.SystemClock.elapsedRealtime() + backoffMs
                            Log.w(
                                TAG,
                                "Bluetooth enable failed (#$bluetoothEnableAttempts) — " +
                                    "next retry in ${backoffMs / 1000}s. " +
                                    "Reboot the glass if this persists."
                            )
                            continue
                        }
                        // Adapter came online — clear backoff state.
                        if (bluetoothEnableAttempts > 0) {
                            Log.i(TAG, "Bluetooth came back online after $bluetoothEnableAttempts attempts")
                        }
                        bluetoothEnableAttempts = 0
                        bluetoothNextEnableElapsedMs = 0L
                    }

                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                    clientSocket = serverSocket?.accept()
                    try { serverSocket?.close() } catch (_: IOException) {}
                    serverSocket = null

                    outputStream = clientSocket?.outputStream
                    Log.d(TAG, "Phone connected!")
                    messageListener?.onConnectionStateChanged(true)
                    notifyPluginsConnectionChanged(true)

                    // Send the directory once on connect so the phone's
                    // PluginDirectory is populated. Used to ride along on
                    // every INFO_REQ but that was burning ~8KB/10s of the
                    // RFCOMM buffer on every heartbeat.
                    sendPluginList()

                    handleConnection(clientSocket!!)

                } catch (e: IOException) {
                    if (running) Log.e(TAG, "Bluetooth error: ${e.message}")
                } finally {
                    closeAll()
                    messageListener?.onConnectionStateChanged(false)
                    notifyPluginsConnectionChanged(false)
                    if (running) {
                        Log.d(TAG, "Will re-listen in 1 second...")
                        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                    }
                }
            }
        }.start()
    }

    private fun handleConnection(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))

        try {
            while (running) {
                val line = reader.readLine() ?: break
                Log.d(TAG, "Received: $line")

                when {
                    line.startsWith("PLUGIN:") -> {
                        val content = line.removePrefix("PLUGIN:")
                        val parts = content.split(":", limit = 3)
                        if (parts.size >= 2) {
                            val pluginId = parts[0]
                            val type = parts[1]
                            val payload = (parts.getOrElse(2) { "" })
                                .replace("\\n", "\n").replace("\\\\", "\\")
                            routeToPlugin(pluginId, type, payload)
                        }
                    }
                    line.startsWith("NOTIF:") -> {
                        val json = line.removePrefix("NOTIF:")
                            .replace("\\n", "\n").replace("\\\\", "\\")
                        com.glasshole.glassee1.home.NotificationStore.put(json)
                        showRichNotification(json)
                    }
                    line.startsWith("NOTIF_REMOVED:") -> {
                        val key = line.removePrefix("NOTIF_REMOVED:")
                            .replace("\\n", "\n").replace("\\\\", "\\")
                        com.glasshole.glassee1.home.NotificationStore.remove(key)
                    }
                    line.startsWith("MSG:") -> {
                        val message = line.removePrefix("MSG:")
                            .replace("\\n", "\n").replace("\\\\", "\\")
                        lastMessage = message
                        messageListener?.onMessageReceived(message)
                        showNotification(message)
                    }
                    line == "PING" -> {
                        writeRaw("PONG\n")
                    }
                    line == "INFO_REQ" -> {
                        sendInfo()
                    }
                    line == "PLUGIN_LIST_REQ" -> {
                        // Re-send the plugin directory when the phone
                        // explicitly asks for it — survives phone-app
                        // restarts that don't reset the BT socket.
                        sendPluginList()
                    }
                    line == "DEVICE_INFO_REQ" -> {
                        sendDeviceInfo()
                    }
                    line == "BATTERY_INFO_REQ" -> {
                        sendBatteryInfo()
                    }
                    line.startsWith("HOME_TZ:") -> {
                        val tz = line.removePrefix("HOME_TZ:").trim()
                        if (tz.isNotEmpty()) {
                            com.glasshole.glassee1.home.HomePrefs.setTimezone(this, tz)
                        }
                    }
                    line == "HOME_RESET_ADMIN_PROMPT" -> {
                        com.glasshole.glassee1.home.HomePrefs.resetAdminPrompt(this)
                        Log.i(TAG, "HOME_RESET_ADMIN_PROMPT — admin prompt will re-show")
                    }
                    line.startsWith("INSTALL:") -> {
                        handleInstall(line, reader)
                    }
                    line == "LIST_PACKAGES_REQ" -> {
                        sendPackageList()
                    }
                    line.startsWith("UNINSTALL:") -> {
                        val pkg = line.removePrefix("UNINSTALL:")
                        handleUninstall(pkg)
                    }
                    line == "LIVE_CAM_START" -> handleLiveCamStart()
                    line == "LIVE_CAM_STOP" -> handleLiveCamStop()
                    line == "LIVE_SCREEN_START" -> {
                        // Screen mirror is EE2-only (needs MediaProjection,
                        // API 21+). EE1 advertises this clearly so the
                        // phone can pop a meaningful toast.
                        sendLine("LIVE_SCREEN_ERR", "unsupported_edition")
                    }
                    line == "LIVE_SCREEN_STOP" -> { /* nothing to tear down */ }
                    else -> {
                        messageListener?.onMessageReceived(line)
                    }
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Connection ended: ${e.message}")
        }
    }

    private fun routeToPlugin(pluginId: String, type: String, payload: String) {
        // Base-app-owned settings (not routed to a plugin APK). Handled
        // inline so these features work on a vanilla glasshole install
        // without requiring any plugin package to be present.
        if (pluginId == "base") {
            handleBaseMessage(type, payload)
            return
        }

        // Home-owned IDs: route to the local broadcast HomeActivity picks up,
        // and skip AIDL dispatch so retired external plugin APKs don't
        // double-render the card.
        if (pluginId in HOME_OWNED_PLUGIN_IDS) {
            if (pluginId == "nav" && type == "NAV_UPDATE") maybeWakeForNavUpdate()
            val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_FROM_PHONE).apply {
                setPackage(packageName) // local-only
                putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, pluginId)
                putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, type)
                putExtra(GlassPluginConstants.EXTRA_PAYLOAD, payload)
            }
            sendBroadcast(intent)
            return
        }

        // Base-app service plugins (gallery): handed to an in-process helper
        // instead of AIDL-binding a retired plugin APK.
        if (pluginId in BASE_SERVICE_PLUGIN_IDS) {
            when (pluginId) {
                "gallery" -> galleryHandler.handleMessage(type, payload)
                "device" -> deviceHandler.handleMessage(type, payload)
            }
            return
        }

        // External plugin route — note the timestamp so the launcher's
        // SCREEN_ON receiver won't relaunch HomeActivity on top of any
        // activity the plugin is about to start.
        lastForegroundLaunchMs = android.os.SystemClock.elapsedRealtime()

        // Prefer AIDL when the plugin's been bound — GlassPluginService
        // also registers a broadcast receiver in its onCreate, so dispatching
        // both paths would deliver the message twice. Fall back to broadcast
        // only if AIDL isn't available or the call throws.
        val callback = pluginCallbacks[pluginId]
        if (callback != null) {
            try {
                callback.onMessageFromPhone(GlassPluginMessage(type, payload))
                return
            } catch (e: Exception) {
                Log.e(TAG, "Plugin callback failed for '$pluginId': ${e.message}")
                pluginCallbacks.remove(pluginId)
                // fall through to broadcast as fallback
            }
        }

        val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_FROM_PHONE).apply {
            // FLAG_INCLUDE_STOPPED_PACKAGES so manifest receivers in
            // "stopped" plugin apps (post-install or post-force-stop)
            // still get the broadcast and can resurrect their service.
            // Without it, a freshly-killed plugin process never sees
            // an OPEN until the user opens it manually.
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, pluginId)
            putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, type)
            putExtra(GlassPluginConstants.EXTRA_PAYLOAD, payload)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast plugin message: $pluginId:$type")
    }

    private fun notifyPluginsConnectionChanged(connected: Boolean) {
        for ((id, callback) in pluginCallbacks) {
            try {
                callback.onPhoneConnectionChanged(connected)
            } catch (e: Exception) {
                Log.e(TAG, "Plugin notify failed for '$id': ${e.message}")
                pluginCallbacks.remove(id)
            }
        }
    }

    private fun showNotification(message: String) {
        // Prefer a real GDK timeline card — it slots into the user's timeline
        // without hijacking the display. Fall back to the legacy full-screen
        // activity if the GDK isn't present on this device.
        val parsed = parseForwardedMessage(message)
        val body = buildCardBody(parsed)
        val footnote = parsed.app.ifEmpty { null }
        if (TimelineCard.insertText(this, body, footnote)) return

        lastForegroundLaunchMs = android.os.SystemClock.elapsedRealtime()
        val intent = Intent(this, NotificationDisplayActivity::class.java)
        intent.putExtra("message", message)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun showRichNotification(json: String) {
        try {
            val obj = JSONObject(json)
            val app = obj.optString("app", "")
            val title = obj.optString("title", "")
            val text = obj.optString("text", "")
            val icon = obj.optString("icon", "")
            val key = obj.optString("key", "")
            val actions = obj.optJSONArray("actions")
            val hasActions = actions != null && actions.length() > 0

            // GDK timeline cards are static — they can't host interactive
            // actions. Only take the timeline path when we have nothing to
            // interact with. Otherwise go straight to the full-screen Activity.
            if (!hasActions) {
                val cardBody = if (title.isNotEmpty() && text.isNotEmpty()) "$title\n$text"
                               else if (title.isNotEmpty()) title else text
                if (TimelineCard.insertText(this, cardBody, app.ifEmpty { null })) return
            }

            val picture = obj.optString("picture", "")
            val titleIcon = obj.optString("title_icon", "")
            lastForegroundLaunchMs = android.os.SystemClock.elapsedRealtime()
            val intent = Intent(this, NotificationDisplayActivity::class.java).apply {
                putExtra("app", app)
                putExtra("title", title)
                putExtra("text", text)
                putExtra("icon", icon)
                putExtra("title_icon", titleIcon)
                putExtra("picture", picture)
                putExtra("key", key)
                if (hasActions) putExtra("actions", actions!!.toString())
                if (obj.has("dismissMs")) {
                    putExtra("dismissMs", obj.optLong("dismissMs", 0L))
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Rich notif parse failed: ${e.message}")
        }
    }

    private data class ParsedNotif(val app: String, val title: String, val text: String)

    private fun parseForwardedMessage(raw: String): ParsedNotif {
        val colon = raw.indexOf(":")
        if (colon <= 0) return ParsedNotif("", "", raw.trim())
        val app = raw.substring(0, colon).trim()
        val rest = raw.substring(colon + 1).trim()
        val dash = rest.indexOf(" - ")
        return if (dash > 0) {
            ParsedNotif(app, rest.substring(0, dash).trim(), rest.substring(dash + 3).trim())
        } else {
            ParsedNotif(app, "", rest)
        }
    }

    private fun buildCardBody(parsed: ParsedNotif): String {
        return if (parsed.title.isNotEmpty()) {
            "${parsed.title}\n${parsed.text}"
        } else {
            parsed.text
        }
    }

    private fun handleInstall(header: String, reader: BufferedReader) {
        val parts = header.removePrefix("INSTALL:").split(":", limit = 3)
        if (parts.size < 3) return

        val filename = parts[0]
        val expectedSize = parts[1].toLongOrNull() ?: return
        val expectedMd5 = parts[2]

        Log.i(TAG, "Receiving APK: $filename ($expectedSize bytes)")

        val tempFile = java.io.File(cacheDir, filename)
        val fos = tempFile.outputStream()
        var totalBytes = 0L

        try {
            while (running) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("INSTALL_DATA:") -> {
                        val chunk = line.removePrefix("INSTALL_DATA:")
                        val bytes = android.util.Base64.decode(chunk, android.util.Base64.NO_WRAP)
                        fos.write(bytes)
                        totalBytes += bytes.size
                    }
                    line == "INSTALL_END" -> {
                        fos.close()
                        Log.i(TAG, "APK received: $totalBytes bytes")

                        val md5 = tempFile.inputStream().use { stream ->
                            val digest = java.security.MessageDigest.getInstance("MD5")
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (stream.read(buffer).also { read = it } != -1) {
                                digest.update(buffer, 0, read)
                            }
                            digest.digest().joinToString("") { "%02x".format(it) }
                        }

                        if (md5 == expectedMd5) {
                            Log.i(TAG, "MD5 verified, triggering install")
                            sendInstallAck(triggerInstall(tempFile))
                        } else {
                            Log.e(TAG, "MD5 mismatch: expected=$expectedMd5, got=$md5")
                            tempFile.delete()
                            sendInstallAck("failed:md5_mismatch")
                        }
                        return
                    }
                    else -> {
                        Log.w(TAG, "Unexpected during install: $line")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Install receive error: ${e.message}")
            fos.close()
            tempFile.delete()
            sendInstallAck("failed:${e.message}")
        }
    }

    private fun triggerInstall(apkFile: java.io.File): String {
        val path = apkFile.absolutePath

        // Path 1: pm install -r (needs INSTALL_PACKAGES — usually denied)
        val pmResult = tryShell(arrayOf("pm", "install", "-r", path))
        if (pmResult.success) {
            Log.i(TAG, "Installed via pm: $path")
            return "success:pm"
        }

        // Path 2: su pm install (only works on rooted Glass)
        val suResult = trySuShell("pm install -r $path")
        if (suResult.success) {
            Log.i(TAG, "Installed via su: $path")
            return "success:su"
        }

        // Path 3: fall back to ACTION_VIEW intent. Glass EE1 intercepts this with
        // PackageInstallerHandlerActivity which silently refuses if Unknown Sources
        // is off. Pre-Marshmallow "unknown sources" is a global toggle in
        // Settings.Secure — check it and show our own dialog that jumps to
        // Security settings when it's off.
        if (Build.VERSION.SDK_INT >= 26) {
            if (!packageManager.canRequestPackageInstalls()) {
                val permIntent = Intent(this, InstallPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(permIntent) } catch (_: Exception) {}
                return "needs_install_permission"
            }
        } else if (!isUnknownSourcesEnabled()) {
            val permIntent = Intent(this, InstallPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { startActivity(permIntent) } catch (_: Exception) {}
            return "needs_install_permission"
        }

        return try {
            // FileProvider works on API 19+ via androidx.core and is required on
            // API 24+ to avoid FileUriExposedException. Use it everywhere for
            // consistency.
            val apkUri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", apkFile
                )
            } catch (e: Exception) {
                Log.w(TAG, "FileProvider failed, falling back to Uri.fromFile: ${e.message}")
                @Suppress("DEPRECATION")
                Uri.fromFile(apkFile)
            }
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(installIntent)
            "prompt_shown"
        } catch (e: Exception) {
            Log.e(TAG, "Install trigger failed: ${e.message}")
            "failed:${e.message}"
        }
    }

    private fun isUnknownSourcesEnabled(): Boolean {
        // API 19 key. Deprecated in 17, removed from constants in 22, but the
        // raw string still works on Android 4.4 where this code actually runs.
        return try {
            android.provider.Settings.Secure.getInt(
                contentResolver, "install_non_market_apps", 0
            ) == 1
        } catch (_: Exception) {
            false
        }
    }

    private fun sendInstallAck(status: String) {
        writeRaw("INSTALL_ACK:$status\n")
    }

    // --- Package list / uninstall ---

    private fun sendPackageList() {
        val pm = packageManager
        val installed = pm.getInstalledApplications(0)
        val arr = org.json.JSONArray()
        for (info in installed) {
            if (!info.enabled) continue
            val pkg = info.packageName
            if (pkg.startsWith("com.google.glass.") ||
                pkg.startsWith("com.google.android.glass.")) continue

            val label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { pkg }
            val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val versionName = try {
                pm.getPackageInfo(pkg, 0).versionName ?: ""
            } catch (_: Exception) { "" }

            arr.put(org.json.JSONObject().apply {
                put("pkg", pkg)
                put("label", label)
                put("version", versionName)
                put("system", isSystem)
                put("glasshole", pkg.startsWith("com.glasshole."))
            })
        }
        if (writeRaw("LIST_PACKAGES:$arr\n")) {
            Log.i(TAG, "Sent package list (${arr.length()} entries)")
        }
    }

    private fun handleUninstall(pkg: String) {
        Log.i(TAG, "Uninstall requested: $pkg")
        if (pkg == packageName) {
            sendUninstallAck(pkg, "refused:self")
            return
        }

        val pmResult = tryShell(arrayOf("pm", "uninstall", pkg))
        if (pmResult.success) {
            sendUninstallAck(pkg, "success:pm")
            return
        }

        val suResult = trySuShell("pm uninstall $pkg")
        if (suResult.success) {
            sendUninstallAck(pkg, "success:su")
            return
        }

        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$pkg")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            sendUninstallAck(pkg, "prompt_shown:needs_unknown_sources")
        } catch (e: Exception) {
            Log.e(TAG, "Uninstall trigger failed: ${e.message}")
            sendUninstallAck(pkg, "failed:${e.message}")
        }
    }

    private data class CmdResult(val success: Boolean, val output: String)

    private fun tryShell(cmd: Array<String>): CmdResult {
        return try {
            val proc = Runtime.getRuntime().exec(cmd)
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val exit = proc.waitFor()
            val combined = (out + err).trim()
            Log.d(TAG, "shell ${cmd.joinToString(" ")}: exit=$exit out=$combined")
            CmdResult(exit == 0 && combined.contains("Success"), combined)
        } catch (e: Exception) {
            Log.d(TAG, "shell exec failed: ${e.message}")
            CmdResult(false, e.message ?: "")
        }
    }

    private fun trySuShell(script: String): CmdResult {
        for (suPath in listOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su")) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(suPath, "-c", script))
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                val exit = proc.waitFor()
                val combined = (out + err).trim()
                if (exit == 0 && combined.contains("Success")) {
                    return CmdResult(true, combined)
                }
            } catch (_: Exception) {
                // next path
            }
        }
        return CmdResult(false, "su_unavailable")
    }

    private fun sendUninstallAck(pkg: String, status: String) {
        writeRaw("UNINSTALL_ACK:$pkg:$status\n")
    }
}
