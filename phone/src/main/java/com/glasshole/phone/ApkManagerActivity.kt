package com.glasshole.phone

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.service.BridgeService
import org.json.JSONArray
import java.io.ByteArrayOutputStream

class ApkManagerActivity : AppCompatActivity() {

    private data class PackageRow(
        val pkg: String,
        val label: String,
        val version: String,
        val system: Boolean,
        val glasshole: Boolean
    )

    private val allPackages = mutableListOf<PackageRow>()
    private val visiblePackages = mutableListOf<PackageRow>()
    private var showSystemApps = false

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var statusText: TextView
    private lateinit var gateBanner: TextView
    private lateinit var installButton: Button
    private lateinit var refreshButton: Button
    private lateinit var systemToggle: CheckBox
    private lateinit var adapter: PackagesAdapter

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as BridgeService.LocalBinder).getService()
            bridgeBound = true
            bridgeService?.onPackageList = { json -> runOnUiThread { handlePackageList(json) } }
            bridgeService?.onUninstallResult = { pkg, status ->
                runOnUiThread { handleUninstallResult(pkg, status) }
            }
            bridgeService?.onInstallResult = { status ->
                runOnUiThread { handleInstallResult(status) }
            }
            refreshPackages()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bridgeBound = false
        }
    }

    private val pickApkLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) sendApk(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apk_manager)

        listView = findViewById(R.id.packageList)
        emptyText = findViewById(R.id.emptyText)
        statusText = findViewById(R.id.statusText)
        gateBanner = findViewById(R.id.gateBanner)
        installButton = findViewById(R.id.installButton)
        refreshButton = findViewById(R.id.refreshButton)
        systemToggle = findViewById(R.id.systemToggle)

        adapter = PackagesAdapter()
        listView.adapter = adapter
        listView.emptyView = emptyText

        installButton.setOnClickListener {
            pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
        }
        refreshButton.setOnClickListener { refreshPackages() }
        systemToggle.setOnCheckedChangeListener { _, isChecked ->
            showSystemApps = isChecked
            applyFilter()
        }

        // Bind to the already-running bridge service
        val intent = Intent(this, BridgeService::class.java)
        bindService(intent, bridgeConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        bridgeService?.onPackageList = null
        bridgeService?.onUninstallResult = null
        bridgeService?.onInstallResult = null
        bridgeService?.onInstallProgress = null
        if (bridgeBound) {
            unbindService(bridgeConnection)
            bridgeBound = false
        }
        super.onDestroy()
    }

    private fun refreshPackages() {
        val sent = bridgeService?.requestPackageList() ?: false
        statusText.text = if (sent) "Fetching package list..." else "Glass not connected"
    }

    private fun handlePackageList(json: String) {
        allPackages.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                allPackages.add(
                    PackageRow(
                        pkg = obj.getString("pkg"),
                        label = obj.optString("label", obj.getString("pkg")),
                        version = obj.optString("version", ""),
                        system = obj.optBoolean("system", false),
                        glasshole = obj.optBoolean("glasshole", false)
                    )
                )
            }
        } catch (e: Exception) {
            statusText.text = "Bad package list: ${e.message}"
            return
        }
        allPackages.sortWith(compareBy({ !it.glasshole }, { it.label.lowercase() }))
        applyFilter()
        statusText.text = "${visiblePackages.size} packages on glass"
    }

    private fun applyFilter() {
        visiblePackages.clear()
        for (p in allPackages) {
            if (!showSystemApps && p.system && !p.glasshole) continue
            visiblePackages.add(p)
        }
        adapter.notifyDataSetChanged()
    }

    private fun handleUninstallResult(pkg: String, status: String) {
        val label = allPackages.firstOrNull { it.pkg == pkg }?.label ?: pkg
        statusText.text = "$label: $status"
        gateBanner.visibility =
            if (status.contains("needs_unknown_sources")) View.VISIBLE else View.GONE
        // Re-fetch list shortly so removed packages disappear (after user confirms)
        Handler(Looper.getMainLooper()).postDelayed({ refreshPackages() }, 2500)
    }

    private fun handleInstallResult(status: String) {
        statusText.text = "Install: $status"
        gateBanner.visibility =
            if (status.contains("needs_unknown_sources")) View.VISIBLE else View.GONE
        Handler(Looper.getMainLooper()).postDelayed({ refreshPackages() }, 2500)
    }

    private fun sendApk(uri: Uri) {
        val filename = resolveFilename(uri) ?: "app.apk"
        val bridge = bridgeService ?: run {
            toast("Glass not connected")
            return
        }
        if (!bridge.isConnected) {
            toast("Glass not connected")
            return
        }

        val progress = ProgressDialog(this).apply {
            setTitle("Installing $filename")
            setMessage("Reading APK...")
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            show()
        }

        bridge.onInstallProgress = { sent, total ->
            runOnUiThread {
                val pct = if (total > 0) ((sent * 100) / total).toInt() else 0
                progress.progress = pct
                progress.setMessage("$pct%  (${sent / 1024} KB / ${total / 1024} KB)")
            }
        }
        bridge.onInstallResult = { status ->
            runOnUiThread {
                progress.dismiss()
                statusText.text = "Install: $status"
                Handler(Looper.getMainLooper()).postDelayed({ refreshPackages() }, 2500)
            }
        }

        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(16 * 1024)
                    var n: Int
                    while (input.read(tmp).also { n = it } > 0) buf.write(tmp, 0, n)
                    buf.toByteArray()
                } ?: throw Exception("Could not read APK")

                runOnUiThread { progress.setMessage("Sending ${bytes.size / 1024} KB...") }
                val ok = bridge.sendApk(filename, bytes)
                if (!ok) runOnUiThread {
                    progress.dismiss()
                    toast("Send failed")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    toast("Install error: ${e.message}")
                }
            }
        }.start()
    }

    private fun resolveFilename(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun confirmUninstall(row: PackageRow) {
        AlertDialog.Builder(this)
            .setTitle("Uninstall ${row.label}?")
            .setMessage("Package: ${row.pkg}\n\nYou'll need to confirm on the Glass screen.")
            .setPositiveButton("Uninstall") { _, _ ->
                val sent = bridgeService?.requestUninstall(row.pkg) ?: false
                if (!sent) toast("Glass not connected")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private inner class PackagesAdapter : BaseAdapter() {
        override fun getCount(): Int = visiblePackages.size
        override fun getItem(position: Int) = visiblePackages[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@ApkManagerActivity)
                .inflate(R.layout.item_apk_package, parent, false)
            val row = visiblePackages[position]

            val label = view.findViewById<TextView>(R.id.label)
            val pkg = view.findViewById<TextView>(R.id.pkg)
            val badge = view.findViewById<TextView>(R.id.badge)
            val uninstall = view.findViewById<ImageButton>(R.id.uninstallBtn)

            label.text = row.label
            pkg.text = if (row.version.isNotEmpty()) "${row.pkg}  v${row.version}" else row.pkg
            badge.visibility = if (row.glasshole) View.VISIBLE else View.GONE
            uninstall.setOnClickListener { confirmUninstall(row) }

            return view
        }
    }
}
