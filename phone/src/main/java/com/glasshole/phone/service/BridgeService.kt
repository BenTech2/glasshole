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
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
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
    }

    override fun onDestroy() {
        running = false
        instance = null
        autoReconnect = false
        reconnectThread?.interrupt()
        reconnectThread = null
        disconnectBluetooth()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GlassHole")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("GlassHole")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
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

        log("Connected to ${device.name}")
        onConnectionChanged?.invoke(true)
        pluginRouter?.notifyConnectionChanged(true)
        updateNotification("Connected to ${device.name}")

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
