package com.glasshole.glassee1

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
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

    // Plugin callbacks (AIDL) - fallback; primary is broadcast
    private val pluginCallbacks = ConcurrentHashMap<String, IGlassPluginCallback>()

    // Bound plugin service connections (kept alive so their onCreate registers their receivers)
    private val pluginConnections = mutableMapOf<String, ServiceConnection>()

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

        // Register static reference so manifest receiver can forward to us
        PluginMessageReceiver.btService = this

        // Listen for plugin reinstalls so we rebind their AIDL callbacks.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        try { registerReceiver(packageReceiver, filter) } catch (_: Exception) {}

        discoverAndBindPlugins()
        startListening()
    }

    override fun onDestroy() {
        running = false
        instance = null
        PluginMessageReceiver.btService = null
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
        for ((_, conn) in pluginConnections) {
            try { unbindService(conn) } catch (_: Exception) {}
        }
        pluginConnections.clear()
        closeAll()
        wakeLock?.release()
        wakeLock = null
        stopForeground(true)
        super.onDestroy()
    }

    /** Watches for plugin installs/replacements and re-runs discovery. */
    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val pkg = intent.data?.schemeSpecificPart ?: return
            // Rediscover on any glasshole-family install — plugin APKs
            // (com.glasshole.plugin.*) and plugin-hosting apps like the
            // merged Stream Player (com.glasshole.streamplayer.*).
            if (!pkg.startsWith("com.glasshole.")) return
            Log.i(TAG, "Package change ($action): $pkg — rediscovering plugins")
            android.os.Handler().postDelayed({ discoverAndBindPlugins() }, 500)
        }
    }

    private fun discoverAndBindPlugins() {
        val intent = Intent(GlassPluginConstants.ACTION_GLASS_PLUGIN)
        val resolved = packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        Log.i(TAG, "Discovered ${resolved.size} glass plugin service(s)")

        for (info in resolved) {
            val serviceInfo = info.serviceInfo ?: continue
            val metaData = serviceInfo.metaData
            val pluginId = metaData?.getString(GlassPluginConstants.META_PLUGIN_ID) ?: continue

            if (pluginConnections.containsKey(pluginId)) continue
            bindPlugin(pluginId, serviceInfo.packageName, serviceInfo.name)
        }
    }

    private fun bindPlugin(pluginId: String, packageName: String, className: String) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.i(TAG, "Glass plugin connected: $pluginId")
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.i(TAG, "Glass plugin disconnected: $pluginId — will retry")
                try { unbindService(this) } catch (_: Exception) {}
                pluginConnections.remove(pluginId)
                android.os.Handler().postDelayed({
                    if (!pluginConnections.containsKey(pluginId)) {
                        bindPlugin(pluginId, packageName, className)
                    }
                }, 750)
            }
        }

        val bindIntent = Intent(GlassPluginConstants.ACTION_GLASS_PLUGIN).apply {
            setClassName(packageName, className)
        }
        try {
            if (bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
                pluginConnections[pluginId] = connection
                Log.i(TAG, "Bound to glass plugin: $pluginId")
            } else {
                Log.w(TAG, "Failed to bind glass plugin: $pluginId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding glass plugin $pluginId: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — service continues running")
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

    // --- Plugin registration (AIDL fallback) ---

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
            // API 19: use ACTION_BATTERY_CHANGED sticky broadcast
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
                    line == "LIST_PACKAGES_REQ" -> {
                        sendPackageList()
                    }
                    line.startsWith("UNINSTALL:") -> {
                        val pkg = line.removePrefix("UNINSTALL:")
                        handleUninstall(pkg)
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

        // Primary: broadcast (EE1 uses broadcasts for plugin communication)
        val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_FROM_PHONE).apply {
            putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, pluginId)
            putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, type)
            putExtra(GlassPluginConstants.EXTRA_PAYLOAD, payload)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast plugin message: $pluginId:$type")

        // Fallback: AIDL callback if registered
        val callback = pluginCallbacks[pluginId]
        if (callback != null) {
            try {
                callback.onMessageFromPhone(GlassPluginMessage(type, payload))
            } catch (e: Exception) {
                Log.e(TAG, "Plugin callback failed for '$pluginId': ${e.message}")
                pluginCallbacks.remove(pluginId)
            }
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
            "SHOW_CONNECT_NOTIF" -> showConnectToast()
            else -> Log.d(TAG, "Unknown base message: $type")
        }
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
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            routeToPlugin("device", "SLEEP_NOW", "")
        }, 200L)
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
        // Prefer a real GDK timeline card — it slots into the user's timeline
        // without hijacking the display. Fall back to the legacy full-screen
        // activity if the GDK isn't present on this device.
        val parsed = parseForwardedMessage(message)
        val body = buildCardBody(parsed)
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

            // GDK timeline cards are static — they can't host interactive
            // actions. Only take the timeline path when we have nothing to
            // interact with. Otherwise go straight to the full-screen Activity.
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
                            val status = triggerInstall(tempFile)
                            sendInstallAck(status)
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

        // Path 3: fall back to ACTION_VIEW intent. Glass XE intercepts this with
        // PackageInstallerHandlerActivity which refuses if Unknown Sources is off.
        return try {
            val apkUri = Uri.fromFile(apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
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
            // Skip disabled apps and glass system packages, focus on user-visible stuff
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
        // Protect the base app from being uninstalled remotely
        if (pkg == packageName) {
            sendUninstallAck(pkg, "refused:self")
            return
        }

        // Path 1: pm uninstall directly (needs DELETE_PACKAGES — usually denied)
        val pmResult = tryShell(arrayOf("pm", "uninstall", pkg))
        if (pmResult.success) {
            Log.i(TAG, "Uninstalled via pm: $pkg")
            sendUninstallAck(pkg, "success:pm")
            return
        }

        // Path 2: su pm uninstall (only works on rooted Glass)
        val suResult = trySuShell("pm uninstall $pkg")
        if (suResult.success) {
            Log.i(TAG, "Uninstalled via su: $pkg")
            sendUninstallAck(pkg, "success:su")
            return
        }

        // Path 3: fall back to ACTION_DELETE intent. Note: Glass XE intercepts
        // this with PackageInstallerHandlerActivity which silently refuses
        // if Unknown Sources is not enabled on the headset.
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
        try {
            outputStream?.write("UNINSTALL_ACK:$pkg:$status\n".toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (_: IOException) {}
    }
}
