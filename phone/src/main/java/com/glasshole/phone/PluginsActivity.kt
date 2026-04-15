package com.glasshole.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.plugins.calc.CalcHistoryActivity
import com.glasshole.phone.plugins.gallery.GalleryActivity
import com.glasshole.phone.plugins.notes.NotesActivity
import com.glasshole.phone.service.BridgeService
import org.json.JSONArray

/**
 * Lists glasshole plugins with their Open button and (optionally) a
 * settings gear. The list is hand-declared for now since the phone-side
 * plugins are in-process and don't support auto-discovery — but each
 * entry carries the matching glass-side package name so we can pair it
 * with the LIST_PACKAGES response from the connected glass and show the
 * version that's actually installed on-device.
 */
class PluginsActivity : AppCompatActivity() {

    private data class PluginEntry(
        val name: String,
        val description: String,
        val openActivity: Class<*>?,
        val settingsActivity: Class<*>?,
        val glassPackage: String
    )

    private val plugins = listOf(
        PluginEntry(
            name = "Notes",
            description = "Dictate notes on glass, read them on the phone",
            openActivity = NotesActivity::class.java,
            settingsActivity = null,
            glassPackage = "com.glasshole.plugin.notes.glass"
        ),
        PluginEntry(
            name = "Calc",
            description = "Calculator with history shared between phone and glass",
            openActivity = CalcHistoryActivity::class.java,
            settingsActivity = null,
            glassPackage = "com.glasshole.plugin.calc.glass"
        ),
        PluginEntry(
            name = "Gallery",
            description = "Browse and download photos/videos from the glass",
            openActivity = GalleryActivity::class.java,
            settingsActivity = null,
            glassPackage = "com.glasshole.plugin.gallery.glass"
        ),
        PluginEntry(
            name = "Stream",
            description = "Share YouTube / Twitch links from any app to play on the glass",
            openActivity = null,
            settingsActivity = null,
            glassPackage = "com.glasshole.plugin.stream.glass"
        ),
        PluginEntry(
            name = "Device Controls",
            description = "Brightness, volume, timeout, wake, auto-start, tilt-wake (EE2)",
            openActivity = com.glasshole.phone.plugins.device.DeviceActivity::class.java,
            settingsActivity = null,
            glassPackage = "com.glasshole.plugin.device.glass"
        )
    )

    // pkg → version string, updated as LIST_PACKAGES responses come in
    private val glassVersions = mutableMapOf<String, String>()

    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as BridgeService.LocalBinder).getService()
            bridgeBound = true
            bridgeService?.onPackageList = { json -> runOnUiThread { handlePackageList(json) } }
            if (bridgeService?.isConnected == true) {
                statusText.text = "Fetching installed versions from glass…"
                bridgeService?.requestPackageList()
            } else {
                statusText.text = "Glass not connected — showing phone-side plugins only"
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bridgeBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugins)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Plugins"

        listContainer = findViewById(R.id.pluginsList)
        statusText = findViewById(R.id.pluginsStatus)

        renderList()

        bindService(
            Intent(this, BridgeService::class.java),
            bridgeConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        if (bridgeBound) {
            bridgeService?.onPackageList = null
            unbindService(bridgeConnection)
            bridgeBound = false
        }
        super.onDestroy()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (p in plugins) {
            val row = inflater.inflate(R.layout.item_plugin, listContainer, false)
            row.findViewById<TextView>(R.id.pluginName).text = p.name
            row.findViewById<TextView>(R.id.pluginDescription).text = p.description

            val versionView = row.findViewById<TextView>(R.id.pluginVersion)
            val version = glassVersions[p.glassPackage]
            versionView.text = when {
                version == null -> "Not installed on glass"
                version.isEmpty() -> "Installed on glass"
                else -> "v$version on glass"
            }
            versionView.alpha = if (version == null) 0.55f else 1f

            val openBtn = row.findViewById<View>(R.id.pluginOpenButton)
            if (p.openActivity != null) {
                openBtn.visibility = View.VISIBLE
                openBtn.setOnClickListener {
                    startActivity(Intent(this, p.openActivity))
                }
            } else {
                openBtn.visibility = View.GONE
            }

            val settingsBtn = row.findViewById<ImageButton>(R.id.pluginSettingsButton)
            if (p.settingsActivity != null) {
                settingsBtn.visibility = View.VISIBLE
                settingsBtn.setOnClickListener {
                    startActivity(Intent(this, p.settingsActivity))
                }
            } else {
                settingsBtn.visibility = View.GONE
            }

            listContainer.addView(row)
        }
    }

    private fun handlePackageList(json: String) {
        try {
            val arr = JSONArray(json)
            glassVersions.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.optString("pkg")
                val version = obj.optString("version", "")
                if (pkg.isNotEmpty()) glassVersions[pkg] = version
            }
            val matched = plugins.count { glassVersions.containsKey(it.glassPackage) }
            statusText.text = "$matched of ${plugins.size} plugins installed on glass"
            renderList()
        } catch (e: Exception) {
            statusText.text = "Bad package list: ${e.message}"
        }
    }
}
