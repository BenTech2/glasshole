package com.glasshole.phone

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.service.NotificationForwardingService
import com.google.android.material.switchmaterial.SwitchMaterial

class NotificationAppsActivity : AppCompatActivity() {

    private data class AppRow(
        val pkg: String,
        val label: String,
        val icon: Drawable?,
        /** True if the app appears in the system launcher (aka has a user-facing UI). */
        val isLauncher: Boolean
    )

    private val allApps = mutableListOf<AppRow>()
    private val visibleApps = mutableListOf<AppRow>()
    private val enabled = mutableSetOf<String>()
    private lateinit var adapter: AppsAdapter
    private lateinit var countText: TextView

    private var query: String = ""
    private var showAll: Boolean = false

    companion object {
        private const val PREF_SHOW_ALL = "notif_apps_show_all"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_apps)

        val searchBox = findViewById<EditText>(R.id.searchBox)
        val listView = findViewById<ListView>(R.id.appsList)
        val emptyView = findViewById<TextView>(R.id.emptyView)
        val advancedSwitch = findViewById<SwitchMaterial>(R.id.advancedSwitch)
        countText = findViewById(R.id.countText)

        adapter = AppsAdapter()
        listView.adapter = adapter
        listView.emptyView = emptyView

        showAll = getSharedPreferences(
            NotificationForwardingService.PREFS_NAME, MODE_PRIVATE
        ).getBoolean(PREF_SHOW_ALL, false)
        advancedSwitch.isChecked = showAll

        loadSelectedApps()
        loadInstalledApps()
        applyFilter()

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        advancedSwitch.setOnCheckedChangeListener { _, isChecked ->
            showAll = isChecked
            getSharedPreferences(NotificationForwardingService.PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(PREF_SHOW_ALL, isChecked).apply()
            applyFilter()
        }

        listView.setOnItemClickListener { _, view, _, _ ->
            val checkbox = view.findViewById<CheckBox>(R.id.check)
            checkbox.performClick()
        }
    }

    private fun loadSelectedApps() {
        val prefs = getSharedPreferences(
            NotificationForwardingService.PREFS_NAME, MODE_PRIVATE
        )
        enabled.clear()
        enabled.addAll(
            prefs.getStringSet(NotificationForwardingService.PREF_FORWARDED_APPS, emptySet())
                ?: emptySet()
        )
    }

    private fun saveSelectedApps() {
        getSharedPreferences(NotificationForwardingService.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putStringSet(NotificationForwardingService.PREF_FORWARDED_APPS, enabled.toSet())
            .apply()
        NotificationForwardingService.instance?.reloadForwardedApps()
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        allApps.clear()
        for (info in installed) {
            // Skip our own app
            if (info.packageName == packageName) continue
            if (!info.enabled) continue

            // "User-visible" = has a launcher entry. That includes YouTube,
            // X/Twitter, Messages, Gmail, etc — regardless of whether they're
            // flagged FLAG_SYSTEM. Excludes things like FotaProvider, IMS,
            // FusedLocation, DeviceDiagnostics, etc.
            val hasLauncher = pm.getLaunchIntentForPackage(info.packageName) != null

            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                info.packageName
            }
            val icon = try {
                pm.getApplicationIcon(info)
            } catch (_: Exception) {
                null
            }
            allApps.add(AppRow(info.packageName, label, icon, hasLauncher))
        }
        allApps.sortWith(compareBy(
            // Enabled apps first, then launcher apps, then alphabetical
            { it.pkg !in enabled },
            { !it.isLauncher },
            { it.label.lowercase() }
        ))
    }

    private fun applyFilter() {
        visibleApps.clear()
        val q = query.lowercase()
        for (app in allApps) {
            // Hidden system apps only show when Advanced is on — unless the
            // user has previously enabled one, in which case it always appears
            // so they can unsubscribe.
            if (!showAll && !app.isLauncher && app.pkg !in enabled) continue
            if (q.isNotEmpty() &&
                !app.label.lowercase().contains(q) &&
                !app.pkg.lowercase().contains(q)
            ) continue
            visibleApps.add(app)
        }
        val total = if (showAll) allApps.size else allApps.count { it.isLauncher || it.pkg in enabled }
        countText.text = "${visibleApps.size} / $total apps"
        adapter.notifyDataSetChanged()
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleApps.size
        override fun getItem(position: Int): AppRow = visibleApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@NotificationAppsActivity)
                .inflate(R.layout.item_notification_app, parent, false)

            val app = visibleApps[position]
            val icon = view.findViewById<ImageView>(R.id.icon)
            val label = view.findViewById<TextView>(R.id.label)
            val pkgText = view.findViewById<TextView>(R.id.pkg)
            val checkbox = view.findViewById<CheckBox>(R.id.check)

            icon.setImageDrawable(
                app.icon ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, theme)
            )
            label.text = app.label
            pkgText.text = app.pkg

            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = app.pkg in enabled
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) enabled.add(app.pkg) else enabled.remove(app.pkg)
                saveSelectedApps()
            }

            return view
        }
    }
}
