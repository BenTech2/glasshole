package com.glasshole.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.plugindir.PluginDirectory
import com.glasshole.phone.plugindir.PluginSettingsActivity
import com.glasshole.phone.plugins.calc.CalcHistoryActivity
import com.glasshole.phone.plugins.notes.NotesActivity
import com.glasshole.phone.service.BridgeService

/**
 * Lists plugins installed on the connected glass. Entirely driven by
 * [PluginDirectory]: glass base app emits PLUGIN_LIST on connect with
 * each plugin's id / name / description / version, and we mirror that
 * into a scrollable list.
 *
 * Tapping the settings icon opens the generic [PluginSettingsActivity]
 * which renders whatever schema the plugin ships — no hardcoded per-
 * plugin settings map anymore. The "Open" button still points at the
 * legacy phone-side companion activities for the two plugins that have
 * them (Notes + Calc) until those also migrate to dynamic surfaces.
 */
class PluginsActivity : AppCompatActivity() {

    // Glass plugin package → phone-side companion "Open" activity, if any.
    // These are data-viewer activities (note list, calc history), not settings.
    private val phoneCompanions: Map<String, Class<*>> = mapOf(
        "com.glasshole.plugin.notes.glass" to NotesActivity::class.java,
        "com.glasshole.plugin.calc.glass" to CalcHistoryActivity::class.java
    )

    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false

    private val directoryListener: () -> Unit = {
        runOnUiThread { render() }
    }

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as BridgeService.LocalBinder).getService()
            bridgeBound = true
            render()
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
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, BridgeService::class.java), bridgeConnection, Context.BIND_AUTO_CREATE)
        PluginDirectory.addListener(directoryListener)
        render()
    }

    override fun onStop() {
        super.onStop()
        if (bridgeBound) { unbindService(bridgeConnection); bridgeBound = false }
        PluginDirectory.removeListener(directoryListener)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    private fun render() {
        val entries = PluginDirectory.all()
        val bridgeConnected = bridgeService?.isConnected == true
        statusText.text = when {
            entries.isNotEmpty() ->
                "${entries.size} plugin${if (entries.size == 1) "" else "s"} installed on glass"
            bridgeConnected ->
                "No plugins discovered yet — waiting for glass to send directory…"
            else ->
                "Glass not connected — connect to see installed plugins"
        }

        listContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (e in entries) {
            val row = inflater.inflate(R.layout.item_plugin, listContainer, false)
            row.findViewById<TextView>(R.id.pluginName).text =
                if (e.name.isNotEmpty()) e.name else e.id
            row.findViewById<TextView>(R.id.pluginDescription).text =
                if (e.description.isNotEmpty()) e.description else e.packageName
            row.findViewById<TextView>(R.id.pluginVersion).text =
                if (e.version.isNotEmpty()) "v${e.version} on glass" else "Installed on glass"

            val openBtn = row.findViewById<View>(R.id.pluginOpenButton)
            val companion = phoneCompanions[e.packageName]
            if (companion != null) {
                openBtn.visibility = View.VISIBLE
                openBtn.setOnClickListener { startActivity(Intent(this, companion)) }
            } else {
                openBtn.visibility = View.GONE
            }

            val settingsBtn = row.findViewById<ImageButton>(R.id.pluginSettingsButton)
            if (e.hasSchema) {
                settingsBtn.visibility = View.VISIBLE
                settingsBtn.setOnClickListener {
                    startActivity(
                        Intent(this, PluginSettingsActivity::class.java)
                            .putExtra(PluginSettingsActivity.EXTRA_PLUGIN_ID, e.id)
                    )
                }
            } else {
                settingsBtn.visibility = View.GONE
            }

            listContainer.addView(row)
        }
    }
}
