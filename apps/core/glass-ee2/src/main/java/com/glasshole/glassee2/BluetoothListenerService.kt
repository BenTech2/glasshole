package com.glasshole.glassee2

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
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
         * Plugin IDs whose messages the base app handles itself (via the
         * Home card surface) instead of AIDL-binding an external plugin APK.
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
        private const val NOTIF_CHANNEL_ID = "glasshole_forwarded"
        private var nextNotifId = 1000

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

    // Plugin callbacks (AIDL)
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

    // Debug live-stream features. Lazily-initialised so we don't open a
    // socket / camera unless the phone actually asks for a stream.
    private val cameraLiveSession by lazy {
        com.glasshole.glass.sdk.CameraLiveSession(this)
    }
    private val screenLiveSession by lazy { ScreenLiveSession(this) }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothListenerService = this@BluetoothListenerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "GlassHole Bluetooth",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // Separate high-importance channel so forwarded notifications show
        // heads-up on the glass display instead of silently filing in the shade.
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHANNEL_ID, "Forwarded notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications forwarded from your phone"
                enableVibration(true)
            }
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GlassHole")
            .setContentText("Listening for phone connection")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_ID, notification)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassHole::BT")
        wakeLock?.acquire()

        // Let the manifest-declared PluginMessageReceiver forward plugin
        // broadcasts (CalcActivity / NotesMenuActivity / DictateActivity) to us.
        PluginMessageReceiver.btService = this

        // Bind to PluginHostService so it runs discoverAndBindPlugins() —
        // which in turn binds each glasshole plugin service via AIDL so they
        // register their callbacks with this service. Without this kick,
        // plugin services stay dormant after a reinstall or reboot and
        // incoming messages like NOTE_LIST fall into the broadcast fallback
        // branch (where nothing is listening on EE2).
        startPluginHost()

        // Re-assert the user's "stay awake while charging" preference
        // — Settings.Global.STAY_ON_WHILE_PLUGGED_IN persists, but if
        // anything else flipped it (developer-options dabbling, factory
        // reset) we want our stored choice to win on the next boot.
        val basePrefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
        applyStayAwakeWhenCharging(
            basePrefs.getBoolean(BaseSettings.KEY_STAY_AWAKE_WHEN_CHARGING, false)
        )
        // Listen for plug/unplug so the wakelock-backup tracks state
        // even when the user (or the toggle) didn't change.
        try {
            registerReceiver(powerStateReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
        } catch (e: Exception) {
            Log.w(TAG, "powerStateReceiver register failed: ${e.message}")
        }

        startListening()
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
        closeAll()
        wakeLock?.release()
        wakeLock = null
        try { unregisterReceiver(powerStateReceiver) } catch (_: Exception) {}
        try { if (stayAwakeWakeLock.isHeld) stayAwakeWakeLock.release() } catch (_: Exception) {}
        stopForeground(true)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — service continues running")
        val restartIntent = Intent(this, BluetoothListenerService::class.java)
        startForegroundService(restartIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun closeAll() {
        try { outputStream?.close() } catch (_: IOException) {}
        try { clientSocket?.close() } catch (_: IOException) {}
        try { serverSocket?.close() } catch (_: IOException) {}
        outputStream = null
        clientSocket = null
        serverSocket = null
        // Free the camera + projection if a live stream was running —
        // a phone disconnect always cancels any debug session.
        try { cameraLiveSession.stop() } catch (_: Exception) {}
        try { screenLiveSession.stop() } catch (_: Exception) {}
        releaseScreenMirrorWakeLock()
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

    // --- Send methods ---

    fun sendReply(message: String): Boolean {
        val escaped = message.replace("\\", "\\\\").replace("\n", "\\n")
        return writeRaw("REPLY:$escaped\n")
    }

    fun sendPluginMessage(pluginId: String, type: String, payload: String): Boolean {
        val escaped = payload.replace("\\", "\\\\").replace("\n", "\\n")
        return writeRaw("PLUGIN:$pluginId:$type:$escaped\n")
    }

    /** Send a notification action invocation back to the phone. */
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

    /** Called by NotificationDisplayActivity when the user taps "Watch on Glass". */
    fun playStreamLocally(url: String) {
        val payload = JSONObject().apply { put("url", url) }.toString()
        routeToPlugin("stream", "PLAY_URL", payload)
    }

    /**
     * Handle settings owned by the base app itself (tilt-to-wake, etc.).
     * These don't route through an external plugin APK so they work on a
     * stock glasshole install.
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
                    if (enabled) startForegroundService(svc) else stopService(svc)
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
            "BG_UPLOAD_REQ" -> handleBgUploadReq(payload)
            "LAUNCH_PACKAGE" -> handleLaunchPackage(payload)
            "GET_STATE" -> sendBaseStateToPhone()
            "SHOW_CONNECT_NOTIF" -> showConnectToast()
            else -> Log.d(TAG, "Unknown base message: $type")
        }
    }

    private fun maybeWakeForNavUpdate() {
        val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(BaseSettings.KEY_NAV_WAKE_ON_UPDATE, false)) return
        // Brief wake lock with ACQUIRE_CAUSES_WAKEUP — if the screen is off,
        // this pops the display back on long enough for HomeActivity's
        // showWhenLocked + turnScreenOn manifest flags to bring the nav card
        // to the foreground.
        @Suppress("DEPRECATION")
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassHole:NavWake"
            )
            wl.acquire(3_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Nav wake lock failed: ${e.message}")
        }
    }

    private fun showConnectToast() {
        // Very brief SCREEN_BRIGHT wake lock with ACQUIRE_CAUSES_WAKEUP to
        // pop the display out of standby. The activity's FLAG_KEEP_SCREEN_ON
        // takes over for the banner duration; shorter wake lock here means
        // the screen-off timeout can kick in right when the banner ends.
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
        // Lower SCREEN_OFF_TIMEOUT right away (via the device plugin, which
        // holds WRITE_SETTINGS) so the moment the banner activity finishes
        // and releases FLAG_KEEP_SCREEN_ON the display can time out quickly.
        // EE2 blocks PowerManager.goToSleep via reflection, this is the
        // reliable path there.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            routeToPlugin("device", "SLEEP_NOW", "")
        }, 200L)
    }

    /**
     * Public hook so MainActivity's swipe-down-to-sleep gesture can trigger
     * the same screen-off path the connect banner uses.
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
        }.toString()
        sendPluginMessage("base", "STATE", json)
    }

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

    /** Whether the system has granted us WRITE_SECURE_SETTINGS yet —
     *  reported back to the phone so the toggle can show a hint when
     *  the permission isn't there. The check is a cheap PackageManager
     *  call; safe to do on the BT listener thread. */
    private fun canWriteSecureSettings(): Boolean =
        packageManager.checkPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS,
            packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Push the user's chosen "stay awake while charging" state to the
     *  global system setting that controls the platform's screen-on
     *  behaviour while plugged in. We set the flag for AC + USB +
     *  wireless charging so any cradle / cable combo keeps the display
     *  alive. Silently no-ops without WRITE_SECURE_SETTINGS — the
     *  phone toggle surfaces that situation via the granted-flag in
     *  STATE so the user can grant via adb. */
    /** Foreground a plugin's main activity by package name. The phone
     *  uses this from the Plugins screen's quick-launch icon — pressing
     *  it on the phone immediately wakes the glass into that plugin
     *  without a separate cover-flow swipe. Silently no-ops if the
     *  package has no launcher intent (worker-style plugins). */
    private fun handleLaunchPackage(payload: String) {
        val pkg = try { JSONObject(payload).optString("pkg") } catch (_: Exception) { "" }
        if (pkg.isEmpty()) return
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            Log.w(TAG, "LAUNCH_PACKAGE: no launcher intent for $pkg")
            return
        }
        // Wake the display first — same pattern as the NAV wake — so a
        // remote-launch from the phone actually surfaces on the glass
        // even if it's currently dozing.
        wakeScreen(reason = "launch:$pkg")
        try {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(launchIntent)
            Log.i(TAG, "Launched $pkg")
        } catch (e: Exception) {
            Log.w(TAG, "LAUNCH_PACKAGE failed for $pkg: ${e.message}")
        }
    }

    /** Brief SCREEN_BRIGHT wakelock with ACQUIRE_CAUSES_WAKEUP — pops
     *  the display on long enough for the just-started activity's own
     *  showWhenLocked / turnScreenOn flags (where they exist) to keep
     *  it lit. Same trick maybeWakeForNavUpdate uses. */
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

    /** SCREEN_BRIGHT wakelock held while the user has stay-awake-while-
     *  charging enabled AND the device is plugged in. EE2's OEM doesn't
     *  honour STAY_ON_WHILE_PLUGGED_IN reliably (verified: setting reads
     *  7, AC plugged, screen still times out at 15s), so we pin the
     *  display via wakelock instead. The Settings.Global write still
     *  goes out as a best-effort for devices that *do* honour it. */
    @Suppress("DEPRECATION")
    private val stayAwakeWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "GlassHole:StayAwakeCharging"
        ).apply { setReferenceCounted(false) }
    }

    private val powerStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Either ACTION_POWER_CONNECTED / DISCONNECTED — re-evaluate
            // the lock based on current pref + plugged state.
            refreshStayAwakeWakeLock()
        }
    }

    private fun applyStayAwakeWhenCharging(enabled: Boolean) {
        // 1. Best-effort Settings.Global write — works on stock Android,
        //    silent no-op on Glass OEM builds that don't read this flag.
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
        // 2. Wakelock backup for the OEM-doesn't-listen case. Re-evaluate
        //    immediately so toggling on while already plugged works.
        refreshStayAwakeWakeLock()
    }

    /** Acquire / release the stay-awake wakelock based on the current
     *  pref + the device's plugged state. Idempotent — safe to call any
     *  time the inputs change. */
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
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging

            val json = JSONObject().apply {
                put("battery", battery)
                put("charging", charging)
                put("model", Build.MODEL)
                put("android", Build.VERSION.RELEASE)
                put("serial", Build.SERIAL)
            }
            writeRaw("INFO:$json\n")
            // Don't piggyback PLUGIN_LIST onto every INFO response — phone
            // heartbeats every 10s and the directory is ~8KB. Sent once on
            // connect (handleConnection) instead.
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
        try {
            val json = gatherDeviceInfo().toString()
            writeRaw("DEVICE_INFO:$json\n")
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        try { put("security_patch", Build.VERSION.SECURITY_PATCH) } catch (_: Exception) {}
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
                put("wifi_frequency_mhz", info.frequency)
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

    // --- Live stream debug features ---

    private fun sendLine(prefix: String, value: String) {
        writeRaw("$prefix:$value\n")
    }

    private fun handleLiveCamStart() {
        // Runtime permission gate: EE2 is API 27, CAMERA is dangerous
        // and must be granted explicitly. We pop our own permission
        // shim activity on the glass and ask the user to retry from
        // the phone once they've granted it.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val intent = Intent(this, CameraPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "CameraPermissionActivity launch failed: ${e.message}")
            }
            sendLine("LIVE_CAM_ERR", "permission_required")
            return
        }
        // Bounce off the BT reader thread — Camera1 open + parameter
        // negotiation can block for a couple hundred ms.
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

    private fun handleLiveScreenStart() {
        // Glass EE2's tiny display can't reliably expose the system
        // MediaProjection consent dialog's [Start now] button to the
        // touchpad. Arm the accessibility service to auto-click it as
        // soon as the dialog appears. Watchdog clears the flag if the
        // dialog never shows so we don't accidentally auto-confirm
        // some other system dialog later.
        SleepAccessibilityService.autoConfirmProjection = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            SleepAccessibilityService.autoConfirmProjection = false
        }, 5_000L)

        // 1) Get user consent via the system MediaProjection dialog.
        //    The activity hands the result back through a static callback,
        //    which then either starts the streamer or replies with ERR.
        ProjectionConsentActivity.pendingResult = { code, data ->
            if (code != Activity.RESULT_OK || data == null) {
                sendLine("LIVE_SCREEN_ERR", "consent_denied")
                Log.i(TAG, "LIVE_SCREEN consent denied")
            } else {
                screenLiveSession.setConsent(code, data)
                Thread {
                    when (val st = screenLiveSession.start(onUserRevoked = {
                        sendLine("LIVE_SCREEN_ERR", "user_revoked")
                    })) {
                        is ScreenLiveSession.Status.Started -> {
                            sendLine("LIVE_SCREEN_URL", st.url)
                            Log.i(TAG, "LIVE_SCREEN_START → ${st.url}")
                        }
                        ScreenLiveSession.Status.NoWifi ->
                            sendLine("LIVE_SCREEN_ERR", "no_wifi")
                        ScreenLiveSession.Status.NoConsent ->
                            sendLine("LIVE_SCREEN_ERR", "consent_denied")
                        ScreenLiveSession.Status.CaptureFailed ->
                            sendLine("LIVE_SCREEN_ERR", "capture_failed")
                    }
                }.apply { isDaemon = true; start() }
            }
        }
        try {
            val intent = Intent(this, ProjectionConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch ProjectionConsentActivity: ${e.message}")
            ProjectionConsentActivity.pendingResult = null
            sendLine("LIVE_SCREEN_ERR", "launch_failed")
        }
    }

    private fun handleLiveScreenStop() {
        screenLiveSession.stop()
        releaseScreenMirrorWakeLock()
        Log.i(TAG, "LIVE_SCREEN_STOP")
    }

    /** Held while the phone has the "keep glass screen on" toggle on
     *  during a live mirror session. Released by handleLiveScreenStop
     *  and closeAll so we never leak a lock past disconnect. */
    private var screenMirrorWakeLock: PowerManager.WakeLock? = null

    private fun handleLiveScreenKeepAwake(enabled: Boolean) {
        if (enabled) acquireScreenMirrorWakeLock()
        else releaseScreenMirrorWakeLock()
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenMirrorWakeLock() {
        if (screenMirrorWakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "GlassHole:ScreenMirrorAwake"
            )
            wl.setReferenceCounted(false)
            wl.acquire()
            screenMirrorWakeLock = wl
            Log.i(TAG, "screen-mirror wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "screen-mirror wake lock failed: ${e.message}")
        }
    }

    private fun releaseScreenMirrorWakeLock() {
        try {
            screenMirrorWakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        if (screenMirrorWakeLock != null) {
            Log.i(TAG, "screen-mirror wake lock released")
        }
        screenMirrorWakeLock = null
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

    /**
     * Emit the current PLUGIN_LIST — one line of JSON listing every
     * installed plugin's id / name / description / version / has_schema.
     * Phone's PluginDirectory consumes this and drives the dynamic
     * settings UI off it.
     */
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

    private fun startListening() {
        Thread {
            while (running) {
                try {
                    Log.d(TAG, "Opening server socket...")
                    val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                    val adapter = manager.adapter ?: run {
                        Log.e(TAG, "No Bluetooth adapter")
                        return@Thread
                    }

                    if (!adapter.isEnabled) {
                        Log.w(TAG, "Bluetooth is off — attempting to enable")
                        try { @Suppress("DEPRECATION") adapter.enable() } catch (_: Exception) {}
                        var waited = 0
                        while (running && !adapter.isEnabled && waited < 6_000) {
                            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                            waited += 500
                        }
                        if (!adapter.isEnabled) continue
                    }

                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                    clientSocket = serverSocket?.accept()
                    try { serverSocket?.close() } catch (_: IOException) {}
                    serverSocket = null

                    outputStream = clientSocket?.outputStream
                    Log.d(TAG, "Phone connected!")
                    messageListener?.onConnectionStateChanged(true)
                    notifyPluginsConnectionChanged(true)

                    // Send the directory once on connect — used to ride
                    // along on every INFO_REQ but that was burning ~8KB
                    // per heartbeat.
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
                        com.glasshole.glassee2.home.NotificationStore.put(json)
                        showRichNotification(json)
                    }
                    line.startsWith("NOTIF_REMOVED:") -> {
                        val key = line.removePrefix("NOTIF_REMOVED:")
                            .replace("\\n", "\n").replace("\\\\", "\\")
                        com.glasshole.glassee2.home.NotificationStore.remove(key)
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
                        // Phone-side BridgeService instances request a
                        // re-send when they bind — handles the case where
                        // the phone process restarted but the BT socket
                        // survived (so the once-per-connect send was
                        // missed).
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
                            com.glasshole.glassee2.home.HomePrefs.setTimezone(this, tz)
                        }
                    }
                    line == "HOME_RESET_ADMIN_PROMPT" -> {
                        com.glasshole.glassee2.home.HomePrefs.resetAdminPrompt(this)
                        Log.i(TAG, "HOME_RESET_ADMIN_PROMPT — admin prompt will re-show")
                    }
                    line.startsWith("INSTALL:") -> {
                        handleInstall(line, reader)
                    }
                    line == "LIST_PACKAGES_REQ" -> {
                        sendPackageList()
                    }
                    line.startsWith("UNINSTALL:") -> {
                        handleUninstall(line.removePrefix("UNINSTALL:"))
                    }
                    line == "LIVE_CAM_START" -> handleLiveCamStart()
                    line == "LIVE_CAM_STOP" -> handleLiveCamStop()
                    line == "LIVE_SCREEN_START" -> handleLiveScreenStart()
                    line == "LIVE_SCREEN_STOP" -> handleLiveScreenStop()
                    line.startsWith("LIVE_SCREEN_KEEP_AWAKE:") ->
                        handleLiveScreenKeepAwake(
                            line.removePrefix("LIVE_SCREEN_KEEP_AWAKE:").trim() == "1"
                        )
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

        // Home-owned plugin IDs: route directly to the base-app broadcast
        // so HomeActivity can pick up media / nav updates. External plugin
        // APKs for these IDs are retired — we intentionally ignore any
        // still-installed old versions to avoid double-rendering.
        if (pluginId in HOME_OWNED_PLUGIN_IDS) {
            if (pluginId == "nav" && type == "NAV_UPDATE") maybeWakeForNavUpdate()
            val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_FROM_PHONE).apply {
                setPackage(packageName) // local-only broadcast
                putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, pluginId)
                putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, type)
                putExtra(GlassPluginConstants.EXTRA_PAYLOAD, payload)
            }
            sendBroadcast(intent)
            return
        }

        // Base-app service plugins: handed to an in-process helper instead
        // of AIDL-binding a retired plugin APK. Same "ignore any stale APK"
        // guarantee as HOME_OWNED_PLUGIN_IDS.
        if (pluginId in BASE_SERVICE_PLUGIN_IDS) {
            when (pluginId) {
                "gallery" -> galleryHandler.handleMessage(type, payload)
                "device" -> deviceHandler.handleMessage(type, payload)
            }
            return
        }

        val callback = pluginCallbacks[pluginId]
        if (callback != null) {
            try {
                callback.onMessageFromPhone(GlassPluginMessage(type, payload))
            } catch (e: Exception) {
                Log.e(TAG, "Plugin callback failed for '$pluginId': ${e.message}")
                pluginCallbacks.remove(pluginId)
            }
        } else {
            // Fallback: try broadcast for plugins that use broadcast instead of AIDL
            val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_FROM_PHONE).apply {
                // Bypass the package-stopped state so manifest receivers
                // in killed plugin apps still wake on phone-side OPENs.
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, pluginId)
                putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, type)
                putExtra(GlassPluginConstants.EXTRA_PAYLOAD, payload)
            }
            sendBroadcast(intent)
            Log.d(TAG, "Broadcast plugin message: $pluginId:$type")
        }
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
        // EE2 has no visible notification shade — take over the display with
        // a full-screen card activity, same pattern we use on EE1.
        val intent = Intent(this, NotificationDisplayActivity::class.java).apply {
            putExtra("message", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification activity: ${e.message}")
        }
    }

    private fun showRichNotification(json: String) {
        try {
            val obj = JSONObject(json)
            val intent = Intent(this, NotificationDisplayActivity::class.java).apply {
                putExtra("app", obj.optString("app", ""))
                putExtra("title", obj.optString("title", ""))
                putExtra("text", obj.optString("text", ""))
                putExtra("icon", obj.optString("icon", ""))
                putExtra("title_icon", obj.optString("title_icon", ""))
                putExtra("picture", obj.optString("picture", ""))
                putExtra("key", obj.optString("key", ""))
                if (obj.has("actions")) {
                    putExtra("actions", obj.getJSONArray("actions").toString())
                }
                if (obj.has("dismissMs")) {
                    putExtra("dismissMs", obj.optLong("dismissMs", 0L))
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse rich notification: ${e.message}")
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

                        // Verify MD5
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
        // Path 1: pm install -r (needs INSTALL_PACKAGES — usually denied)
        val pmResult = tryShell(arrayOf("pm", "install", "-r", apkFile.absolutePath))
        if (pmResult.success) {
            Log.i(TAG, "Installed via pm")
            return "success:pm"
        }

        // Path 2: su pm install (only works on rooted glass)
        val suResult = trySuShell("pm install -r ${apkFile.absolutePath}")
        if (suResult.success) {
            Log.i(TAG, "Installed via su")
            return "success:su"
        }

        // Path 3: ACTION_VIEW intent via FileProvider. EE2 needs REQUEST_INSTALL_PACKAGES
        // granted or the installer silently refuses. If it isn't granted, show
        // our own explainer dialog that jumps straight to the right settings page —
        // the system installer's error is unhelpful on glass.
        if (!packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(this, InstallPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { startActivity(permIntent) } catch (_: Exception) {}
            return "needs_install_permission"
        }

        return try {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", apkFile
            )
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

        // Path 1: pm uninstall (needs DELETE_PACKAGES)
        val pmResult = tryShell(arrayOf("pm", "uninstall", pkg))
        if (pmResult.success) {
            sendUninstallAck(pkg, "success:pm")
            return
        }

        // Path 2: su pm uninstall
        val suResult = trySuShell("pm uninstall $pkg")
        if (suResult.success) {
            sendUninstallAck(pkg, "success:su")
            return
        }

        // Path 3: ACTION_DELETE intent → user confirms on glass
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:$pkg")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            sendUninstallAck(pkg, "prompt_shown:needs_unknown_sources")
        } catch (e: Exception) {
            Log.e(TAG, "Uninstall trigger failed: ${e.message}")
            sendUninstallAck(pkg, "failed:${e.message}")
        }
    }

    private fun sendUninstallAck(pkg: String, status: String) {
        writeRaw("UNINSTALL_ACK:$pkg:$status\n")
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
}
