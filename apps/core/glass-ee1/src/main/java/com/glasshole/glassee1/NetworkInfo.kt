package com.glasshole.glassee1

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager

/**
 * Pulls Wi-Fi MAC, Bluetooth MAC and current IP address for the About
 * card's network block. KitKat-safe paths only:
 *   - WifiManager.connectionInfo.macAddress works on API ≤22.
 *   - BluetoothAdapter.getDefaultAdapter().address works on API ≤22.
 *   - For API 23+ both return the placeholder 02:00:00:00:00:00; we
 *     fall back to reading /sys/class/net/{wlan0,bt0}/address.
 *
 * IP is the Wi-Fi DHCP-assigned address; "—" when not connected.
 */
object NetworkInfo {

    fun summary(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("Wi-Fi MAC: ").append(wifiMac(ctx))
        sb.append("\nBT MAC: ").append(bluetoothMac())
        sb.append("\nIP: ").append(wifiIp(ctx))
        return sb.toString()
    }

    private fun wifiMac(ctx: Context): String {
        try {
            val wm = ctx.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val mac = wm?.connectionInfo?.macAddress
            if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00") return mac
        } catch (_: Throwable) {}
        return readSysAddress("/sys/class/net/wlan0/address") ?: "—"
    }

    private fun bluetoothMac(): String {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "—"
            @Suppress("DEPRECATION", "HardwareIds")
            val addr = adapter.address
            if (!addr.isNullOrBlank() && addr != "02:00:00:00:00:00") return addr
        } catch (_: Throwable) {}
        // Common Glass node names — bt0 on EE1/XE, hci0 on most devices.
        return readSysAddress("/sys/class/net/bt0/address")
            ?: readSysAddress("/sys/class/bluetooth/hci0/address")
            ?: "—"
    }

    private fun wifiIp(ctx: Context): String {
        try {
            val wm = ctx.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val raw = wm?.connectionInfo?.ipAddress ?: 0
            if (raw == 0) return "—"
            // Little-endian int → dotted-quad.
            return "${raw and 0xff}.${(raw shr 8) and 0xff}." +
                "${(raw shr 16) and 0xff}.${(raw shr 24) and 0xff}"
        } catch (_: Throwable) {
            return "—"
        }
    }

    private fun readSysAddress(path: String): String? = try {
        java.io.File(path).readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }
}
