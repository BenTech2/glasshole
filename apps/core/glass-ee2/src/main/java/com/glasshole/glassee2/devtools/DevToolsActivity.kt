package com.glasshole.glassee2.devtools

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import com.glasshole.glassee2.R

/**
 * Dev Tools settings screen, launched from the Settings drawer's
 * Dev Tools tile. Styled to look like a classic Glass XE settings
 * detail view — dark background, light title row at the top, tappable
 * list rows below.
 *
 * First row is "Wireless debugging", which:
 *  1. Reads service.adb.tcp.port via reflection on initial load to
 *     surface current state.
 *  2. On tap, runs the same setprop + adbd-restart command the plugin
 *     used. If `su` isn't available (i.e. the glass isn't rooted) the
 *     row shows that fact and points the user at the USB workflow
 *     (`adb tcpip 5555` from a host machine) — there's no rootless
 *     path to flipping the daemon's listening socket on KitKat.
 *
 * Swipe-down / hardware-back returns to the Settings drawer (whose
 * activity is intentionally kept on the back stack by
 * SettingsDrawerActivity.launchCurrent).
 */
class DevToolsActivity : Activity() {

    companion object { private const val TAG = "DevToolsActivity" }

    private lateinit var wirelessAdbRow: View
    private lateinit var wirelessAdbStatus: TextView

    /** Tracks the last-known port we read from setprop. -1 = off,
     *  any positive int = the live listening port. */
    private var currentTcpPort: Int = -1
    /** Once detected, drives whether the row is tappable. Initial state
     *  is null = not yet probed; we lock the row out until the first
     *  detect() returns. */
    private var rootAvailable: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dev_tools)
        wirelessAdbRow = findViewById(R.id.wirelessAdbRow)
        wirelessAdbStatus = findViewById(R.id.wirelessAdbStatus)
        wirelessAdbRow.setOnClickListener { onWirelessAdbTapped() }
        refreshWirelessAdb()
        RootHelper.detect { granted ->
            rootAvailable = granted
            refreshWirelessAdb()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshWirelessAdb()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** Read service.adb.tcp.port via reflection and render the row's
     *  status text accordingly. Reflection is necessary because
     *  android.os.SystemProperties is @hide on every public API level. */
    private fun refreshWirelessAdb() {
        currentTcpPort = readAdbTcpPort()
        val ip = wifiIp()
        val status = when {
            currentTcpPort > 0 && ip != null ->
                "ON · adb connect $ip:$currentTcpPort"
            currentTcpPort > 0 ->
                "ON · port $currentTcpPort (no Wi-Fi)"
            rootAvailable == false ->
                "OFF · root required — pair via USB once, then run\n" +
                    "adb tcpip 5555 from your dev machine"
            else ->
                "OFF · tap to turn on"
        }
        wirelessAdbStatus.text = status
        // While we don't know root state yet, leave the row tappable but
        // it'll just show an error if root is missing. Once detect()
        // returns we narrow the surface area.
    }

    private fun onWirelessAdbTapped() {
        if (rootAvailable == false) {
            // Best-effort: still try, since the user might have installed
            // su between checks. RootHelper will surface the actual error.
        }
        val turningOn = currentTcpPort <= 0
        val cmd = if (turningOn) {
            "setprop service.adb.tcp.port 5555 && stop adbd && start adbd"
        } else {
            // -1 makes adbd revert to USB-only at next restart.
            "setprop service.adb.tcp.port -1 && stop adbd && start adbd"
        }
        wirelessAdbStatus.text = if (turningOn) "Enabling…" else "Disabling…"
        RootHelper.run(cmd) { r ->
            if (r.ok) {
                // Give adbd a beat to bring its TCP listener up before
                // re-reading the property.
                Handler(Looper.getMainLooper())
                    .postDelayed({ refreshWirelessAdb() }, 600L)
            } else {
                val detail = r.stderr.ifEmpty { r.stdout }.take(160)
                wirelessAdbStatus.text = if (!r.available) {
                    "OFF · root required — pair via USB once, then run\n" +
                        "adb tcpip 5555 from your dev machine"
                } else {
                    "Failed (exit ${r.exitCode}):\n$detail"
                }
                rootAvailable = r.available && rootAvailable == true
            }
        }
    }

    private fun readAdbTcpPort(): Int {
        return try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            val raw = (get.invoke(null, "service.adb.tcp.port", "") as? String).orEmpty()
            raw.toIntOrNull() ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "SystemProperties.get reflection failed: ${e.message}")
            -1
        }
    }

    private fun wifiIp(): String? {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val raw = wifi.connectionInfo?.ipAddress ?: 0
            if (raw == 0) null else Formatter.formatIpAddress(raw)
        } catch (_: Exception) { null }
    }
}
