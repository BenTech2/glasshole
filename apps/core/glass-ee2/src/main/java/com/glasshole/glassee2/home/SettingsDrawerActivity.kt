package com.glasshole.glassee2.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.glasshole.glassee2.R

/**
 * Cover-flow drawer of Android system settings — launched from Home's
 * Settings card. Each tile wraps a standard Settings intent so that
 * when GlassHole ships as a launcher replacement, the user still has
 * a path to every stock configuration screen.
 *
 * Tiles are hard-coded in [SETTINGS_ENTRIES] — tap to fire the intent,
 * swipe to navigate, swipe-down to return to Home.
 */
class SettingsDrawerActivity : Activity() {

    companion object {
        private const val SCROLL_MS = 220
        private const val PREFS = "settings_drawer"
        private const val KEY_LAST_INDEX = "last_index"

        private data class Entry(
            val label: String,
            val iconRes: Int,
            val action: String,
            val useAppPackage: Boolean = false
        )

        // Ordered so the user reaches the most common controls first
        // (Wi-Fi, Bluetooth, brightness, volume) without having to swipe
        // deep into the carousel.
        private val SETTINGS_ENTRIES: List<Entry> = listOf(
            Entry("Wi-Fi", R.drawable.ic_settings_wifi, Settings.ACTION_WIFI_SETTINGS),
            Entry("Bluetooth", R.drawable.ic_settings_bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS),
            Entry("Display", R.drawable.ic_settings_display, Settings.ACTION_DISPLAY_SETTINGS),
            Entry("Sound", R.drawable.ic_settings_sound, Settings.ACTION_SOUND_SETTINGS),
            Entry("Date & Time", R.drawable.ic_settings_datetime, Settings.ACTION_DATE_SETTINGS),
            Entry("Apps", R.drawable.ic_settings_apps, Settings.ACTION_APPLICATION_SETTINGS),
            Entry("Storage", R.drawable.ic_settings_storage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Entry("Accessibility", R.drawable.ic_settings_accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Entry("About", R.drawable.ic_settings_info, Settings.ACTION_DEVICE_INFO_SETTINGS),
            Entry("All Settings", R.drawable.ic_settings_gear, Settings.ACTION_SETTINGS)
        )
    }

    private lateinit var pager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_drawer)

        pager = findViewById(R.id.settingsPager)
        pager.offscreenPageLimit = 4
        pager.isUserInputEnabled = false
        pager.adapter = SettingsAdapter(SETTINGS_ENTRIES)
        pager.setPageTransformer(CoverFlowTransformer())

        // Same padding-on-inner-RV pattern as the app drawer so the
        // cover-flow transformer has the right page slot from frame one.
        (pager.getChildAt(0) as? RecyclerView)?.apply {
            val pad = (resources.displayMetrics.widthPixels * 0.28f).toInt()
            setPadding(pad, 0, pad, 0)
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        // Restore the previously selected tile so the drawer opens where
        // the user left off — survives both in-process reentry (swipe-down
        // from a launched settings activity) and a fresh open after the
        // process was killed.
        val lastIdx = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_INDEX, 0)
            .coerceIn(0, SETTINGS_ENTRIES.size - 1)
        if (lastIdx > 0) pager.setCurrentItem(lastIdx, false)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_LAST_INDEX, position)
                    .apply()
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_TAB -> {
                val dir = if (event?.isShiftPressed == true) -1 else 1
                glideBy(dir)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { glideBy(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { glideBy(-1); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                launchCurrent()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun glideBy(direction: Int) {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        val itemWidth = rv.width - rv.paddingLeft - rv.paddingRight
        if (itemWidth <= 0) return
        rv.smoothScrollBy(direction * itemWidth, 0, DecelerateInterpolator(), SCROLL_MS)
    }

    private fun launchCurrent() {
        val entry = SETTINGS_ENTRIES.getOrNull(pager.currentItem) ?: return
        val intent = Intent(entry.action).apply {
            if (entry.useAppPackage) {
                data = Uri.parse("package:$packageName")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Some Glass ROMs strip a subset of Settings activities —
            // fall back to the root Settings screen so the user still
            // has a path forward instead of a dead tap.
            Toast.makeText(this, "${entry.label} unavailable on this glass", Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
        // Intentionally NOT finish(): keep the drawer on the back stack so
        // swipe-down from the launched settings activity returns the user
        // to the same tile rather than jumping all the way back to Home.
    }

    private class SettingsAdapter(
        private val items: List<Entry>
    ) : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.page_setting, parent, false)
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val e = items[position]
            holder.itemView.findViewById<ImageView>(R.id.settingIcon)?.setImageResource(e.iconRes)
            val label = holder.itemView.findViewById<TextView>(R.id.settingLabel)
            label?.text = e.label
            label?.alpha = 1f  // reset in case recycled from a faded slot
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)
}
