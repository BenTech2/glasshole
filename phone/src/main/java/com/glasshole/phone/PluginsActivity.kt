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
import com.glasshole.phone.plugins.notes.NotesActivity
import com.glasshole.phone.service.BridgeService
import org.json.JSONArray

/**
 * Lists the user-launchable GlassHole plugins currently installed on the
 * connected glass. The glass's LIST_PACKAGES response is the source of
 * truth — whatever plugin APKs it reports as installed (excluding the
 * core device / stream / photo-sync packages) show up here with their
 * label, installed version, and an Open button routing to the phone-side
 * companion activity when one exists.
 */
class PluginsActivity : AppCompatActivity() {

    private data class PluginRow(
        val packageName: String,
        val label: String,
        val version: String,
        val openActivity: Class<*>?,
        val settingsActivity: Class<*>?
    )

    // Plugin packages that back core GlassHole functionality — excluded
    private val CORE_PLUGIN_PACKAGES = setOf(
        "com.glasshole.plugin.device.glass",
        "com.glasshole.plugin.gallery.glass"
    )

    // Glass plugin package → phone-side companion activity, if any.
    private val phoneCompanions: Map<String, Class<*>> = mapOf(
        "com.glasshole.plugin.notes.glass" to NotesActivity::class.java,
        "com.glasshole.plugin.calc.glass" to CalcHistoryActivity::class.java
    )

    private val rows = mutableListOf<PluginRow>()

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
                statusText.text = "Fetching installed plugins from glass…"
                bridgeService?.requestPackageList()
            } else {
                statusText.text = "Glass not connected — connect to see installed plugins"
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
        for (p in rows) {
            val row = inflater.inflate(R.layout.item_plugin, listContainer, false)
            row.findViewById<TextView>(R.id.pluginName).text = p.label
            row.findViewById<TextView>(R.id.pluginDescription).text = p.packageName

            val versionView = row.findViewById<TextView>(R.id.pluginVersion)
            versionView.text = if (p.version.isNotEmpty()) "v${p.version} on glass" else "Installed on glass"

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
        rows.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.optString("pkg")
                if (!pkg.startsWith("com.glasshole.plugin.")) continue
                if (pkg in CORE_PLUGIN_PACKAGES) continue

                rows.add(
                    PluginRow(
                        packageName = pkg,
                        label = obj.optString("label", pkg),
                        version = obj.optString("version", ""),
                        openActivity = phoneCompanions[pkg],
                        settingsActivity = null
                    )
                )
            }
            rows.sortBy { it.label.lowercase() }
        } catch (e: Exception) {
            statusText.text = "Bad package list: ${e.message}"
            return
        }
        statusText.text = if (rows.isEmpty()) {
            "No plugins installed on glass"
        } else {
            "${rows.size} plugin${if (rows.size == 1) "" else "s"} installed on glass"
        }
        renderList()
    }
}
