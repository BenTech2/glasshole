package com.glasshole.phone.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.glasshole.phone.MainActivity
import com.glasshole.phone.bt.DecodedMessage
import com.glasshole.phone.bt.ProtocolCodec
import com.glasshole.phone.model.GlassInfo
import com.glasshole.sdk.PluginMessage
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

class BridgeService : Service() {

    companion object {
        private const val TAG = "GlassHoleBridge"
        private val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "glasshole_bridge"
        private const val HEARTBEAT_INTERVAL = 10_000L
        // Background battery sample cadence. Coarse — we want a rolling
        // history that survives across activity opens/closes, not a
        // live readout. The device-info activity does its own 5s poll
        // when it's foreground; both paths write to the same store.
        private const val BATTERY_POLL_INTERVAL_MS = 60_000L
        // If we've heard nothing back from the glass for this long, assume
        // the glass-side socket is dead even if writes still appear to
        // succeed (BT RFCOMM buffers can mask a half-closed connection,
        // especially after `pm install` force-stops the glass app).
        private const val INBOUND_STALE_THRESHOLD = 25_000L
        private const val RECONNECT_DELAY_INITIAL = 1_000L
        private const val RECONNECT_DELAY_MAX = 4_000L
        private const val LAUNCH_CHANNEL_ID = "glasshole_launch"
        private const val LAUNCH_NOTIFICATION_ID = 2
        private const val CONNECT_CHANNEL_ID = "glasshole_connect"
        private const val CONNECT_NOTIFICATION_ID = 3
        // Shut the whole service down after this long without a live BT
        // connection so the wake lock + reconnect loop don't drain the
        // phone's battery when the user is nowhere near their glass.
        // Reopening the GlassHole app restarts everything.
        private const val IDLE_SHUTDOWN_MS = 10L * 60_000L
        private const val IDLE_CHECK_INTERVAL_MS = 60_000L

        /** Notification-action intent asking the service to shut down entirely. */
        const val ACTION_STOP = "com.glasshole.phone.service.BRIDGE_STOP"

        @Volatile var instance: BridgeService? = null
    }

