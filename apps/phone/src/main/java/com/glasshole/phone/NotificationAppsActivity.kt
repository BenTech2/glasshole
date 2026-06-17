package com.glasshole.phone

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.service.BridgeService
import com.glasshole.phone.service.NotificationForwardingService
import com.google.android.material.materialswitch.MaterialSwitch

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
    private val silentAllowed = mutableSetOf<String>()
    private lateinit var adapter: AppsAdapter
    private lateinit var countText: TextView

    private var query: String = ""
    private var showAll: Boolean = false

    companion object {
        private const val PREF_SHOW_ALL = "notif_apps_show_all"
        /** Local mirror of the per-app sound choice (mirrored on the
         *  glass via SET_NOTIF_APP_SOUND). Key = package name, value
         *  = sound ID. Empty string / missing = "Default". */
        private const val PER_APP_SOUNDS_PREFS = "notif_app_sounds"
        /** Display order matches glass-side NotifSoundPlayer.TONES. */
        private val BUNDLED_TONES = listOf(
            "tone:beep" to "Beep",
            "tone:beep2" to "Beep 2",
            "tone:ack" to "Ack",
            "tone:nack" to "Nack",
            "tone:prompt" to "Prompt",
            "tone:chime" to "Chime",
        )
        /** Glass-side ToneGenerator codes — kept in sync with
         *  NotifSoundPlayer.TONES so the preview the user hears on
         *  the phone matches the actual sound on glass. */
        private val PREVIEW_TONE_CODES = mapOf(
            "tone:beep" to ToneGenerator.TONE_PROP_BEEP,
            "tone:beep2" to ToneGenerator.TONE_PROP_BEEP2,
            "tone:ack" to ToneGenerator.TONE_PROP_ACK,
            "tone:nack" to ToneGenerator.TONE_PROP_NACK,
            "tone:prompt" to ToneGenerator.TONE_PROP_PROMPT,
            "tone:chime" to ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
        )
    }

    /** Cached uploaded-sound filenames returned by the most recent
     *  NOTIF_SOUND_LIST query. Empty until the picker is opened. */
    private var glassSoundFiles: List<String> = emptyList()

    /** SAF file picker for the "Upload new sound" action. The pending
     *  package is the row whose picker is open — set when the user
     *  taps "Upload…" so we know which app to apply the new sound to
     *  after the upload completes. */
    private var pendingUploadForPkg: String? = null
    private val pickSoundLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val pkg = pendingUploadForPkg
        pendingUploadForPkg = null
        if (uri != null && pkg != null) handlePickedSound(pkg, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_apps)

        val searchBox = findViewById<EditText>(R.id.searchBox)
        val listView = findViewById<ListView>(R.id.appsList)
        val emptyView = findViewById<TextView>(R.id.emptyView)
        val advancedSwitch = findViewById<MaterialSwitch>(R.id.advancedSwitch)
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
        silentAllowed.clear()
        silentAllowed.addAll(
            prefs.getStringSet(NotificationForwardingService.PREF_SILENT_ALLOWED_APPS, emptySet())
                ?: emptySet()
        )
    }

    private fun saveSelectedApps() {
        getSharedPreferences(NotificationForwardingService.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putStringSet(NotificationForwardingService.PREF_FORWARDED_APPS, enabled.toSet())
            .putStringSet(NotificationForwardingService.PREF_SILENT_ALLOWED_APPS, silentAllowed.toSet())
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
            val silentSwitch = view.findViewById<MaterialSwitch>(R.id.silentSwitch)
            val soundChip = view.findViewById<TextView>(R.id.soundChip)

            icon.setImageDrawable(
                app.icon ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, theme)
            )
            label.text = app.label
            pkgText.text = app.pkg

            val isEnabled = app.pkg in enabled
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = isEnabled
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    enabled.add(app.pkg)
                } else {
                    enabled.remove(app.pkg)
                    // Disabling forwarding removes any silent allowance
                    // for the app — leaving a stale entry in the silent
                    // set would re-allow it the moment the user re-enables
                    // forwarding, which is surprising.
                    silentAllowed.remove(app.pkg)
                }
                saveSelectedApps()
                silentSwitch.visibility = if (isChecked) View.VISIBLE else View.GONE
                silentSwitch.isChecked = app.pkg in silentAllowed
            }

            // The silent sub-toggle is only meaningful when the app is
            // already being forwarded — hide it otherwise.
            silentSwitch.setOnCheckedChangeListener(null)
            silentSwitch.visibility = if (isEnabled) View.VISIBLE else View.GONE
            silentSwitch.isChecked = app.pkg in silentAllowed
            silentSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) silentAllowed.add(app.pkg) else silentAllowed.remove(app.pkg)
                saveSelectedApps()
            }

            // Per-app sound chip — same visibility gating as silent
            // switch. Label shows current selection so the user can
            // tell at a glance without opening the picker.
            soundChip.visibility = if (isEnabled) View.VISIBLE else View.GONE
            soundChip.text = "Sound: ${displayLabelForSoundId(currentSoundIdFor(app.pkg))}"
            soundChip.setOnClickListener { showSoundPicker(app.pkg) { newId ->
                soundChip.text = "Sound: ${displayLabelForSoundId(newId)}"
            } }

            return view
        }
    }

    // ──────────────────── per-app sound: storage + picker ────────────────────

    private fun perAppSoundsPrefs() =
        getSharedPreferences(PER_APP_SOUNDS_PREFS, MODE_PRIVATE)

    private fun currentSoundIdFor(pkg: String): String =
        perAppSoundsPrefs().getString(pkg, "") ?: ""

    private fun displayLabelForSoundId(id: String): String = when {
        id.isEmpty() || id == "default" -> "Default"
        id.startsWith("tone:") -> {
            val key = id.removePrefix("tone:")
            BUNDLED_TONES.firstOrNull { it.first == id }?.second ?: key
        }
        id.startsWith("file:") -> id.removePrefix("file:")
        else -> id
    }

    /** Open the AlertDialog picker for [pkg]. Calls [onChosen] with
     *  the new sound ID (after persisting + sending the BT message)
     *  so the row can refresh its chip without re-binding the list. */
    private fun showSoundPicker(pkg: String, onChosen: (String) -> Unit) {
        // Build the option list: Default + bundled tones + currently
        // uploaded files. The uploaded-files list comes from the most
        // recent glass-side query — refresh on every picker open so a
        // delete/upload from another device shows up.
        BridgeService.instance?.queryNotifSoundList { files, err ->
            runOnUiThread {
                if (err != null) {
                    // Soft-fail: still show the dialog with whatever
                    // we had cached, with a toast about the issue.
                    Toast.makeText(this, "Sound list: $err", Toast.LENGTH_SHORT).show()
                } else {
                    glassSoundFiles = files
                }
                buildAndShowPicker(pkg, onChosen)
            }
        } ?: run {
            // Bridge not bound — still let the user pick a bundled
            // tone; uploaded list will be empty until next time.
            buildAndShowPicker(pkg, onChosen)
        }
    }

    private fun buildAndShowPicker(pkg: String, onChosen: (String) -> Unit) {
        // Order: Default, bundled tones, uploaded files.
        val ids = mutableListOf<String>()
        val labels = mutableListOf<String>()
        ids.add(""); labels.add("Default (global beep)")
        for ((id, label) in BUNDLED_TONES) { ids.add(id); labels.add(label) }
        for (f in glassSoundFiles) { ids.add("file:$f"); labels.add(f) }

        val currentId = currentSoundIdFor(pkg)
        val initialIdx = ids.indexOf(currentId).takeIf { it >= 0 } ?: 0
        var chosenIdx = initialIdx

        AlertDialog.Builder(this)
            .setTitle("Notification sound")
            .setSingleChoiceItems(labels.toTypedArray(), initialIdx) { _, which ->
                chosenIdx = which
                // Audio preview only for bundled tones — uploaded files
                // live on the glass, no local copy on the phone to play.
                previewTone(ids[which])
            }
            .setPositiveButton("Use") { _, _ ->
                val newId = ids[chosenIdx]
                applySoundChoice(pkg, newId)
                onChosen(newId)
            }
            .setNeutralButton("Upload…") { _, _ ->
                pendingUploadForPkg = pkg
                pickSoundLauncher.launch("audio/*")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applySoundChoice(pkg: String, soundId: String) {
        val prefs = perAppSoundsPrefs().edit()
        if (soundId.isEmpty() || soundId == "default") prefs.remove(pkg)
        else prefs.putString(pkg, soundId)
        prefs.apply()
        BridgeService.instance?.setNotifAppSound(pkg, soundId)
    }

    private fun previewTone(id: String) {
        if (!id.startsWith("tone:")) return
        val toneCode = PREVIEW_TONE_CODES[id] ?: return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(toneCode, 300)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 500)
        } catch (_: Exception) {}
    }

    /** Read the picked audio bytes off the phone, push them to the
     *  glass over the BG-upload-style channel, then apply the picked
     *  file as the per-app sound. Runs decode + send on a worker. */
    private fun handlePickedSound(pkg: String, uri: Uri) {
        Thread {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Exception) { null }
            if (bytes == null) {
                runOnUiThread { Toast.makeText(this, "Couldn't read picked file", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            val filename = pickedAudioFilename(uri) ?: "sound.mp3"
            val bridge = BridgeService.instance
            if (bridge == null) {
                runOnUiThread { Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            bridge.uploadNotifSound(bytes, filename) { ok, msg ->
                runOnUiThread {
                    if (ok) {
                        // Apply the new file as the choice for this
                        // app and refresh the list so the user sees
                        // it ready for future picks too.
                        applySoundChoice(pkg, "file:$msg")
                        Toast.makeText(this, "Uploaded $msg", Toast.LENGTH_SHORT).show()
                        adapter.notifyDataSetChanged()
                    } else {
                        Toast.makeText(this, "Upload failed: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.apply { isDaemon = true; name = "NotifSound-pick" }.start()
    }

    private fun pickedAudioFilename(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
            }
        } catch (_: Exception) {}
        return name?.takeIf { it.isNotEmpty() }
    }
}
