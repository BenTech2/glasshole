package com.glasshole.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.service.BridgeService
import com.glasshole.phone.widget.BatteryHistoryView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Glass-side device info viewer.
 *
 * Refresh strategy:
 *   - Full DEVICE_INFO_REQ once when the activity opens (heavy: hardware,
 *     OS, network, storage, memory — none of which change moment-to-
 *     moment).
 *   - Light BATTERY_INFO_REQ every [BATTERY_POLL_MS] while the activity
 *     is foreground, used to (a) refresh the Battery section's rows
 *     (percent / current_now / voltage / temperature etc. tick live) and
 *     (b) append a sample to the in-memory history that the
 *     BatteryHistoryView graph at the top of the Battery card draws.
 *
 * Section content is rendered programmatically from whatever JSON the
 * glass sends — adding a new field on the glass side shows up here
 * without any layout change.
 */
class GlassDeviceInfoActivity : AppCompatActivity() {

    companion object {
        private const val BATTERY_POLL_MS = 5000L
        // Cap the in-memory history so the buffer doesn't grow unbounded
        // if the user leaves the page open for hours. ~1h at 5s spacing.
        private const val MAX_HISTORY_SAMPLES = 720
        // Stable display order. Keys not in this list still get rendered
        // at the bottom (catch-all so unknown sections aren't silently
        // dropped if the glass adds a new one later).
        private val SECTION_ORDER = listOf(
            "hardware", "os", "network", "battery", "storage", "memory", "misc"
        )
        private val SECTION_TITLES = mapOf(
            "hardware" to "Hardware",
            "os" to "OS / firmware",
            "network" to "Network",
            "battery" to "Battery",
            "storage" to "Storage",
            "memory" to "Memory",
            "misc" to "Misc"
        )
    }

    private lateinit var statusText: TextView
    private lateinit var container: LinearLayout

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false

