package com.glasshole.glassxe

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
        // later; if our process gets killed inside that window the restore
        // never runs and the setting is stuck at 1000ms forever. Anything
        // below 2s on startup is the orphaned-override value (phone-side
        // slider min is 2s).
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

        // Launcher flavor: bypass Glass's HOME chooser by re-launching
        // HomeActivity ourselves on every screen-on. Glass XE's custom
        // ResolverActivity (com.google.android.glass.systemui) doesn't
        // expose an "Always" button, so the chooser would otherwise pop up
        // on every wake. The launch lands on the existing singleTask
        // HomeActivity instance and dismisses the chooser.
        if (BuildConfig.FLAVOR == "launcher") {
            try {
                registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            } catch (e: Exception) {
                Log.w(TAG, "screenOnReceiver register failed: ${e.message}")
            }
        }

        startListening()
    }

    // Set whenever we route a non-base plugin message that may launch its
    // own activity (e.g. stream's PLAY_URL → MainActivity → PlayerActivity).
    // The screen-on receiver checks this so it doesn't race the plugin's
    // launch with our own HomeActivity relaunch when the wake came from a
    // plugin holding ACQUIRE_CAUSES_WAKEUP.
    @Volatile private var lastPluginRouteMs: Long = 0L
    private val PLUGIN_LAUNCH_QUIET_MS = 4_000L

    private val screenOnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            val sinceRoute = android.os.SystemClock.elapsedRealtime() - lastPluginRouteMs
            if (sinceRoute < PLUGIN_LAUNCH_QUIET_MS) {
                Log.i(TAG, "SCREEN_ON: skipping HomeActivity (plugin route ${sinceRoute}ms ago)")
                return
            }
            try {
                startActivity(
                    Intent(this@BluetoothListenerService,
                        com.glasshole.glassxe.home.HomeActivity::class.java).apply {
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

    fun sendNotifDismiss(notifKey: String): Boolean {
        val os = outputStream ?: return false
        return try {
            val escaped = notifKey.replace("\\", "\\\\").replace("\n", "\\n")
            os.write("NOTIF_DISMISS:$escaped\n".toByteArray(Charsets.UTF_8))
            os.flush()
            Log.i(TAG, "NOTIF_DISMISS sent: $notifKey")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Send notif dismiss failed: ${e.message}")
            false
        }
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
            "GET_STATE" -> sendBaseStateToPhone()
            "SHOW_CONNECT_NOTIF" -> showConnectToast()
            else -> Log.d(TAG, "Unknown base message: $type")
        }
    }

    private fun maybeWakeForNavUpdate() {
        val prefs = getSharedPreferences(BaseSettings.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(BaseSettings.KEY_NAV_WAKE_ON_UPDATE, false)) return
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
        }.toString()
        sendPluginMessage("base", "STATE", json)
    }

    private fun sendInfo() {
        val os = outputStream ?: return
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
            }
            os.write("INFO:$json\n".toByteArray(Charsets.UTF_8))
            os.flush()
            // Don't piggyback PLUGIN_LIST onto every INFO response — phone
            // heartbeats every 10s and the directory is ~8KB. Sent once on
            // connect (handleConnection) instead.
        } catch (e: Exception) {
            Log.e(TAG, "Send info failed: ${e.message}")
        }
    }

    private fun sendPluginList() {
        val os = outputStream ?: return
        try {
            val entries = com.glasshole.glass.sdk.PluginDirectoryScanner.scan(this)
            val json = com.glasshole.glass.sdk.PluginDirectoryScanner.toJson(entries)
            val escaped = json.replace("\\", "\\\\").replace("\n", "\\n")
            os.write("PLUGIN_LIST:$escaped\n".toByteArray(Charsets.UTF_8))
            os.flush()
            Log.i(TAG, "PLUGIN_LIST sent (${entries.size} plugins)")
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
                    @Suppress("DEPRECATION")
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
                        Log.e(TAG, "No Bluetooth adapter")
                        return@Thread
                    }

                    if (!adapter.isEnabled) {
                        Log.w(TAG, "Bluetooth is off — attempting to enable")
                        try { adapter.enable() } catch (_: Exception) {}
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
                        com.glasshole.glassxe.home.NotificationStore.put(json)
                        showRichNotification(json)
                    }
                    line.startsWith("NOTIF_REMOVED:") -> {
                        val key = line.removePrefix("NOTIF_REMOVED:")
                            .replace("\\n", "\n").replace("\\\\", "\\")
                        com.glasshole.glassxe.home.NotificationStore.remove(key)
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
                    line.startsWith("HOME_TZ:") -> {
                        val tz = line.removePrefix("HOME_TZ:").trim()
                        if (tz.isNotEmpty()) {
                            com.glasshole.glassxe.home.HomePrefs.setTimezone(this, tz)
                        }
                    }
                    line == "HOME_RESET_ADMIN_PROMPT" -> {
                        com.glasshole.glassxe.home.HomePrefs.resetAdminPrompt(this)
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
        // SCREEN_ON receiver won't relaunch HomeActivity on top of the
        // activity the plugin is about to start (e.g. stream's PLAY_URL
        // wakes the screen via wakelock then opens MainActivity).
        lastPluginRouteMs = android.os.SystemClock.elapsedRealtime()

        // Prefer AIDL when the plugin's been bound — GlassPluginService
        // also registers a broadcast receiver in its onCreate, so dispatching
        // both paths would deliver the message twice (the stream plugin
        // launching the player activity twice was the symptom). Fall back
        // to broadcast only if AIDL isn't available or the call throws.
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

        lastPluginRouteMs = android.os.SystemClock.elapsedRealtime()
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
            lastPluginRouteMs = android.os.SystemClock.elapsedRealtime()
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
        try {
            outputStream?.write("UNINSTALL_ACK:$pkg:$status\n".toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (_: IOException) {}
    }
}
