package com.glasshole.glassee2

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
import android.content.ServiceConnection
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

    /** Send a notification action invocation back to the phone. */
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
            "GET_STATE" -> sendBaseStateToPhone()
            "SHOW_CONNECT_NOTIF" -> showConnectToast()
            else -> Log.d(TAG, "Unknown base message: $type")
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
        }.toString()
        sendPluginMessage("base", "STATE", json)
    }

    private fun sendInfo() {
        val os = outputStream ?: return
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
                    val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                    val adapter = manager.adapter ?: run {
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
                    line == "LIST_PACKAGES_REQ" -> {
                        sendPackageList()
                    }
                    line.startsWith("UNINSTALL:") -> {
                        handleUninstall(line.removePrefix("UNINSTALL:"))
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
        // Base-app-owned settings (not routed to a plugin APK). Handled
        // inline so these features work on a vanilla glasshole install
        // without requiring any plugin package to be present.
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

        // Path 3: ACTION_VIEW intent via FileProvider. EE2 with REQUEST_INSTALL_PACKAGES
        // granted will show the system installer prompt on the glass display.
        return try {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(installIntent)
            "prompt_shown:needs_unknown_sources"
        } catch (e: Exception) {
            Log.e(TAG, "Install trigger failed: ${e.message}")
            "failed:${e.message}"
        }
    }

    private fun sendInstallAck(status: String) {
        try {
            outputStream?.write("INSTALL_ACK:$status\n".toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (_: IOException) {}
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
        try {
            outputStream?.write("LIST_PACKAGES:$arr\n".toByteArray(Charsets.UTF_8))
            outputStream?.flush()
            Log.i(TAG, "Sent package list (${arr.length()} entries)")
        } catch (e: IOException) {
            Log.e(TAG, "Send package list failed: ${e.message}")
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
        try {
            outputStream?.write("UNINSTALL_ACK:$pkg:$status\n".toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (_: IOException) {}
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