    // Battery section is special-cased so the graph + history persist
    // across the lightweight refreshes.
    private var batteryRowsContainer: LinearLayout? = null
    private var batteryGraph: BatteryHistoryView? = null
    private val batteryHistory = mutableListOf<BatteryHistoryView.Sample>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            bridgeService?.takeIf { it.isConnected }?.requestBatteryInfo()
            mainHandler.postDelayed(this, BATTERY_POLL_MS)
        }
    }

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as BridgeService.LocalBinder).getService()
            bridgeBound = true
            bridgeService?.onGlassDeviceInfo = { json ->
                runOnUiThread { renderInfo(json) }
            }
            bridgeService?.onGlassBatteryInfo = { json ->
                runOnUiThread { applyBatteryUpdate(json) }
            }
            requestFullRefresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bridgeBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_glass_device_info)
        statusText = findViewById(R.id.deviceInfoStatus)
        container = findViewById(R.id.deviceInfoContainer)

        bindService(
            Intent(this, BridgeService::class.java),
            bridgeConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        // Battery polling only runs while the page is visible.
        mainHandler.post(batteryPollRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(batteryPollRunnable)
    }

    override fun onDestroy() {
        bridgeService?.onGlassDeviceInfo = null
        bridgeService?.onGlassBatteryInfo = null
        if (bridgeBound) {
            unbindService(bridgeConnection)
            bridgeBound = false
        }
        super.onDestroy()
    }

    private fun requestFullRefresh() {
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            statusText.text = "Glass not connected — connect from the main screen first"
            return
        }
        if (!bridge.requestDeviceInfo()) {
            statusText.text = "Send failed — glass connection may have dropped"
        }
    }

    private fun renderInfo(json: String) {
        container.removeAllViews()
        batteryRowsContainer = null
        batteryGraph = null

        val root = try { JSONObject(json) } catch (_: Exception) {
            statusText.text = "Couldn't parse device info"
            return
        }

        // Render in defined order, then any unknown sections.
        val orderSet = SECTION_ORDER.toSet()
        val keys = mutableListOf<String>()
        keys.addAll(SECTION_ORDER.filter { root.has(it) })
        val iter = root.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k !in orderSet) keys.add(k)
        }

        for (sectionKey in keys) {
            val obj = root.optJSONObject(sectionKey) ?: continue
            if (obj.length() == 0) continue
            if (sectionKey == "battery") {
                addBatteryCard(obj)
            } else {
                addGenericCard(SECTION_TITLES[sectionKey] ?: sectionKey, obj)
            }
        }

        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        statusText.text = "Loaded $now · battery refreshes every ${BATTERY_POLL_MS / 1000}s"
    }

    private fun addGenericCard(title: String, obj: JSONObject) {
        val sectionView = LayoutInflater.from(this)
            .inflate(R.layout.item_device_info_section, container, false)
        sectionView.findViewById<TextView>(R.id.sectionTitle).text = title
        val rows = sectionView.findViewById<LinearLayout>(R.id.sectionRows)
        populateRows(rows, obj)
        container.addView(sectionView)
    }

    private fun addBatteryCard(obj: JSONObject) {
        val card = LayoutInflater.from(this)
            .inflate(R.layout.item_device_info_battery, container, false)
        batteryRowsContainer = card.findViewById(R.id.batteryRows)
        batteryGraph = card.findViewById(R.id.batteryHistoryView)
        applyBatterySnapshot(obj)
        container.addView(card)
    }

    /** Called on every BATTERY_INFO_REQ tick. Re-renders the battery
     *  rows and appends a new percent sample to the graph. */
    private fun applyBatteryUpdate(json: String) {
        val obj = try { JSONObject(json) } catch (_: Exception) { return }
        applyBatterySnapshot(obj)
    }

    private fun applyBatterySnapshot(obj: JSONObject) {
        val rows = batteryRowsContainer ?: return
        rows.removeAllViews()
        populateRows(rows, obj)

        val percent = obj.opt("percent")?.toString()?.toFloatOrNull()
            ?: obj.opt("level")?.toString()?.toFloatOrNull()
        if (percent != null) {
            batteryHistory.add(BatteryHistoryView.Sample(System.currentTimeMillis(), percent))
            while (batteryHistory.size > MAX_HISTORY_SAMPLES) batteryHistory.removeAt(0)
            batteryGraph?.setSamples(batteryHistory.toList())
        }
    }

    private fun populateRows(rows: LinearLayout, obj: JSONObject) {
        val keyIter = obj.keys()
        while (keyIter.hasNext()) {
            val key = keyIter.next()
            val value = obj.opt(key)
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_device_info_row, rows, false)
            row.findViewById<TextView>(R.id.rowKey).text = humanizeKey(key)
            row.findViewById<TextView>(R.id.rowValue).text = formatValue(key, value)
            rows.addView(row)
        }
    }

    private fun humanizeKey(key: String): String {
        // foo_bar_baz_uA → "Foo bar baz (µA)" — keep unit-suffix tags
        // wrapped in parens so the user reads them as the unit, not as
        // part of the field name.
        val unitMap = mapOf(
            "_bytes" to " (bytes)",
            "_uA" to " (µA)",
            "_uAh" to " (µAh)",
            "_nWh" to " (nWh)",
            "_mV" to " (mV)",
            "_mhz" to " (MHz)",
            "_mbps" to " (Mbps)",
            "_dbm" to " (dBm)",
            "_dpi" to " (dpi)",
            "_px" to " (px)",
            "_ms" to " (ms)",
            "_c" to " (°C)"
        )
        var work = key
        var unitSuffix = ""
        for ((suffix, label) in unitMap) {
            if (work.endsWith(suffix, ignoreCase = false)) {
                work = work.removeSuffix(suffix)
                unitSuffix = label
                break
            }
        }
        val pretty = work.replace('_', ' ').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        return pretty + unitSuffix
    }

    private fun formatValue(key: String, value: Any?): String {
        if (value == null || value == JSONObject.NULL) return "—"
        val raw = value.toString()
        return when {
            key.endsWith("_bytes") -> formatBytes(raw.toLongOrNull() ?: return raw)
            key.endsWith("_uAh") -> {
                val uah = raw.toLongOrNull() ?: return raw
                "${uah / 1000} mAh"
            }
            key.endsWith("_uA") -> {
                val ua = raw.toIntOrNull() ?: return raw
                "${ua / 1000} mA"
            }
            key.endsWith("_nWh") -> {
                val nwh = raw.toLongOrNull() ?: return raw
                "${nwh / 1_000_000_000L} Wh"
            }
            key.endsWith("_mV") -> {
                val mv = raw.toIntOrNull() ?: return raw
                String.format(Locale.US, "%.3f V", mv / 1000.0)
            }
            key.endsWith("_ms") -> formatDuration(raw.toLongOrNull() ?: return raw)
            key == "temperature_c" -> {
                val c = raw.toDoubleOrNull() ?: return raw
                String.format(Locale.US, "%.1f °C", c)
            }
            else -> raw
        }
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        return Formatter.formatShortFileSize(this, b)
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        if (s < 60) return "${s}s"
        val m = s / 60
        if (m < 60) return "${m}m ${s % 60}s"
        val h = m / 60
        return if (h < 24) "${h}h ${m % 60}m" else "${h / 24}d ${h % 24}h"
    }
}
