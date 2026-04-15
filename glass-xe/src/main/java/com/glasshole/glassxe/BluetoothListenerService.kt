package com.glasshole.glassxe

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.IntentFilter
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
        val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val SERVICE_NAME = "GlassHole"
        private const val FOREGROUND_ID = 1

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
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Plugin callbacks (AIDL)
    private val pluginCallbacks = ConcurrentHashMap<String, IGlassPluginCallback>()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothListenerService = this@BluetoothListenerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true

        @Suppress("DEPRECATION")
        val notification = Notification.Builder(this)
            .setContentTitle("GlassHole")
            .setContentText("Listening for phone connection")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_ID, notification)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassHole::BT")
        wakeLock?.acquire()

        startListening()
    }

    override fun onDestroy() {
        running = false
        instance = null
        closeAll()
        wakeLock?.release()
        wakeLock = null
        stopForeground(true)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed -- service continues running")
        val restartIntent = Intent(this, BluetoothListenerService::class.java)
        startService(restartIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun closeAll() {
        try { outputStream?.close() } catch (_: IOException) {}
        try { clientSocket?.close() } catch (_: IOException) {}
        try { serverSocket?.close() } catch (_: IOException) {}
        outputStream = null
        clientSocket = null
        serverSocket = null
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
        val os = outputStream ?: return false
        return try {
            val escaped = message.replace("\\", "\\\\").replace("\n", "\\n")
            os.write("REPLY:$escaped\n".toByteArray(Charsets.UTF_8))
            os.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Send reply failed: ${e.message}")
            false
        }
    }

    fun sendPluginMessage(pluginId: String, type: String, payload: String): Boolean {
        val os = outputStream ?: return false
        return try {
            val escaped = payload.replace("\\", "\\\\").replace("\n", "\\n")
            os.write("PLUGIN:$pluginId:$type:$escaped\n".toByteArray(Charsets.UTF_8))
            os.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Send plugin message failed: ${e.message}")
            false
        }
    }

    fun sendNotifAction(notifKey: String, actionId: String, replyText: String? = null): Boolean {
        val os = outputStream ?: return false
        return try {
            val obj = JSONObject().apply {
                put("key", notifKey)
                put("id", actionId)
                if (replyText != null) put("text", replyText)
            }
            val escaped = obj.toString().replace("\\", "\\\\").replace("\n", "\\n")
            os.write("NOTIF_ACTION:$escaped\n".toByteArray(Charsets.UTF_8))
            os.flush()
            Log.i(TAG, "NOTIF_ACTION sent: $actionId")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Send notif action failed: ${e.message}")
            false
        }
    }

    fun playStreamLocally(url: String) {
        val payload = JSONObject().apply { put("url", url) }.toString()
        routeToPlugin("stream", "PLAY_URL", payload)
    }

    private fun sendInfo() {
        val os = outputStream ?: return
        try {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val battery = if (scale > 0) (level * 100) / scale else -1
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val charging = plugged != 0

            val json = JSONObject().apply {
                put("battery", battery)
                put("charging", charging)
                put("model", Build.MODEL)
                put("android", Build.VERSION.RELEASE)
                put("serial", Build.SERIAL)
            }
            os.write("INFO:$json\n".toByteArray(Charsets.UTF_8))
            os.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send info failed: ${e.message}")
        }
    }

    // --- BT Server ---

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

                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                    clientSocket = serverSocket?.accept()
                    try { serverSocket?.close() } catch (_: IOException) {}
                    serverSocket = null

                    outputStream = clientSocket?.outputStream
                    Log.d(TAG, "Phone connected!")
                    messageListener?.onConnectionStateChanged(true)
                    notifyPluginsConnectionChanged(true)

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
                        showRichNotification(json)
                    }
                    line.startsWith("MSG:") -> {
                        val message = line.removePrefix("MSG:")
                            .replace("\\n", "\n").replace("\\\\", "\\")
                        lastMessage = message
                        messageListener?.onMessageReceived(message)
                        showNotification(message)
                    }
                    line == "PING" -> {
                        try {
                            outputStream?.write("PONG\n".toByteArray(Charsets.UTF_8))
                            outputStream?.flush()
                        } catch (_: IOException) {}
                    }
                    line == "INFO_REQ" -> {
                        sendInfo()
                    }
                    line.startsWith("INSTALL:") -> {
                        handleInstall(line, reader)
                    }
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
        if (pluginId == "base") {
            handleBaseMessage(type, payload)
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
                putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, pluginId)
                putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, type)
                putExtra(GlassPluginConstants.EXTRA_PAYLOAD, payload)
            }
            sendBroadcast(intent)
            Log.d(TAG, "Broadcast plugin message: $pluginId:$type")
        }
    }

    private fun handleBaseMessage(type: String, payload: String) {
        when (type) {
            "SET_AUTO_START" -> {
                val enabled = try {
                    JSONObject(payload).optBoolean("enabled", true)
                } catch (_: Exception) { true }
                val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
                prefs.edit().putBoolean(BaseSettings.KEY_AUTO_START, enabled).apply()
                Log.i(TAG, "Auto-start ${if (enabled) "enabled" else "disabled"}")
                sendBaseStateToPhone()
            }
            "GET_STATE" -> sendBaseStateToPhone()
            else -> Log.d(TAG, "Unknown base message: $type")
        }
    }

    private fun sendBaseStateToPhone() {
        val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
        val json = JSONObject().apply {
            put("autoStart", prefs.getBoolean(BaseSettings.KEY_AUTO_START, true))
        }.toString()
        sendPluginMessage("base", "STATE", json)
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
        // Prefer a GDK timeline card so notifications slot into the home
        // carousel instead of hijacking the display. Fall back to the legacy
        // full-screen activity on devices without the GDK.
        val parsed = parseForwardedMessage(message)
        val body = if (parsed.title.isNotEmpty()) "${parsed.title}\n${parsed.text}" else parsed.text
        val footnote = parsed.app.ifEmpty { null }
        if (TimelineCard.insertText(this, body, footnote)) return

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

            if (!hasActions) {
                val cardBody = if (title.isNotEmpty() && text.isNotEmpty()) "$title\n$text"
                               else if (title.isNotEmpty()) title else text
                if (TimelineCard.insertText(this, cardBody, app.ifEmpty { null })) return
            }

            val picture = obj.optString("picture", "")
            val intent = Intent(this, NotificationDisplayActivity::class.java).apply {
                putExtra("app", app)
                putExtra("title", title)
                putExtra("text", text)
                putExtra("icon", icon)
                putExtra("picture", picture)
                putExtra("key", key)
                if (hasActions) putExtra("actions", actions!!.toString())
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
                            triggerInstall(tempFile)
                            sendInstallAck("success")
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

    private fun triggerInstall(apkFile: java.io.File) {
        try {
            @Suppress("DEPRECATION")
            val apkUri = android.net.Uri.fromFile(apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Install trigger failed: ${e.message}")
        }
    }

    private fun sendInstallAck(status: String) {
        try {
            outputStream?.write("INSTALL_ACK:$status\n".toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (_: IOException) {}
    }
}
