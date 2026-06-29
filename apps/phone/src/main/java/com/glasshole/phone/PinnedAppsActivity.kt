// SPDX-License-Identifier: MIT
package com.glasshole.phone

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.service.BridgeService
import com.glasshole.phone.service.PluginHostService
import org.json.JSONObject

/**
 * Phone-side picker for the App-Drawer pinned-apps feature. Round trip:
 *
 *   1. Phone fires `LIST_LAUNCHER_APPS_REQ` to glass on open.
 *   2. Glass enumerates `Intent.ACTION_MAIN + CATEGORY_LAUNCHER`
 *      activities + ships back `LAUNCHER_APPS_LIST` JSON.
 *   3. User checks up to 4 entries (the 5th click toasts a refusal).
 *   4. Save fires `SET_PINNED_APPS` with the package names in pin order.
 *   5. Glass's `AppDrawerActivity.loadLauncherApps()` reorders pinned
 *      packages to the front on next open.
 *
 * Persisted state: phone keeps a local snapshot of the pin list in its
 * own SharedPreferences for instant render on reopen; the glass STATE
 * push is authoritative if it ever lands.
 */
class PinnedAppsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PinnedApps"
        private const val MAX_PINNED = 4
        private const val PREFS = "glasshole_pinned_apps"
        private const val KEY_PINNED_CSV = "pinned_csv"
    }

    private data class AppRow(val pkg: String, val label: String, var checked: Boolean)

    private lateinit var status: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var saveBtn: Button
    private lateinit var clearBtn: Button

    private val rows = mutableListOf<AppRow>()
    /** Maintains insertion order so saved JSON is in pin order. */
    private val pinnedInOrder = LinkedHashSet<String>()

    private var previousOnBaseMessage: ((String, String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pinned_apps)
        status = findViewById(R.id.pinnedStatus)
        listContainer = findViewById(R.id.pinnedAppsList)
        saveBtn = findViewById(R.id.pinnedSaveBtn)
        clearBtn = findViewById(R.id.pinnedClearBtn)

        // Seed pinned set from the phone's last-known list so the user
        // sees their current pins checked even before glass replies.
        prefs().getString(KEY_PINNED_CSV, "")
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.take(MAX_PINNED)
            ?.forEach { pinnedInOrder.add(it.trim()) }

        startService(android.content.Intent(this, PluginHostService::class.java))

        saveBtn.setOnClickListener { saveAndExit() }
        clearBtn.setOnClickListener {
            pinnedInOrder.clear()
            rows.forEach { it.checked = false }
            refreshUi()
        }

        attachGlassListener()
        requestList()
    }

    override fun onDestroy() {
        // Restore whatever was listening to onBaseMessage before we
        // hijacked it (typically nothing in our codebase, but be safe).
        BridgeService.instance?.onBaseMessage = previousOnBaseMessage
        super.onDestroy()
    }

    /** Subscribe to the LAUNCHER_APPS_LIST reply from glass. */
    private fun attachGlassListener() {
        val bridge = BridgeService.instance
        previousOnBaseMessage = bridge?.onBaseMessage
        bridge?.onBaseMessage = { type, payload ->
            if (type == "LAUNCHER_APPS_LIST") {
                Handler(Looper.getMainLooper()).post { renderList(payload) }
            }
            // Forward other base messages to whatever was listening before
            // us so we don't swallow them.
            previousOnBaseMessage?.invoke(type, payload)
        }
    }

    private fun requestList() {
        val bridge = BridgeService.instance
        if (bridge == null || !bridge.isConnected) {
            status.text = "Glass not connected — connect to load your installed apps."
            return
        }
        status.text = "Loading apps from glass…"
        bridge.requestLauncherApps()
    }

    private fun renderList(payload: String) {
        rows.clear()
        try {
            val arr = JSONObject(payload).optJSONArray("apps") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.optString("pkg", "")
                val label = o.optString("label", pkg)
                if (pkg.isBlank()) continue
                rows.add(AppRow(pkg = pkg, label = label, checked = pkg in pinnedInOrder))
            }
            // Sort: pinned first (in pin order), then everyone else
            // alphabetical by label.
            val pinOrder = pinnedInOrder.withIndex().associate { it.value to it.index }
            rows.sortWith(compareBy<AppRow> {
                pinOrder[it.pkg] ?: Int.MAX_VALUE
            }.thenBy { it.label.lowercase() })
            status.text = "Pick up to $MAX_PINNED apps — currently ${pinnedInOrder.size} pinned."
        } catch (e: Exception) {
            status.text = "Couldn't read app list: ${e.message}"
        }
        refreshUi()
    }

    private fun refreshUi() {
        listContainer.removeAllViews()
        for (row in rows) addRowView(row)
        saveBtn.isEnabled = true
    }

    private fun addRowView(row: AppRow) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val cb = CheckBox(this)
        cb.isChecked = row.checked
        cb.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (pinnedInOrder.size >= MAX_PINNED) {
                    Toast.makeText(
                        this@PinnedAppsActivity,
                        "Max $MAX_PINNED pinned apps — uncheck one first.",
                        Toast.LENGTH_SHORT
                    ).show()
                    cb.isChecked = false
                    return@setOnCheckedChangeListener
                }
                pinnedInOrder.add(row.pkg)
                row.checked = true
            } else {
                pinnedInOrder.remove(row.pkg)
                row.checked = false
            }
            status.text = "Pick up to $MAX_PINNED apps — currently ${pinnedInOrder.size} pinned."
        }
        val labelView = TextView(this)
        labelView.text = row.label
        labelView.textSize = 15f
        val pkgView = TextView(this)
        pkgView.text = row.pkg
        pkgView.textSize = 11f
        pkgView.setTextColor(0xFF90A4AE.toInt())
        val labelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        labelCol.addView(labelView)
        labelCol.addView(pkgView)
        container.addView(cb)
        container.addView(labelCol)
        listContainer.addView(container)
    }

    private fun saveAndExit() {
        val pkgs = pinnedInOrder.toList()
        prefs().edit().putString(KEY_PINNED_CSV, pkgs.joinToString(",")).apply()
        val bridge = BridgeService.instance
        val ok = bridge?.setPinnedApps(pkgs) ?: false
        Toast.makeText(
            this,
            if (ok) "Pinned ${pkgs.size} app${if (pkgs.size == 1) "" else "s"} on glass"
            else "Glass not connected — pin list saved locally + retried on next connect",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
