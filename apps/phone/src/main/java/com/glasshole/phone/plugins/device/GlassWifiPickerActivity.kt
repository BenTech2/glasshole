package com.glasshole.phone.plugins.device

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.glasshole.phone.service.BridgeService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Phone-side picker for Wi-Fi networks visible to the glass. Asks
 * the glass to scan, lists results sorted by signal strength, and
 * opens a password dialog (or connects directly for OPEN networks)
 * on tap. Connection result lands as a Toast; the calling
 * DeviceActivity refreshes its glass Wi-Fi status when this picker
 * closes.
 */
class GlassWifiPickerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var scanProgress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var adapter: NetworkAdapter
    private var networks: List<BridgeService.GlassWifiNetwork> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_glass_wifi_picker)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listView = findViewById(R.id.networksList)
        scanProgress = findViewById(R.id.scanProgress)
        emptyText = findViewById(R.id.emptyText)
        adapter = NetworkAdapter()
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            promptAndConnect(networks[position])
        }
        findViewById<FloatingActionButton>(R.id.refreshFab).setOnClickListener {
            triggerScan()
        }

        triggerScan()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    private fun triggerScan() {
        val bridge = BridgeService.instance
        if (bridge == null || !bridge.isConnected) {
            Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        scanProgress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        bridge.scanGlassWifi { results, err ->
            runOnUiThread {
                scanProgress.visibility = View.GONE
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                networks = results
                emptyText.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun promptAndConnect(net: BridgeService.GlassWifiNetwork) {
        if (net.security == "OPEN") {
            connect(net, password = "")
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(net.ssid)
            .setMessage("${net.security} • ${rssiBars(net.rssi)}")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                connect(net, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connect(net: BridgeService.GlassWifiNetwork, password: String) {
        val bridge = BridgeService.instance ?: return
        Toast.makeText(this, "Connecting to ${net.ssid}…", Toast.LENGTH_SHORT).show()
        bridge.connectGlassWifi(net.ssid, password, net.security) { ok, message ->
            runOnUiThread {
                val msg = if (ok) "Connected to ${net.ssid}" else "Connect failed: $message"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) finish()
            }
        }
    }

    private fun rssiBars(rssi: Int): String = when {
        rssi >= -55 -> "Strong"
        rssi >= -70 -> "Good"
        rssi >= -82 -> "Fair"
        else -> "Weak"
    } + " (${rssi} dBm)"

    private inner class NetworkAdapter : BaseAdapter() {
        override fun getCount(): Int = networks.size
        override fun getItem(i: Int): Any = networks[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@GlassWifiPickerActivity)
                .inflate(R.layout.item_glass_wifi_network, parent, false)
            val net = networks[position]
            view.findViewById<TextView>(R.id.ssidText).text = net.ssid
            view.findViewById<TextView>(R.id.metaText).text = rssiBars(net.rssi)
            view.findViewById<TextView>(R.id.securityBadge).text = net.security
            return view
        }
    }
}