    inner class LocalBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // Bluetooth state
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    @Volatile private var btConnected = false
    /** Latest GlassInfo from the heartbeat. The Glass Device Info
     *  page and the BatteryHistoryStore both look up the current
     *  device id from here. */
    @Volatile var lastGlassInfo: GlassInfo? = null
        private set
    /** Stable ID for the connected glass — serial when present, falls
     *  back to model+android. Used as the file key for
     *  BatteryHistoryStore so each glass keeps its own log. */
    val currentDeviceId: String
        get() {
            val info = lastGlassInfo ?: return ""
            val serial = info.serial
            if (serial.isNotBlank() && serial != "?" && serial != "unknown") return serial
            return "${info.model}-${info.androidVersion}".trim('-').ifEmpty { "" }
        }
    private val batteryPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            if (btConnected) {
                requestBatteryInfo()
                batteryPollHandler.postDelayed(this, BATTERY_POLL_INTERVAL_MS)
            }
        }
    }
    @Volatile private var running = false
    private var readerThread: Thread? = null
    private var heartbeatThread: Thread? = null
    @Volatile private var lastInboundMs: Long = 0L

    // Auto-reconnect
    private var targetDevice: BluetoothDevice? = null
    @Volatile private var autoReconnect = false
    private var reconnectThread: Thread? = null

    // Wake lock
    private var wakeLock: PowerManager.WakeLock? = null

    // Idle watchdog — 0 when connected (or user is actively driving a
    // connect attempt); otherwise the millis stamp at which we became idle.
    @Volatile private var disconnectedSinceMillis: Long = 0L
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleCheckRunnable = object : Runnable {
        override fun run() {
            val since = disconnectedSinceMillis
            if (since != 0L && System.currentTimeMillis() - since >= IDLE_SHUTDOWN_MS) {
                Log.i(TAG, "Idle for ≥10min — shutting down bridge service")
                shutdownAndKill("Disconnected for 10 min — reopen the app to reconnect")
                return
            }
            idleHandler.postDelayed(this, IDLE_CHECK_INTERVAL_MS)
        }
    }

    // Callbacks
    var onLog: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onGlassInfo: ((GlassInfo) -> Unit)? = null
    /** Raw DEVICE_INFO json — consumed by GlassDeviceInfoActivity. */
    var onGlassDeviceInfo: ((String) -> Unit)? = null
    /** Raw BATTERY_INFO json — lightweight 5s poll for the live battery
     *  graph + values inside GlassDeviceInfoActivity. */
    var onGlassBatteryInfo: ((String) -> Unit)? = null
    var onNotificationReply: ((String) -> Unit)? = null

    // APK manager callbacks
    var onPackageList: ((String) -> Unit)? = null
    var onUninstallResult: ((pkg: String, status: String) -> Unit)? = null
    var onInstallResult: ((status: String) -> Unit)? = null
    var onInstallProgress: ((sent: Long, total: Long) -> Unit)? = null

    // Plugin router — set by PluginHostService
    var pluginRouter: com.glasshole.phone.plugin.PluginRouter? = null

    // Notification forwarding callback
    var onNotificationFromGlass: ((String) -> Unit)? = null

    // Debug live-stream callbacks. DebugActivity registers these for
    // the duration of a request — the bridge forwards LIVE_*_URL on
    // success and LIVE_*_ERR with a short machine-readable reason
    // string on failure (no_wifi / camera_busy / unsupported_edition /
    // consent_denied / capture_failed / user_revoked).
    var onLiveCamUrl: ((String) -> Unit)? = null
    var onLiveCamErr: ((String) -> Unit)? = null
    var onLiveScreenUrl: ((String) -> Unit)? = null
    var onLiveScreenErr: ((String) -> Unit)? = null

    /** Catches incoming `PLUGIN:base:*` messages from glass — used
     *  by the wallpaper-upload flow (BG_UPLOAD_OPEN / DONE / ERR)
     *  and any future base-plugin round-trip. Receiver runs on the
     *  BT reader thread; bounce to the main thread yourself if
     *  you're touching UI. */
    var onBaseMessage: ((type: String, payload: String) -> Unit)? = null

    val isConnected: Boolean get() = btConnected

    /**
     * Coordinates workers for dynamic plugins. Activates once at service
     * start and drives primitives' lifecycle off PluginDirectory updates.
     */
    private val workerManager by lazy {
        com.glasshole.phone.plugindir.worker.WorkerManager(
            appContext = applicationContext,
            send = { pluginId, type, payload ->
                sendPluginMessage(pluginId, type, payload)
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Register built-in worker primitives exactly once per process.
        com.glasshole.phone.plugindir.worker.WorkerRegistry.registerBuiltIns()
        workerManager.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        }
        running = true

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassHole::Bridge")
        wakeLock?.acquire()

        // Service has just started without a live connection — start the
        // idle clock. The watchdog will shutdownAndKill() if we don't
        // reach a connected state within IDLE_SHUTDOWN_MS.
        disconnectedSinceMillis = System.currentTimeMillis()
        idleHandler.postDelayed(idleCheckRunnable, IDLE_CHECK_INTERVAL_MS)

        startWeatherSchedulerIfEnabled()
    }

    override fun onDestroy() {
        running = false
        instance = null
        autoReconnect = false
        workerManager.stop()
        idleHandler.removeCallbacks(idleCheckRunnable)
        stopWeatherScheduler()
        reconnectThread?.interrupt()
        reconnectThread = null
        disconnectBluetooth()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdownAndKill("Stopping GlassHole — reopen the app to reconnect")
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * Tear everything down and kill the process. Used by the foreground
     * notification's Stop action and by the idle watchdog. Bound clients
     * (MainActivity, PluginHostService) would otherwise keep the process
     * alive, so we Process.killProcess after a short grace period — the
     * user's only way back is to relaunch the app icon.
     */
    private fun shutdownAndKill(reason: String) {
        Log.i(TAG, "shutdownAndKill: $reason")
        log(reason)
        running = false
        autoReconnect = false
        idleHandler.removeCallbacks(idleCheckRunnable)
        disconnectBluetooth()
        wakeLock?.release()
        wakeLock = null
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopService(Intent(this, PluginHostService::class.java))
        stopSelf()
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 300)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed — service continues running")
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "GlassHole Bridge",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("GlassHole")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Launch another app on the phone, waking the screen if it's off.
     *
     * Android 15's BAL refuses every "task → foreground" path we tried
     * (direct pi.send, same-UID LaunchRelay, FGS-context PI send) with
     * `balDontBringExistingBackgroundTaskStackToFg`. The only sideload-
     * accessible escape hatch is a full-screen-intent notification:
     * Android treats FSI fires as user-initiated, so the PI launches
     * without BAL interference AND the screen wakes. Requires the
     * USE_FULL_SCREEN_INTENT permission (granted at install on Android 13,
     * needs a Settings toggle on Android 14+).
     */
    fun launchAppOnPhone(pkg: String?, url: String?): Boolean {
        if (pkg.isNullOrBlank() && url.isNullOrBlank()) return false
        return try {
            val target = buildLaunchIntent(pkg, url) ?: return false
            val fsPi = PendingIntent.getActivity(
                this,
                (pkg ?: url ?: "").hashCode(),
                target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            ensureLaunchChannel()
            val label = try {
                if (!pkg.isNullOrBlank()) {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } else url
            } catch (_: Exception) { pkg ?: url }
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, LAUNCH_CHANNEL_ID)
                    .setContentTitle("Opening $label on phone")
                    .setContentText("From Glass")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(fsPi)
                    .setFullScreenIntent(fsPi, true)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Opening $label on phone")
                    .setContentText("From Glass")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(fsPi)
                    .setFullScreenIntent(fsPi, true)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_MAX)
                    .build()
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(LAUNCH_NOTIFICATION_ID, notif)
            // The notification has done its job once the system fires the PI
            // and the target activity is in front — cancel so it doesn't
            // linger as a dead "Opening X" banner.
            Handler(Looper.getMainLooper()).postDelayed({
                try { nm.cancel(LAUNCH_NOTIFICATION_ID) } catch (_: Exception) {}
            }, 1500)
            Log.i(TAG, "FSI launch posted: pkg=$pkg url=$url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "FSI launch failed: ${e.message}")
            false
        }
    }

    private fun buildLaunchIntent(pkg: String?, url: String?): Intent? {
        if (!url.isNullOrBlank()) {
            return Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                if (!pkg.isNullOrBlank() &&
                    packageManager.getLaunchIntentForPackage(pkg) != null) {
                    setPackage(pkg)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        if (!pkg.isNullOrBlank()) {
            return packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }
        return null
    }

    /**
     * Show a brief "Connected to Glass" heads-up on the phone and cue the
     * glass to post its own on-headset card. Both sides gated on the
     * "connect_notify_enabled" pref from Device Controls. Off by default.
     */
    private fun notifyConnectionSuccess(deviceName: String) {
        val enabled = getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)
            .getBoolean("connect_notify_enabled", false)
        if (!enabled) return

        // Phone-side heads-up.
        try {
            ensureConnectChannel()
            val nm = getSystemService(NotificationManager::class.java)
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CONNECT_CHANNEL_ID)
                    .setContentTitle("Connected to Glass")
                    .setContentText(deviceName)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .setTimeoutAfter(5_000L)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Connected to Glass")
                    .setContentText(deviceName)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .build()
            }
            nm.notify(CONNECT_NOTIFICATION_ID, notif)
            // Belt + suspenders: cancel after 5s even if setTimeoutAfter is
            // unsupported or ignored on the target Android version.
            Handler(Looper.getMainLooper()).postDelayed({
                try { nm.cancel(CONNECT_NOTIFICATION_ID) } catch (_: Exception) {}
            }, 5_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Phone connect notification failed: ${e.message}")
        }

        // Glass-side cue.
        try {
            sendPluginMessage("base", "SHOW_CONNECT_NOTIF", "{}")
        } catch (e: Exception) {
            Log.w(TAG, "SHOW_CONNECT_NOTIF send failed: ${e.message}")
        }
    }

    /**
     * Upload a wallpaper image to glass over the LAN. Coordinates the
     * three-step round-trip:
     *
     *   1. BT: phone sends `base:BG_UPLOAD_REQ {filename, size}`.
     *   2. BT: glass replies `base:BG_UPLOAD_OPEN {url}` (or
     *      `BG_UPLOAD_ERR {reason}` on no-Wi-Fi / oversize).
     *   3. HTTP POST: phone streams [bytes] to the URL.
     *   4. BT: glass writes the file and replies `BG_UPLOAD_DONE` (or
     *      `BG_UPLOAD_ERR` on disk failure).
     *
     * Bytes ride Wi-Fi rather than BT specifically to avoid piling
     * more traffic on glass's flaky RFCOMM stack — same architecture
     * as the camera live-stream feature in reverse.
     *
     * [onResult] runs on a worker thread.
     */
    fun uploadWallpaper(
        bytes: ByteArray,
        filename: String,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        if (!btConnected) {
            onResult(false, "Glass not connected")
            return
        }

        val prevHandler = onBaseMessage
        // Use a handler that the user can replace by setting
        // onBaseMessage to something else mid-flight; restore prev
        // on completion / failure.
        onBaseMessage = handler@{ type, payload ->
            when (type) {
                "BG_UPLOAD_OPEN" -> {
                    val url = try {
                        org.json.JSONObject(payload).optString("url", "")
                    } catch (_: Exception) { "" }
                    Log.i(TAG, "BG_UPLOAD_OPEN url=$url bytes=${bytes.size}")
                    if (url.isEmpty()) {
                        onBaseMessage = prevHandler
                        onResult(false, "Glass returned no URL")
                        return@handler
                    }
                    Thread {
                        try {
                            postWallpaperBytes(url, filename, bytes)
                            // Successful HTTP — now wait for the BT
                            // DONE / ERR reply that confirms the disk
                            // write went through.
                        } catch (e: Exception) {
                            Log.e(TAG, "Wallpaper POST failed: ${e.javaClass.simpleName}: ${e.message}", e)
                            onBaseMessage = prevHandler
                            onResult(false, "POST failed: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }.apply { isDaemon = true; name = "BgUpload-post"; start() }
                }
                "BG_UPLOAD_DONE" -> {
                    onBaseMessage = prevHandler
                    val fn = try {
                        org.json.JSONObject(payload).optString("filename", filename)
                    } catch (_: Exception) { filename }
                    onResult(true, "Wallpaper saved: $fn")
                }
                "BG_UPLOAD_ERR" -> {
                    onBaseMessage = prevHandler
                    val reason = try {
                        org.json.JSONObject(payload).optString("reason", "unknown")
                    } catch (_: Exception) { "unknown" }
                    onResult(false, "Glass rejected: $reason")
                }
                else -> prevHandler?.invoke(type, payload)
            }
        }

        val req = org.json.JSONObject().apply {
            put("filename", filename)
            put("size", bytes.size)
        }.toString()
        val sent = sendPluginMessage("base", "BG_UPLOAD_REQ", req)
        if (!sent) {
            onBaseMessage = prevHandler
            onResult(false, "Failed to send upload request")
        }
    }

    private fun postWallpaperBytes(url: String, filename: String, bytes: ByteArray) {
        // Append the filename as a query param so the server can
        // pick the on-disk name without parsing a multipart body.
        val sep = if (url.contains('?')) '&' else '?'
        val encoded = java.net.URLEncoder.encode(filename, "UTF-8")
        val finalUrl = "$url${sep}filename=$encoded"
        Log.i(TAG, "POSTing $bytes.size bytes to $finalUrl")
        val conn = (java.net.URL(finalUrl).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/octet-stream")
            connectTimeout = 10_000
            readTimeout = 60_000
        }
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        Log.i(TAG, "POST response code=$code")
        try { conn.inputStream.close() } catch (_: Exception) {}
        if (code != 200) {
            throw java.io.IOException("HTTP $code")
        }
    }

    /**
     * Ask the glass for its current Wi-Fi IP + SSID. Sends GET_WIFI_IP
     * over the base channel, swaps onBaseMessage to catch the WIFI_IP
     * reply (restoring the prior handler whether we succeed, fail, or
     * time out), and invokes [onResult] on the BT reader thread with
     * the parsed values (ip and ssid may be empty when Wi-Fi is off).
     *
     * Mirrors the swap-and-restore pattern in uploadWallpaper. The 5 s
     * timeout is generous — the round-trip is tiny but BT RFCOMM can
     * stall briefly after a recent disconnect.
     */
    /**
     * Upload an audio file to the glass for per-app notification
     * sounds. Mirrors uploadWallpaper's swap-and-restore handler
     * pattern — request a one-shot HTTP URL over BT, POST the bytes
     * over Wi-Fi, wait for the DONE/ERR ack.
     */
    fun uploadNotifSound(
        bytes: ByteArray,
        filename: String,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        if (!btConnected) {
            onResult(false, "Glass not connected"); return
        }
        val prevHandler = onBaseMessage
        onBaseMessage = handler@{ type, payload ->
            when (type) {
                "NOTIF_SOUND_UPLOAD_OPEN" -> {
                    val url = try {
                        org.json.JSONObject(payload).optString("url", "")
                    } catch (_: Exception) { "" }
                    Log.i(TAG, "NOTIF_SOUND_UPLOAD_OPEN url=$url bytes=${bytes.size}")
                    if (url.isEmpty()) {
                        onBaseMessage = prevHandler
                        onResult(false, "Glass returned no URL"); return@handler
                    }
                    Thread {
                        try {
                            postWallpaperBytes(url, filename, bytes)
                        } catch (e: Exception) {
                            Log.e(TAG, "Notif sound POST failed", e)
                            onBaseMessage = prevHandler
                            onResult(false, "POST failed: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }.apply { isDaemon = true; name = "NotifSoundUpload-post"; start() }
                }
                "NOTIF_SOUND_UPLOAD_DONE" -> {
                    onBaseMessage = prevHandler
                    val fn = try {
                        org.json.JSONObject(payload).optString("filename", filename)
                    } catch (_: Exception) { filename }
                    onResult(true, fn)
                }
                "NOTIF_SOUND_UPLOAD_ERR" -> {
                    onBaseMessage = prevHandler
                    val reason = try {
                        org.json.JSONObject(payload).optString("reason", "unknown")
                    } catch (_: Exception) { "unknown" }
                    onResult(false, "Glass rejected: $reason")
                }
                else -> prevHandler?.invoke(type, payload)
            }
        }

        val req = org.json.JSONObject().apply {
            put("filename", filename)
            put("size", bytes.size)
        }.toString()
        if (!sendPluginMessage("base", "NOTIF_SOUND_UPLOAD_REQ", req)) {
            onBaseMessage = prevHandler
            onResult(false, "Failed to send upload request")
        }
    }

    /** Ask the glass for the current list of uploaded notif-sound files.
     *  Reply lands on the same swap-and-restore channel as everything
     *  else. */
    fun queryNotifSoundList(onResult: (files: List<String>, error: String?) -> Unit) {
        if (!btConnected) { onResult(emptyList(), "Glass not connected"); return }
        val prevHandler = onBaseMessage
        var fired = false
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (!fired) { fired = true; onBaseMessage = prevHandler
                onResult(emptyList(), "Timed out waiting for glass") }
        }
        onBaseMessage = handler@{ type, payload ->
            if (type == "NOTIF_SOUND_LIST") {
                if (fired) return@handler
                fired = true
                timeoutHandler.removeCallbacks(timeout)
                onBaseMessage = prevHandler
                val files = mutableListOf<String>()
                try {
                    val arr = org.json.JSONObject(payload).optJSONArray("files")
                    if (arr != null) for (i in 0 until arr.length()) files.add(arr.getString(i))
                } catch (_: Exception) {}
                onResult(files, null)
            } else prevHandler?.invoke(type, payload)
        }
        timeoutHandler.postDelayed(timeout, 5_000L)
        if (!sendPluginMessage("base", "NOTIF_SOUND_LIST_REQ", "")) {
            timeoutHandler.removeCallbacks(timeout)
            if (!fired) {
                fired = true; onBaseMessage = prevHandler
                onResult(emptyList(), "Send failed")
            }
        }
    }

    /** Tell the glass to delete an uploaded notif-sound file. Fire-
     *  and-forget; the glass replies with a fresh NOTIF_SOUND_LIST
     *  the next time we ask. */
    fun deleteNotifSound(filename: String) {
        sendPluginMessage("base", "NOTIF_SOUND_DELETE",
            org.json.JSONObject().apply { put("filename", filename) }.toString())
    }

    /** Send the per-app sound choice (or clear it when soundId is
     *  empty). Glass-side prefs key is the app's package name. */
    fun setNotifAppSound(pkg: String, soundId: String): Boolean {
        val json = org.json.JSONObject().apply {
            put("pkg", pkg)
            put("soundId", soundId)
        }.toString()
        return sendPluginMessage("base", "SET_NOTIF_APP_SOUND", json)
    }

    /**
     * Push an APK over the Wi-Fi LAN to the glass and trigger
     * triggerInstall there. Same shape as uploadWallpaper /
     * uploadNotifSound: BT carries the four short
     * APK_INSTALL_REQ/_OPEN/_DONE/_ERR envelopes, Wi-Fi carries the
     * bytes. Replaces the chunked-BT path which has been crashing
     * the glass-side BT stack mid-stream on EE2.
     *
     * Progress callbacks fire during the POST so the existing
     * ApkManagerActivity progress dialog stays useful. Final
     * onResult is called once the glass replies with DONE or ERR.
     */
    fun installApkOverWifi(
        bytes: ByteArray,
        filename: String,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        if (!btConnected) { onResult(false, "Glass not connected"); return }
        val prevHandler = onBaseMessage
        onBaseMessage = handler@{ type, payload ->
            when (type) {
                "APK_INSTALL_OPEN" -> {
                    val url = try {
                        org.json.JSONObject(payload).optString("url", "")
                    } catch (_: Exception) { "" }
                    Log.i(TAG, "APK_INSTALL_OPEN url=$url bytes=${bytes.size}")
                    if (url.isEmpty()) {
                        onBaseMessage = prevHandler
                        onResult(false, "Glass returned no URL"); return@handler
                    }
                    Thread {
                        try {
                            postWallpaperBytes(url, filename, bytes)
                        } catch (e: Exception) {
                            Log.e(TAG, "APK POST failed", e)
                            onBaseMessage = prevHandler
                            onResult(false, "POST failed: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }.apply { isDaemon = true; name = "ApkInstall-post"; start() }
                }
                "APK_INSTALL_DONE" -> {
                    onBaseMessage = prevHandler
                    val status = try {
                        org.json.JSONObject(payload).optString("status", "ok")
                    } catch (_: Exception) { "ok" }
                    // Phone-side success matches the same "glass got the
                    // bytes" semantics the old chunked path used —
                    // triggerInstall may still pop a system installer
                    // prompt on glass that the user has to tap. Pass the
                    // status string through so the caller can surface it.
                    onResult(true, status)
                }
                "APK_INSTALL_ERR" -> {
                    onBaseMessage = prevHandler
                    val reason = try {
                        org.json.JSONObject(payload).optString("reason", "unknown")
                    } catch (_: Exception) { "unknown" }
                    onResult(false, "Glass rejected: $reason")
                }
                else -> prevHandler?.invoke(type, payload)
            }
        }

        val req = org.json.JSONObject().apply {
            put("filename", filename)
            put("size", bytes.size)
        }.toString()
        if (!sendPluginMessage("base", "APK_INSTALL_REQ", req)) {
            onBaseMessage = prevHandler
            onResult(false, "Failed to send install request")
        }
    }

    /** Snapshot of the glass's Wi-Fi radio state. */
    data class GlassWifiState(
        val enabled: Boolean,
        val connected: Boolean,
        val ssid: String,
        val ip: String,
    )

    /** Single scan-result entry returned by [scanGlassWifi]. */
    data class GlassWifiNetwork(
        val ssid: String,
        val rssi: Int,
        val security: String,
    )

    fun queryGlassWifiState(onResult: (state: GlassWifiState?, error: String?) -> Unit) {
        runOneShotBaseRoundTrip(
            request = "GET_WIFI_STATE", payload = "",
            replyType = "WIFI_STATE", timeoutMs = 5_000L,
            parse = { json ->
                GlassWifiState(
                    enabled = json.optBoolean("enabled", false),
                    connected = json.optBoolean("connected", false),
                    ssid = json.optString("ssid", ""),
                    ip = json.optString("ip", ""),
                )
            },
            onResult = onResult,
        )
    }

    /** Toggles the glass-side Wi-Fi radio. Glass echoes a fresh
     *  WIFI_STATE after the call; this fire-and-forget only confirms
     *  the BT send succeeded — callers should requery the state if
     *  they need the post-toggle truth. */
    fun setGlassWifiEnabled(enabled: Boolean): Boolean {
        val json = org.json.JSONObject().put("enabled", enabled).toString()
        return sendPluginMessage("base", "SET_WIFI_ENABLED", json)
    }

    /** Home Screen prefs (Time card top-bar): show the numeric battery
     *  percent next to the icon. Default true. */
    fun setShowBatteryPercent(enabled: Boolean): Boolean {
        val json = org.json.JSONObject().put("enabled", enabled).toString()
        return sendPluginMessage("base", "SET_SHOW_BATTERY_PERCENT", json)
    }

    /** Home Screen prefs (Time card top-bar): mirror battery vs
     *  connection icons (battery left, icons right when enabled).
     *  Default false. */
    fun setSwapTopBar(enabled: Boolean): Boolean {
        val json = org.json.JSONObject().put("enabled", enabled).toString()
        return sendPluginMessage("base", "SET_SWAP_TOP_BAR", json)
    }

    /** Ask glass to enumerate its launcher activities + ship the list
     *  back via PLUGIN:base:LAUNCHER_APPS_LIST. The phone-side
     *  PinnedAppsActivity listens on onBaseMessage for the reply. */
    fun requestLauncherApps(): Boolean =
        sendPluginMessage("base", "LIST_LAUNCHER_APPS_REQ", "")

    /** Push the user's pinned package list to glass. Capped at 4 on
     *  the glass side; extra entries dropped silently. */
    fun setPinnedApps(pkgs: List<String>): Boolean {
        val arr = org.json.JSONArray()
        pkgs.forEach { arr.put(it) }
        val json = org.json.JSONObject().put("pkgs", arr).toString()
        return sendPluginMessage("base", "SET_PINNED_APPS", json)
    }

    /** Send a cached / fresh weather payload to glass. Pass null to
     *  tell glass "weather is off, hide the chip" — useful when the
     *  user disables the feature so the stale value doesn't linger. */
    /** Proxy the AI Assistant plugin's ASK envelope through to the
     *  selected provider, on a daemon thread (HTTP is blocking and
     *  the BT read loop must keep moving). Replies with PLUGIN:ai:
     *  RESPONSE on success or PLUGIN:ai:ERROR on failure so the glass
     *  activity can render an actionable message. */
    private fun handleAiAssistantAsk(payload: String) {
        Thread {
            val envelope = try { org.json.JSONObject(payload) } catch (e: Exception) {
                replyAi("ERROR", "Bad envelope: ${e.message}")
                return@Thread
            }
            val result = AiAssistantHttpClient.ask(envelope)
            if (result.text != null) {
                val reply = org.json.JSONObject().put("text", result.text).toString()
                replyAi("RESPONSE", reply)
            } else {
                val reply = result.error.orEmpty().ifBlank { "Unknown failure" }
                replyAi("ERROR", reply)
            }
        }.apply { isDaemon = true; name = "AiAssistant" }.start()
    }

    private fun replyAi(type: String, payload: String) {
        Handler(Looper.getMainLooper()).post {
            sendPluginMessage("ai", type, payload)
        }
    }

    /** Re-fetch the glass-side plugin directory. Used by the Plugins
     *  screen's refresh button after a new plugin manifest meta-data
     *  ships (e.g. SCHEMA newly declared) — without this the phone's
     *  in-memory PluginDirectory keeps the stale has_schema=false
     *  flag and the settings gear stays hidden. */
    fun requestPluginList(): Boolean {
        if (!btConnected) return false
        return try {
            sendRaw(ProtocolCodec.encodePluginListReq())
            true
        } catch (_: Exception) { false }
    }

    fun sendWeatherUpdate(result: WeatherFetcher.Result?): Boolean {
        val json = if (result == null) {
            org.json.JSONObject().put("enabled", false).toString()
        } else {
            org.json.JSONObject().apply {
                put("enabled", true)
                put("temp", Math.round(result.tempCurrent))
                put("high", Math.round(result.tempHigh))
                put("low", Math.round(result.tempLow))
                put("code", result.weatherCode)
                put("isDay", result.isDay)
                put("units", result.units)
                if (result.aqi != null) put("aqi", result.aqi)
                put("ts", result.fetchedAt / 1000L)
            }.toString()
        }
        return sendPluginMessage("base", "WEATHER_UPDATE", json)
    }

    // --- Weather scheduler ---

    /** Valid refetch cadences exposed to the user (minutes). The
     *  scheduler clamps any pref to this set so out-of-band values
     *  written by older builds or hand-edits can't strand us at e.g.
     *  60s and burn through Open-Meteo's free-tier rate limit. */
    private val weatherIntervalChoicesMin = listOf(5, 15, 30, 60, 120)
    private val weatherHandler = Handler(Looper.getMainLooper())
    private val weatherTick = object : Runnable {
        override fun run() {
            runWeatherFetchIfDue(force = false)
            weatherHandler.postDelayed(this, weatherIntervalMs())
        }
    }

    /** Read the user's chosen interval from prefs and convert to ms.
     *  Default 30 min; anything outside [weatherIntervalChoicesMin]
     *  snaps back to 30 so a corrupt pref can't break the loop. */
    private fun weatherIntervalMs(): Long {
        val raw = getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)
            .getInt("weather_interval_minutes", 30)
        val safe = if (raw in weatherIntervalChoicesMin) raw else 30
        return safe.toLong() * 60 * 1000
    }

    /** Called from onCreate to seed the periodic refetch, and again
     *  from connectBluetooth's success path so the chip lights up
     *  immediately on (re-)connect. */
    fun startWeatherSchedulerIfEnabled() {
        weatherHandler.removeCallbacks(weatherTick)
        if (!isWeatherEnabled()) return
        weatherHandler.postDelayed(weatherTick, 4_000L)
    }

    /** Restart the scheduler with the latest interval pref. Called
     *  when the user picks a new cadence in DeviceActivity so the
     *  change takes effect on the next tick rather than waiting out
     *  the previous (potentially long) interval. */
    fun restartWeatherScheduler() {
        weatherHandler.removeCallbacks(weatherTick)
        if (!isWeatherEnabled()) return
        weatherHandler.postDelayed(weatherTick, weatherIntervalMs())
    }

    fun stopWeatherScheduler() {
        weatherHandler.removeCallbacks(weatherTick)
    }

    private fun isWeatherEnabled(): Boolean =
        getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)
            .getBoolean("weather_enabled", true)

    private fun weatherUnits(): String =
        getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)
            .getString("weather_units", "F") ?: "F"

    /** Fetch + cache + ship to glass. [force] skips the freshness check
     *  so a unit-toggle flip can reflect immediately. Runs the HTTP
     *  call on a daemon thread to keep the BT main loop unblocked. */
    fun runWeatherFetchIfDue(force: Boolean) {
        if (!isWeatherEnabled()) return
        val prefs = getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)
        val lastTs = prefs.getLong("weather_last_ts", 0L)
        if (!force && System.currentTimeMillis() - lastTs < weatherIntervalMs()) {
            // Still fresh — just re-send what we have so glass doesn't
            // miss an update across a (re)connect.
            shipCachedWeather()
            return
        }
        // Active location: request a fresh fix via the NETWORK provider
        // (Wi-Fi positioning, no GPS spin-up), then chain the forecast
        // + AQI fetches on a daemon thread. requestFreshLocation falls
        // back to last-known after its internal timeout so this can't
        // hang forever even with location disabled mid-flight.
        WeatherFetcher.requestFreshLocation(this@BridgeService, 8_000L) { loc ->
            if (loc == null) {
                log("Weather: no location available — skipping fetch")
                return@requestFreshLocation
            }
            Thread {
                val units = weatherUnits()
                val forecast = WeatherFetcher.fetch(loc.latitude, loc.longitude, units) ?: run {
                    log("Weather: Open-Meteo fetch failed")
                    return@Thread
                }
                // Air quality is a best-effort secondary call — Open-Meteo's
                // AQI coverage is global-ish but spotty in remote regions.
                val aqi = WeatherFetcher.fetchAirQuality(loc.latitude, loc.longitude)
                val result = forecast.copy(aqi = aqi)
                writeAndShipWeather(prefs, result)
            }.apply { isDaemon = true; name = "WeatherFetch" }.start()
        }
    }

    private fun writeAndShipWeather(
        prefs: android.content.SharedPreferences,
        result: WeatherFetcher.Result,
    ) {
        prefs.edit().apply {
            putLong("weather_last_ts", result.fetchedAt)
            putString("weather_payload", org.json.JSONObject().apply {
                put("temp", result.tempCurrent)
                put("high", result.tempHigh)
                put("low", result.tempLow)
                put("code", result.weatherCode)
                put("isDay", result.isDay)
                put("units", result.units)
                if (result.aqi != null) put("aqi", result.aqi)
                put("fetchedAt", result.fetchedAt)
            }.toString())
        }.apply()
        Handler(Looper.getMainLooper()).post { sendWeatherUpdate(result) }
    }

    /** Re-ship the last cached payload (if any) to glass. Called from
     *  the connection-up path so a reconnect doesn't leave the chip
     *  blank until the next scheduler tick. */
    fun shipCachedWeather() {
        if (!isWeatherEnabled()) return
        val prefs = getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("weather_payload", null) ?: return
        try {
            val obj = org.json.JSONObject(raw)
            sendWeatherUpdate(
                WeatherFetcher.Result(
                    tempCurrent = obj.optDouble("temp", Double.NaN),
                    tempHigh = obj.optDouble("high", Double.NaN),
                    tempLow = obj.optDouble("low", Double.NaN),
                    weatherCode = obj.optInt("code", 0),
                    isDay = obj.optBoolean("isDay", true),
                    units = obj.optString("units", "F"),
                    aqi = if (obj.has("aqi")) obj.optInt("aqi") else null,
                    fetchedAt = obj.optLong("fetchedAt", 0L),
                )
            )
        } catch (_: Exception) {}
    }

    /** Trigger a fresh scan on the glass and wait for the result.
     *  Allow ~3 s — the glass-side handler delays 800 ms before
     *  reading the cache to give the radio a chance to populate. */
    fun scanGlassWifi(onResult: (networks: List<GlassWifiNetwork>, error: String?) -> Unit) {
        runOneShotBaseRoundTrip(
            request = "WIFI_SCAN_REQ", payload = "",
            replyType = "WIFI_SCAN_RESULT", timeoutMs = 6_000L,
            parse = { json ->
                val out = mutableListOf<GlassWifiNetwork>()
                val arr = json.optJSONArray("networks")
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(GlassWifiNetwork(
                        ssid = o.optString("ssid", ""),
                        rssi = o.optInt("rssi", -100),
                        security = o.optString("security", "OPEN"),
                    ))
                }
                out
            },
            onResult = { list, err -> onResult(list ?: emptyList(), err) },
        )
    }

    /** Add + enable a WifiConfiguration on the glass. [security] is
     *  one of "OPEN", "WEP", "WPA", "WPA2", "WPA3" — matches the
     *  badge returned by scanGlassWifi. */
    fun connectGlassWifi(
        ssid: String, password: String, security: String,
        onResult: (ok: Boolean, message: String) -> Unit,
    ) {
        val req = org.json.JSONObject().apply {
            put("ssid", ssid)
            put("password", password)
            put("security", security)
        }.toString()
        runOneShotBaseRoundTrip(
            request = "WIFI_CONNECT_REQ", payload = req,
            replyType = "WIFI_CONNECT_RESULT", timeoutMs = 8_000L,
            parse = { json ->
                Pair(
                    json.optBoolean("ok", false),
                    json.optString("reason", "")
                )
            },
            onResult = { pair, err ->
                if (err != null) onResult(false, err)
                else onResult(pair?.first == true, pair?.second.orEmpty())
            },
        )
    }

    /** Ask glass to flip wireless ADB on (root required). Glass runs
     *  `setprop service.adb.tcp.port 5555 && stop adbd && start adbd`
     *  through its RootHelper and replies with the live state — port +
     *  IP if the daemon came up, or an error string if su was refused
     *  / missing. The reply also fires Magisk/SuperSU's grant prompt on
     *  glass the first time, which is the whole reason this button
     *  exists. */
    fun enableGlassWirelessAdb(
        onResult: (ok: Boolean, message: String) -> Unit,
    ) {
        runOneShotBaseRoundTrip(
            request = "ENABLE_WIRELESS_ADB", payload = "",
            replyType = "WIRELESS_ADB_RESULT", timeoutMs = 20_000L,
            parse = { json ->
                Triple(
                    json.optBoolean("ok", false),
                    json.optInt("port", 0),
                    Pair(json.optString("ip", ""), json.optString("reason", "")),
                )
            },
            onResult = { trip, err ->
                if (err != null) { onResult(false, err); return@runOneShotBaseRoundTrip }
                if (trip == null) { onResult(false, "no reply"); return@runOneShotBaseRoundTrip }
                val (ok, port, rest) = trip
                val (ip, reason) = rest
                val msg = when {
                    ok && ip.isNotEmpty() && port > 0 -> "adb connect $ip:$port"
                    ok && port > 0 -> "Listening on port $port (no Wi-Fi IP)"
                    reason.isNotEmpty() -> reason
                    else -> "Failed"
                }
                onResult(ok, msg)
            },
        )
    }

    /** Generic single-message round trip on the base channel.
     *  Captures the next reply of [replyType], parses it with
     *  [parse], restores the previous handler. Used by the Wi-Fi
     *  helpers above so each one isn't ~50 lines of swap/restore
     *  boilerplate. */
    private fun <T> runOneShotBaseRoundTrip(
        request: String, payload: String,
        replyType: String, timeoutMs: Long,
        parse: (org.json.JSONObject) -> T,
        onResult: (T?, String?) -> Unit,
    ) {
        if (!btConnected) { onResult(null, "Glass not connected"); return }
        val prevHandler = onBaseMessage
        var fired = false
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (!fired) { fired = true; onBaseMessage = prevHandler
                onResult(null, "Timed out waiting for glass") }
        }
        onBaseMessage = handler@{ type, body ->
            if (type == replyType) {
                if (fired) return@handler
                fired = true
                timeoutHandler.removeCallbacks(timeout)
                onBaseMessage = prevHandler
                val parsed = try {
                    parse(org.json.JSONObject(body))
                } catch (e: Exception) { null }
                if (parsed == null) onResult(null, "Bad reply payload")
                else onResult(parsed, null)
            } else prevHandler?.invoke(type, body)
        }
        timeoutHandler.postDelayed(timeout, timeoutMs)
        if (!sendPluginMessage("base", request, payload)) {
            timeoutHandler.removeCallbacks(timeout)
            if (!fired) {
                fired = true; onBaseMessage = prevHandler
                onResult(null, "Send failed")
            }
        }
    }

    fun queryWifiIp(onResult: (ip: String, ssid: String, error: String?) -> Unit) {
        if (!btConnected) {
            onResult("", "", "Glass not connected")
            return
        }
        val prevHandler = onBaseMessage
        var fired = false
        val timeout = Runnable {
            if (!fired) {
                fired = true
                onBaseMessage = prevHandler
                onResult("", "", "Timed out waiting for glass")
            }
        }
        val timeoutHandler = Handler(Looper.getMainLooper())
        onBaseMessage = handler@{ type, payload ->
            when (type) {
                "WIFI_IP" -> {
                    if (fired) return@handler
                    fired = true
                    timeoutHandler.removeCallbacks(timeout)
                    onBaseMessage = prevHandler
                    val ip = try {
                        org.json.JSONObject(payload).optString("ip", "")
                    } catch (_: Exception) { "" }
                    val ssid = try {
                        org.json.JSONObject(payload).optString("ssid", "")
                    } catch (_: Exception) { "" }
                    onResult(ip, ssid, null)
                }
                else -> prevHandler?.invoke(type, payload)
            }
        }
        timeoutHandler.postDelayed(timeout, 5_000L)
        if (!sendPluginMessage("base", "GET_WIFI_IP", "")) {
            timeoutHandler.removeCallbacks(timeout)
            if (!fired) {
                fired = true
                onBaseMessage = prevHandler
                onResult("", "", "Send failed")
            }
        }
    }

    private fun ensureConnectChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CONNECT_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CONNECT_CHANNEL_ID,
            "Connection status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Brief notification when the phone successfully connects to your glass."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun ensureLaunchChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(LAUNCH_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            LAUNCH_CHANNEL_ID,
            "Open on Phone",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Short-lived heads-up used to wake the screen and open an app when requested from the glass."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    // --- Bluetooth connection ---

    @SuppressLint("MissingPermission")
    fun connectBluetooth(device: BluetoothDevice) {
        // No-op if we're already connected to this exact device. The
        // auto-reconnect path in MainActivity.onCreate fires on every
        // fresh activity instance — including a tap on the foreground-
        // service notification — so without this guard, opening the app
        // via the notification would tear down a perfectly healthy BT
        // link just to re-establish it.
        if (btConnected && targetDevice?.address == device.address) {
            log("Already connected to ${device.name} — keeping link")
            // Re-emit state so a freshly-bound activity's UI reflects
            // reality without waiting for an actual change event.
            onConnectionChanged?.invoke(true)
            return
        }

        // Stop any reconnect loop targeting a previous device before starting a new one
        if (targetDevice != null && targetDevice != device) {
            log("Switching target from ${targetDevice?.name} to ${device.name}")
        }
        autoReconnect = false
        reconnectThread?.interrupt()
        reconnectThread = null
        try { bluetoothSocket?.close() } catch (_: IOException) {}
        bluetoothSocket = null
        outputStream = null
        btConnected = false

        targetDevice = device
        autoReconnect = true
        Thread { doConnect(device) }.start()
    }

    @SuppressLint("MissingPermission")
    private fun doConnect(device: BluetoothDevice): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        adapter?.cancelDiscovery()

        // Attempt 1: modern SDP-based connect
        log("Connecting to ${device.name}...")
        var socket: android.bluetooth.BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(APP_UUID)
            socket.connect()
        } catch (e: IOException) {
            log("SDP connect failed: ${e.message} — trying channel-1 fallback")
            try { socket?.close() } catch (_: IOException) {}
            socket = null
        }

        // Attempt 2: reflection-based channel-1 fallback
        if (socket == null || !socket.isConnected) {
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket = method.invoke(device, 1) as android.bluetooth.BluetoothSocket
                socket.connect()
                log("Connected via channel-1 fallback")
            } catch (e: Exception) {
                log("BT connect failed: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
                bluetoothSocket = null
                outputStream = null
                onConnectionChanged?.invoke(false)
                return false
            }
        }

        bluetoothSocket = socket
        outputStream = socket!!.outputStream
        btConnected = true
        // Reset idle clock — we're live again.
        disconnectedSinceMillis = 0L

        log("Connected to ${device.name}")
        onConnectionChanged?.invoke(true)
        pluginRouter?.notifyConnectionChanged(true)
        workerManager.onConnectionChanged(true)
        updateNotification("Connected to ${device.name}")
        // Weather: re-ship cached payload immediately and trigger a
        // fresh fetch in the background so the chip lights up on
        // reconnect without waiting for the next 30-min tick.
        shipCachedWeather()
        runWeatherFetchIfDue(force = false)
        // Glass auto-sends PLUGIN_LIST on socket accept, but if our
        // process restarted while the BT socket survived (e.g. an `adb
        // install` that didn't bring down the OS Bluetooth stack), we
        // miss that one-shot send and the on-phone PluginDirectory
        // stays empty. Always re-request after we know we're alive.
        // Raw command — wrapping in MSG: would surface this as a
        // user-visible notification on the glass.
        sendRaw(ProtocolCodec.encodePluginListReq())
        // Background battery sampling for the history graph. First
        // request fires after a brief delay so the heartbeat INFO can
        // populate lastGlassInfo first (we need a device id to file
        // the sample under).
        batteryPollHandler.removeCallbacks(batteryPollRunnable)
        batteryPollHandler.postDelayed(batteryPollRunnable, 5_000L)
        notifyConnectionSuccess(device.name ?: "Glass")

        wireNotificationListener()
        sendRaw(ProtocolCodec.encodeInfoReq())
        // Seed the Home clock card with our timezone. Phone is the source
        // of truth for Glass time so the two screens never drift.
        sendRaw(ProtocolCodec.encodeHomeTz(java.util.TimeZone.getDefault().id))

        startHeartbeat()
        startReaderThread()
        return true
    }

    fun disconnectBluetooth() {
        autoReconnect = false
        btConnected = false
        batteryPollHandler.removeCallbacks(batteryPollRunnable)
        reconnectThread?.interrupt()
        reconnectThread = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        try { outputStream?.close() } catch (_: IOException) {}
        try { bluetoothSocket?.close() } catch (_: IOException) {}
        readerThread?.interrupt()
        readerThread = null
        outputStream = null
        bluetoothSocket = null

        NotificationForwardingService.instance?.onNotificationForGlass = null
        pluginRouter?.notifyConnectionChanged(false)
        workerManager.onConnectionChanged(false)
        onConnectionChanged?.invoke(false)
        updateNotification("Disconnected")
        // User hit disconnect — start the 10-min idle clock.
        disconnectedSinceMillis = System.currentTimeMillis()
    }

    private fun onConnectionLost() {
        btConnected = false
        heartbeatThread?.interrupt()
        heartbeatThread = null
        try { outputStream?.close() } catch (_: IOException) {}
        try { bluetoothSocket?.close() } catch (_: IOException) {}
        outputStream = null
        bluetoothSocket = null

        pluginRouter?.notifyConnectionChanged(false)
        workerManager.onConnectionChanged(false)
        onConnectionChanged?.invoke(false)
        // Lost connection — start the 10-min idle clock. If auto-reconnect
        // brings us back before it fires, doConnect() resets it.
        disconnectedSinceMillis = System.currentTimeMillis()

        if (autoReconnect && running) startReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun startReconnect() {
        val device = targetDevice ?: return
        updateNotification("Reconnecting to ${device.name}...")
        log("Connection lost — auto-reconnecting...")

        reconnectThread = Thread {
            var delay = RECONNECT_DELAY_INITIAL
            while (autoReconnect && running && !btConnected) {
                try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
                if (!autoReconnect || !running) break
                log("Reconnecting (next retry in ${delay / 1000}s)...")
                if (doConnect(device)) return@Thread
                delay = (delay * 2).coerceAtMost(RECONNECT_DELAY_MAX)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startHeartbeat() {
        heartbeatThread?.interrupt()
        lastInboundMs = android.os.SystemClock.elapsedRealtime()
        heartbeatThread = Thread {
            try {
                while (btConnected && running) {
                    Thread.sleep(HEARTBEAT_INTERVAL)
                    if (!btConnected) break
                    val sinceInbound = android.os.SystemClock.elapsedRealtime() - lastInboundMs
                    if (sinceInbound > INBOUND_STALE_THRESHOLD) {
                        Log.i(TAG, "Heartbeat: no inbound for ${sinceInbound}ms, dead")
                        break
                    }
                    try {
                        sendRaw(ProtocolCodec.encodePing())
                        // Refresh glass battery / model / plugin info so the
                        // header card doesn't go stale while connected.
                        sendRaw(ProtocolCodec.encodeInfoReq())
                    } catch (e: IOException) {
                        Log.i(TAG, "Heartbeat failed")
                        break
                    }
                }
            } catch (_: InterruptedException) {}
            if (btConnected && running) onConnectionLost()
        }.apply { isDaemon = true; start() }
    }

    private fun startReaderThread() {
        readerThread = Thread {
            try {
                val reader = BufferedReader(
                    InputStreamReader(bluetoothSocket!!.inputStream, Charsets.UTF_8)
                )
                while (btConnected) {
                    val line = reader.readLine() ?: break
                    lastInboundMs = android.os.SystemClock.elapsedRealtime()
                    Log.d(TAG, "From Glass: $line")
                    handleMessage(ProtocolCodec.decode(line))
                }
            } catch (e: IOException) {
                if (btConnected) log("BT read error: ${e.message}")
            }
            if (running) onConnectionLost()
        }.apply { isDaemon = true; start() }
    }

    private fun handleMessage(msg: DecodedMessage) {
        when (msg) {
            is DecodedMessage.Plugin -> {
                // Dynamic-plugin directory messages are base-app-owned;
                // intercept before the hardcoded phone-side plugin router.
                when (msg.type) {
                    "SCHEMA_RESP" -> {
                        com.glasshole.phone.plugindir.PluginDirectory
                            .updateSchema(msg.pluginId, msg.payload)
                        com.glasshole.phone.AppLog.log(
                            "BT", "← ${msg.pluginId}:SCHEMA_RESP (${msg.payload.length} B)"
                        )
                        return
                    }
                    "CONFIG" -> {
                        com.glasshole.phone.plugindir.PluginDirectory
                            .updateConfig(msg.pluginId, msg.payload)
                        com.glasshole.phone.AppLog.log(
                            "BT", "← ${msg.pluginId}:CONFIG (${msg.payload.length} B)"
                        )
                        return
                    }
                }
                // Catch base-plugin messages early so the wallpaper
                // upload flow (and any other lightweight base round-
                // trip we add later) can subscribe without going
                // through the AIDL plugin router. workerManager +
                // pluginRouter still see the message but have no
                // "base" handler registered, so they're no-ops here.
                if (msg.pluginId == "base") {
                    try {
                        onBaseMessage?.invoke(msg.type, msg.payload)
                    } catch (e: Exception) {
                        Log.w(TAG, "onBaseMessage failed: ${e.message}")
                    }
                }

                // AI Assistant proxy: glass relies on the phone for the
                // outbound provider HTTP call. Intercept before the
                // workers/router dispatch so a registered worker
                // primitive for "ai" doesn't shadow this and so we
                // don't double-handle ASK in two places.
                if (msg.pluginId == "ai" && msg.type == "ASK") {
                    handleAiAssistantAsk(msg.payload)
                    return
                }

                // Dynamic workers get every non-directory PLUGIN message.
                // Hardcoded phone-side plugins (ChatPlugin etc. during the
                // migration) still receive via the legacy router — both
                // paths co-exist while we finish the refactor.
                workerManager.deliverMessage(msg.pluginId, msg.type, msg.payload)

                val pluginMsg = PluginMessage(msg.type, msg.payload)
                val routed = pluginRouter?.routeToPlugin(msg.pluginId, pluginMsg) ?: false
                if (!routed) {
                    log("Unrouted plugin message: ${msg.pluginId}:${msg.type}")
                } else {
                    com.glasshole.phone.AppLog.log(
                        "BT",
                        "← ${msg.pluginId}:${msg.type} (${msg.payload.length} B)"
                    )
                }
            }
            is DecodedMessage.PluginList -> {
                com.glasshole.phone.plugindir.PluginDirectory.updateList(msg.json)
                com.glasshole.phone.AppLog.log(
                    "BT", "← PLUGIN_LIST (${msg.json.length} B)"
                )
            }
            is DecodedMessage.Reply -> {
                log("Glass replied: ${msg.text}")
                onNotificationReply?.invoke(msg.text)
                handleGlassReply(msg.text)
            }
            is DecodedMessage.Info -> {
                val info = GlassInfo.fromJson(msg.json)
                lastGlassInfo = info
                log("Glass info: ${info.model} (battery: ${info.battery}%)")
                onGlassInfo?.invoke(info)
                // Heartbeat INFO already carries the battery percent —
                // log it so the history is fresh even if the
                // device-info activity has never been opened.
                if (info.battery in 0..100) {
                    val deviceId = currentDeviceId
                    if (deviceId.isNotEmpty()) {
                        com.glasshole.phone.widget.BatteryHistoryStore.get(this)
                            .add(deviceId, System.currentTimeMillis(), info.battery.toFloat())
                    }
                }
            }
            is DecodedMessage.DeviceInfo -> {
                onGlassDeviceInfo?.invoke(msg.json)
            }
            is DecodedMessage.BatteryInfo -> {
                onGlassBatteryInfo?.invoke(msg.json)
                // Persist for the history graph. The activity also
                // calls add() from its 5s in-app poll; both writers
                // hit the same per-device file.
                try {
                    val percent = org.json.JSONObject(msg.json)
                        .opt("percent")?.toString()?.toFloatOrNull()
                    val deviceId = currentDeviceId
                    if (percent != null && deviceId.isNotEmpty()) {
                        com.glasshole.phone.widget.BatteryHistoryStore.get(this)
                            .add(deviceId, System.currentTimeMillis(), percent)
                    }
                } catch (_: Exception) {}
            }
            is DecodedMessage.InstallAck -> {
                log("Install result: ${msg.status}")
                onInstallResult?.invoke(msg.status)
            }
            is DecodedMessage.ListPackages -> {
                onPackageList?.invoke(msg.json)
            }
            is DecodedMessage.UninstallAck -> {
                log("Uninstall ${msg.pkg}: ${msg.status}")
                onUninstallResult?.invoke(msg.pkg, msg.status)
            }
            is DecodedMessage.NotifDismiss -> {
                val listener = NotificationForwardingService.instance
                log("← NOTIF_DISMISS key=${msg.notifKey.take(40)}")
                if (listener != null) {
                    try {
                        listener.cancelNotification(msg.notifKey)
                    } catch (e: Exception) {
                        log("  cancelNotification failed: ${e.message}")
                    }
                }
            }
            is DecodedMessage.NotifAction -> {
                val listener = NotificationForwardingService.instance
                val hasReply = msg.replyText != null
                log("← NOTIF_ACTION key=${msg.notifKey.take(40)} id=${msg.actionId}${if (hasReply) " reply=\"${msg.replyText?.take(40)}\"" else ""}")
                if (listener == null) {
                    log("  listener not active — dropped")
                } else {
                    val ok = listener.invokeAction(msg.notifKey, msg.actionId, msg.replyText)
                    log("  ${if (ok) "✓ fired" else "✗ FAILED (no pending action)"}")
                }
            }
            is DecodedMessage.LiveCamUrl -> {
                log("Live cam URL: ${msg.url}")
                onLiveCamUrl?.invoke(msg.url)
            }
            is DecodedMessage.LiveCamErr -> {
                log("Live cam err: ${msg.reason}")
                onLiveCamErr?.invoke(msg.reason)
            }
            is DecodedMessage.LiveScreenUrl -> {
                log("Live screen URL: ${msg.url}")
                onLiveScreenUrl?.invoke(msg.url)
            }
            is DecodedMessage.LiveScreenErr -> {
                log("Live screen err: ${msg.reason}")
                onLiveScreenErr?.invoke(msg.reason)
            }
            is DecodedMessage.Pong -> { /* heartbeat alive */ }
            is DecodedMessage.Unknown -> {
                log("Glass: ${msg.raw}")
            }
            else -> {}
        }
    }

    // --- Send methods ---

    fun sendRaw(data: String): Boolean {
        val os = outputStream ?: return false
        return try {
            os.write(data.toByteArray(Charsets.UTF_8))
            os.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "BT send failed: ${e.message}")
            false
        }
    }

    fun sendToGlass(text: String): Boolean = sendRaw(ProtocolCodec.encodeMsg(text))

    fun sendPluginMessage(pluginId: String, type: String, payload: String): Boolean {
        val ok = sendRaw(ProtocolCodec.encodePlugin(pluginId, type, payload))
        com.glasshole.phone.AppLog.log(
            "BT",
            "→ $pluginId:$type (${payload.length} B) ${if (ok) "sent" else "FAILED"}"
        )
        return ok
    }

    fun requestGlassInfo() = sendRaw(ProtocolCodec.encodeInfoReq())

    /** Ask Glass for the extensive device-info dump (hardware, OS,
     *  network, advanced battery, storage, memory) used by the
     *  Glass Device Info page. Heavier than the heartbeat INFO so
     *  it's a separate command, polled only while that page is open. */
    fun requestDeviceInfo(): Boolean =
        sendRaw(ProtocolCodec.encodeDeviceInfoReq())

    /** Lighter-weight battery-only refresh — just the battery section
     *  of the device info, no kernel string / network scan / etc.
     *  Polled every few seconds while the device-info page is open
     *  to drive the live battery graph. */
    fun requestBatteryInfo(): Boolean =
        sendRaw(ProtocolCodec.encodeBatteryInfoReq())

    fun sendLiveCamStart(): Boolean = sendRaw(ProtocolCodec.encodeLiveCamStart())
    fun sendLiveCamStop(): Boolean = sendRaw(ProtocolCodec.encodeLiveCamStop())
    fun sendLiveScreenStart(): Boolean = sendRaw(ProtocolCodec.encodeLiveScreenStart())
    fun sendLiveScreenStop(): Boolean = sendRaw(ProtocolCodec.encodeLiveScreenStop())
    fun sendLiveScreenKeepAwake(enabled: Boolean): Boolean =
        sendRaw(ProtocolCodec.encodeLiveScreenKeepAwake(enabled))

    /** Ask Glass Home to clear its "already prompted" flag for the
     *  device-admin dialog so the next HomeActivity open re-asks. */
    fun sendResetHomeAdminPrompt(): Boolean =
        sendRaw(ProtocolCodec.encodeResetHomeAdminPrompt())

    /** Ask a plugin for its settings schema (JSON from its res/raw). */
    fun requestPluginSchema(pluginId: String): Boolean =
        sendRaw(ProtocolCodec.encodePlugin(pluginId, "SCHEMA_REQ", ""))

    /** Ask a plugin for its current saved config. */
    fun requestPluginConfig(pluginId: String): Boolean =
        sendRaw(ProtocolCodec.encodePlugin(pluginId, "CONFIG_READ", ""))

    /** Commit a partial or full config edit to a plugin. */
    fun writePluginConfig(pluginId: String, configJson: String): Boolean =
        sendRaw(ProtocolCodec.encodePlugin(pluginId, "CONFIG_WRITE", configJson))

    // --- APK manager ---

    fun requestPackageList(): Boolean = sendRaw(ProtocolCodec.encodeListPackagesReq())

    fun requestUninstall(pkg: String): Boolean = sendRaw(ProtocolCodec.encodeUninstall(pkg))

    /**
     * Send an APK to glass in chunks. Runs synchronously on the calling thread —
     * callers must NOT invoke from the UI thread. Returns true if all bytes were
     * written; does not wait for INSTALL_ACK.
     */
    fun sendApk(
        filename: String,
        inputBytes: ByteArray
    ): Boolean {
        if (!btConnected) return false
        val md5 = try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            digest.update(inputBytes)
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            log("APK MD5 failed: ${e.message}")
            return false
        }

        val total = inputBytes.size.toLong()
        log("Sending APK: $filename ($total bytes, md5=$md5)")
        if (!sendRaw(ProtocolCodec.encodeInstallStart(filename, total, md5))) return false

        // ~48 KB of raw payload per chunk keeps the base64 line under BT's line-read ceiling.
        val chunkSize = 48 * 1024
        var offset = 0
        while (offset < inputBytes.size) {
            if (!btConnected) {
                log("BT disconnected during install")
                return false
            }
            val end = (offset + chunkSize).coerceAtMost(inputBytes.size)
            val chunk = inputBytes.copyOfRange(offset, end)
            val base64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
            if (!sendRaw(ProtocolCodec.encodeInstallData(base64))) return false
            offset = end
            onInstallProgress?.invoke(offset.toLong(), total)
        }
        return sendRaw(ProtocolCodec.encodeInstallEnd())
    }

    // --- Notification forwarding ---

    private fun wireNotificationListener() {
        val listener = NotificationForwardingService.instance
        if (listener == null) {
            log("WARNING: Notification listener not active!")
            log("Go to Settings > Notification access > enable GlassHole")
            return
        }
        // Actions-aware path — the listener builds the full JSON (app/title/
        // text/icon/key/actions) and hands it to us ready to ship.
        listener.onNotifWithActions = { json ->
            log("Forwarding notification with actions (${json.length} B)")
            sendRaw(ProtocolCodec.encodeNotif(json))
        }
        listener.onNotifRemoved = { key ->
            sendRaw(ProtocolCodec.encodeNotifRemoved(key))
        }
        log("Notification forwarding active")
    }

    private fun handleGlassReply(text: String) {
        val listener = NotificationForwardingService.instance ?: run {
            log("Cannot send reply: notification listener not active")
            return
        }
        val sent = listener.sendReply(text)
        if (sent) {
            log("Reply sent via Direct Reply: $text")
        } else {
            log("Direct Reply failed — no notification to reply to")
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }
}
