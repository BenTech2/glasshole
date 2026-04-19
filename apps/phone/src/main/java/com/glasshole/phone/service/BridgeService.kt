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
        private const val HEARTBEAT_INTERVAL = 30_000L
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
    @Volatile private var running = false
    private var readerThread: Thread? = null
    private var heartbeatThread: Thread? = null

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

    val isConnected: Boolean get() = btConnected

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
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
    }

    override fun onDestroy() {
        running = false
        instance = null
        autoReconnect = false
        idleHandler.removeCallbacks(idleCheckRunnable)
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
        updateNotification("Connected to ${device.name}")
        notifyConnectionSuccess(device.name ?: "Glass")

        wireNotificationListener()
        sendRaw(ProtocolCodec.encodeInfoReq())

        startHeartbeat()
        startReaderThread()
        return true
    }

    fun disconnectBluetooth() {
        autoReconnect = false
        btConnected = false
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
        heartbeatThread = Thread {
            try {
                while (btConnected && running) {
                    Thread.sleep(HEARTBEAT_INTERVAL)
                    if (!btConnected) break
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
            is DecodedMessage.Reply -> {
                log("Glass replied: ${msg.text}")
                onNotificationReply?.invoke(msg.text)
                handleGlassReply(msg.text)
            }
            is DecodedMessage.Info -> {
                val info = GlassInfo.fromJson(msg.json)
                log("Glass info: ${info.model} (battery: ${info.battery}%)")
                onGlassInfo?.invoke(info)
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
