package com.glasshole.plugin.devtools.glass

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * On-glass dev panel. Shows the device's Wi-Fi IP / SSID and current
 * ADB on/off state, with three action buttons:
 *
 *   1. Toggle ADB           — flips Settings.Global.ADB_ENABLED.
 *                              Needs WRITE_SECURE_SETTINGS granted via
 *                              `adb shell pm grant ... WRITE_SECURE_SETTINGS`
 *                              (declared in the manifest; signature-level
 *                              so adb is the only way to grant it on a
 *                              stock build).
 *   2. Open Developer opts  — fires the system settings intent so the
 *                              user can manually flip anything else.
 *   3. Try wireless ADB     — best-effort reflection into
 *                              SystemProperties.set("service.adb.tcp.port",
 *                              "5555"). Almost always fails on stock EE2
 *                              (sepolicy blocks non-system UIDs from
 *                              writing system properties); we run it
 *                              anyway and report the SecurityException so
 *                              the user knows where they stand.
 *
 * Swipe-down (BACK key on EE1/XE, hardware back on EE2) exits.
 */
class DevToolsActivity : Activity() {

    private lateinit var wifiStatusText: TextView
    private lateinit var adbStatusText: TextView
    private lateinit var resultText: TextView
    private lateinit var sshStatusText: TextView
    private lateinit var sshConnectText: TextView
    private lateinit var sshPasswordText: TextView
    private lateinit var sshToggleButton: Button
    private lateinit var sshRegenPasswordButton: Button
    private lateinit var rootStatusText: TextView
    private lateinit var grantSecureSettingsButton: Button
    private lateinit var enableWirelessAdbButton: Button
    private lateinit var customRootCommandInput: EditText
    private lateinit var runCustomRootButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devtools)

        wifiStatusText = findViewById(R.id.wifiStatusText)
        adbStatusText = findViewById(R.id.adbStatusText)
        resultText = findViewById(R.id.resultText)
        sshStatusText = findViewById(R.id.sshStatusText)
        sshConnectText = findViewById(R.id.sshConnectText)
        sshPasswordText = findViewById(R.id.sshPasswordText)
        sshToggleButton = findViewById(R.id.sshToggleButton)
        sshRegenPasswordButton = findViewById(R.id.sshRegenPasswordButton)

        findViewById<Button>(R.id.toggleAdbButton).setOnClickListener { toggleAdb() }
        findViewById<Button>(R.id.openDevOptionsButton).setOnClickListener { openDevOptions() }
        findViewById<Button>(R.id.tryWirelessAdbButton).setOnClickListener { tryWirelessAdb() }

        sshToggleButton.setOnClickListener { toggleSshd() }
        sshRegenPasswordButton.setOnClickListener { regenSshPassword() }

        rootStatusText = findViewById(R.id.rootStatusText)
        grantSecureSettingsButton = findViewById(R.id.grantSecureSettingsButton)
        enableWirelessAdbButton = findViewById(R.id.enableWirelessAdbButton)
        customRootCommandInput = findViewById(R.id.customRootCommandInput)
        runCustomRootButton = findViewById(R.id.runCustomRootButton)
        grantSecureSettingsButton.setOnClickListener { grantSecureSettingsViaRoot() }
        enableWirelessAdbButton.setOnClickListener { enableWirelessAdbViaRoot() }
        runCustomRootButton.setOnClickListener { runCustomRoot() }
        detectRoot()

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        wifiStatusText.text = wifiStatusLine()
        adbStatusText.text = "ADB: ${if (isAdbEnabled()) "on" else "off"}"
        refreshSshSection()
    }

    private fun refreshSshSection() {
        val mgr = DevToolsGlassPluginService.sshd
        if (mgr == null) {
            sshStatusText.text = "SSH: plugin service not bound"
            sshConnectText.text = ""
            sshPasswordText.text = ""
            sshToggleButton.isEnabled = false
            sshRegenPasswordButton.isEnabled = false
            return
        }
        if (!mgr.isSupported) {
            sshStatusText.text = "SSH: requires Android 8.0+"
            sshConnectText.text = ""
            sshPasswordText.text = ""
            sshToggleButton.isEnabled = false
            sshRegenPasswordButton.isEnabled = false
            return
        }
        sshToggleButton.isEnabled = true
        sshRegenPasswordButton.isEnabled = true
        if (mgr.isRunning) {
            sshStatusText.text = "SSH: running on port ${SshdManager.DEFAULT_PORT}"
            val ip = wifiIp()
            sshConnectText.text = if (ip != null) {
                "ssh glass@$ip -p ${SshdManager.DEFAULT_PORT}"
            } else "Connect Wi-Fi to see the SSH URL"
            sshPasswordText.text = "Password: ${mgr.password()}"
            sshToggleButton.text = "Stop SSH server"
        } else {
            sshStatusText.text = "SSH: stopped"
            sshConnectText.text = ""
            sshPasswordText.text = ""
            sshToggleButton.text = "Start SSH server"
        }
    }

    private fun toggleSshd() {
        val mgr = DevToolsGlassPluginService.sshd ?: return
        if (mgr.isRunning) {
            mgr.stop()
            resultText.text = "SSH server stopped"
        } else {
            val err = mgr.start()
            resultText.text = if (err == null) "SSH server started" else "SSH start: $err"
        }
        refreshSshSection()
    }

    private fun regenSshPassword() {
        val mgr = DevToolsGlassPluginService.sshd ?: return
        mgr.regeneratePassword()
        resultText.text = "Password regenerated (SSH server stopped — start to apply)"
        refreshSshSection()
    }

    /** Detects root once at activity-open. Magisk/SuperSU shows a
     *  permission prompt — once granted the buttons light up. */
    private fun detectRoot() {
        rootStatusText.text = "Root: checking…"
        RootHelper.detect { available ->
            if (available) {
                rootStatusText.text = "Root: available ✓"
                grantSecureSettingsButton.isEnabled = true
                enableWirelessAdbButton.isEnabled = true
                customRootCommandInput.isEnabled = true
                runCustomRootButton.isEnabled = true
            } else {
                rootStatusText.text = "Root: not available (or denied)"
                grantSecureSettingsButton.isEnabled = false
                enableWirelessAdbButton.isEnabled = false
                customRootCommandInput.isEnabled = false
                runCustomRootButton.isEnabled = false
            }
        }
    }

    private fun grantSecureSettingsViaRoot() {
        resultText.text = "Granting WRITE_SECURE_SETTINGS…"
        RootHelper.run(
            "pm grant ${packageName} android.permission.WRITE_SECURE_SETTINGS"
        ) { r ->
            resultText.text = if (r.ok) {
                refresh() // re-check ADB toggle visibility
                "WRITE_SECURE_SETTINGS granted ✓\n" +
                    "ADB toggle + wireless ADB buttons now work."
            } else {
                "Grant failed (exit ${r.exitCode}):\n" +
                    "${r.stderr.ifEmpty { r.stdout }.take(400)}"
            }
        }
    }

    /** Sets service.adb.tcp.port to 5555 and restarts adbd so the
     *  user can `adb connect <ip>:5555`. */
    private fun enableWirelessAdbViaRoot() {
        resultText.text = "Enabling wireless ADB…"
        // && stop adbd && start adbd restarts the daemon to pick up
        // the new port. Settings.Global.ADB_ENABLED stays as-is so the
        // glass still allows ADB connections in general.
        RootHelper.run(
            "setprop service.adb.tcp.port 5555 && stop adbd && start adbd"
        ) { r ->
            val ip = wifiIp() ?: "<glass-ip>"
            resultText.text = if (r.ok) {
                "Wireless ADB on. From your dev machine:\n" +
                    "adb connect $ip:5555"
            } else {
                "Failed (exit ${r.exitCode}):\n" +
                    "${r.stderr.ifEmpty { r.stdout }.take(400)}"
            }
        }
    }

    private fun runCustomRoot() {
        val cmd = customRootCommandInput.text.toString().trim()
        if (cmd.isEmpty()) return
        resultText.text = "$ $cmd"
        RootHelper.run(cmd) { r ->
            val body = buildString {
                if (r.stdout.isNotEmpty()) {
                    append(r.stdout.take(600))
                }
                if (r.stderr.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("[stderr] ").append(r.stderr.take(400))
                }
                if (isEmpty()) append("(no output)")
                append("\n— exit ").append(r.exitCode)
            }
            resultText.text = body
        }
    }

    private fun wifiStatusLine(): String {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo
            @Suppress("DEPRECATION")
            val ipRaw = info?.ipAddress ?: 0
            if (ipRaw == 0) {
                "Wi-Fi: not connected"
            } else {
                val ip = Formatter.formatIpAddress(ipRaw)
                @Suppress("DEPRECATION")
                val ssid = info?.ssid.orEmpty().trim('"')
                "SSID: $ssid\nIP: $ip"
            }
        } catch (e: Exception) {
            "Wi-Fi: error (${e.message})"
        }
    }

    private fun isAdbEnabled(): Boolean = try {
        Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    } catch (_: Exception) { false }

    /** Flip Settings.Global.ADB_ENABLED. WRITE_SECURE_SETTINGS is in
     *  the manifest but must be adb-granted; we surface the failure
     *  rather than silently no-op'ing. */
    private fun toggleAdb() {
        val target = if (isAdbEnabled()) 0 else 1
        try {
            Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, target)
            resultText.text = "ADB flipped to ${if (target == 1) "on" else "off"}"
            refresh()
        } catch (e: SecurityException) {
            resultText.text = "Can't toggle: WRITE_SECURE_SETTINGS not granted.\n" +
                "Run on a connected machine:\n" +
                "adb shell pm grant com.glasshole.plugin.devtools.glass " +
                "android.permission.WRITE_SECURE_SETTINGS"
        } catch (e: Exception) {
            resultText.text = "Toggle failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun openDevOptions() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            resultText.text = "Couldn't open dev options: ${e.message}"
        }
    }

    /** Best-effort SystemProperties.set("service.adb.tcp.port", "5555")
     *  via reflection. Stock Android 8.1 blocks this for non-system
     *  UIDs at the sepolicy layer; the call usually returns silently or
     *  throws SecurityException. We try anyway, then verify by reading
     *  the property back and reporting either success or the captured
     *  error. If it ever does take, the user should restart adbd via
     *  Settings.Global.ADB_ENABLED off+on (or this plugin's toggle). */
    private fun tryWirelessAdb() {
        val lines = StringBuilder()
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val setter = cls.getMethod("set", String::class.java, String::class.java)
            setter.invoke(null, "service.adb.tcp.port", "5555")
            // Verify
            val getter = cls.getMethod("get", String::class.java, String::class.java)
            val current = getter.invoke(null, "service.adb.tcp.port", "") as? String ?: ""
            if (current == "5555") {
                lines.append("service.adb.tcp.port = 5555 ✓\n")
                lines.append("Now flip ADB off + on (button above) to restart adbd in tcpip mode.\n")
                val ip = wifiIp()
                if (ip != null) {
                    lines.append("From your dev machine:\nadb connect $ip:5555")
                }
            } else {
                lines.append("setprop returned silently but the value didn't stick (=\"$current\"). " +
                    "sepolicy is blocking it — wireless ADB needs root on this build.")
            }
        } catch (e: SecurityException) {
            lines.append("SecurityException — sepolicy blocked it (expected on stock EE2).")
        } catch (e: Exception) {
            lines.append("${e.javaClass.simpleName}: ${e.message}")
        }
        resultText.text = lines.toString()
        Log.i("DevTools", "tryWirelessAdb result: $resultText")
    }

    private fun wifiIp(): String? {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val raw = wifi.connectionInfo?.ipAddress ?: 0
            if (raw == 0) null else Formatter.formatIpAddress(raw)
        } catch (_: Exception) { null }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // EE1/XE: swipe-down arrives as KEYCODE_BACK.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // EE1/XE raw touchpad — match the swipe-down convention the other
    // plugin activities use so back-out works whether the gesture
    // arrives as a KeyEvent or as raw x/y.
    private var startPadX: Float = 0f
    private var startPadY: Float = 0f
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event ?: return super.onGenericMotionEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startPadX = event.x; startPadY = event.y; return true
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.y - startPadY
                val dx = event.x - startPadX
                if (dy > 150 && Math.abs(dy) > Math.abs(dx) * 1.5f) finish()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
