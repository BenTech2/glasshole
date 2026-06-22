package com.glasshole.glassxe.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.glasshole.glassxe.R

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
        /** Each half of the overlay-label fade transition (out, then in). */
        private const val FADE_MS = 110L

        /** Tile fires either a system [action] or an in-launcher
         *  [internalActivity]. See EE2 copy for the design note. */
        private data class Entry(
            val label: String,
            val iconRes: Int,
            val action: String,
            val useAppPackage: Boolean = false,
            val internalActivity: Class<out Activity>? = null,
        )

        // Ordered so the user reaches the most common controls first.
        // Dev Tools is last — deliberate destination, not a frequent flow.
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
            Entry("All Settings", R.drawable.ic_settings_gear, Settings.ACTION_SETTINGS),
            Entry(
                label = "Dev Tools",
                iconRes = R.drawable.ic_settings_devtools,
                action = "",
                internalActivity = com.glasshole.glassxe.devtools.DevToolsActivity::class.java,
            ),
        )
    }

    private lateinit var pager: ViewPager2
    private lateinit var overlayLabel: TextView
    private lateinit var drawerBackground: DrawerBackground
    /** SETTINGS_ENTRIES filtered to only those whose intent the device can
     *  actually handle. Glass XE's stock Settings ships a much narrower
     *  subset than EE2 — pre-filtering at runtime via PackageManager
     *  removes dead tiles entirely instead of relying on launchCurrent's
     *  ActivityNotFound fallback. */
    private lateinit var entries: List<Entry>
    /** Position whose label the overlay currently shows. We only swap the
     *  overlay text once the pager has fully settled on a new tile. */
    private var shownPosition: Int = -1
    /** Target position after the in-flight glideBy completes, tracked
     *  manually since ViewPager2's callbacks don't fire reliably with
     *  our offset-padded RecyclerView setup. */
    private var pendingPosition: Int = 0

    private val labelHandler = Handler(Looper.getMainLooper())
    private val labelUpdateRunnable = Runnable {
        if (pendingPosition != shownPosition) {
            crossfadeOverlayLabel(entries.getOrNull(pendingPosition)?.label ?: "")
            shownPosition = pendingPosition
            // Persist the latest tile so swipe-down from a launched
            // activity returns the user to the same spot. Replaces the
            // OnPageChangeCallback.onPageSelected that handled this
            // before we switched to manual position tracking.
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_LAST_INDEX, pendingPosition)
                .apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_drawer)

        entries = SETTINGS_ENTRIES.filter {
            // Always keep ACTION_SETTINGS and any internal-activity
            // tile — we control the latter's target ourselves.
            it.internalActivity != null
                || it.action == Settings.ACTION_SETTINGS
                || resolvesActivity(it.action)
        }

        pager = findViewById(R.id.settingsPager)
        overlayLabel = findViewById(R.id.overlayLabel)
        drawerBackground = DrawerBackground(
            activity = this,
            backgroundImage = findViewById(R.id.backgroundImage),
            backgroundFade = findViewById(R.id.backgroundFade),
            enabledPrefKey = com.glasshole.glassxe.BaseSettings.KEY_WALLPAPER_ON_SETTINGS,
        )
        pager.offscreenPageLimit = 4
        pager.isUserInputEnabled = false
        pager.adapter = SettingsAdapter(entries)
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
            .coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        if (lastIdx > 0) pager.setCurrentItem(lastIdx, false)

        // Seed the overlay with the restored page's label — no fade since
        // the drawer just opened and there's nothing to transition from.
        overlayLabel.text = entries.getOrNull(lastIdx)?.label ?: ""
        shownPosition = lastIdx
        pendingPosition = lastIdx

        // Force software rendering on the chip + its wrapper. KitKat's
        // hardware-accelerated alpha + width-change combo leaves edge
        // ghosting around the chip as it re-centers on each text swap.
        overlayLabel.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        (overlayLabel.parent as? View)?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    override fun onResume() {
        super.onResume()
        drawerBackground.attach()
    }

    override fun onPause() {
        super.onPause()
        drawerBackground.detach()
    }

    /** Fade the overlay label out, swap its text, fade back in.
     *  The wrapper FrameLayout has animateLayoutChanges=true so the chip
     *  background morphs to the new text width during the alpha-0 window. */
    private fun crossfadeOverlayLabel(newText: String) {
        overlayLabel.animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction {
                overlayLabel.text = newText
                overlayLabel.animate()
                    .alpha(1f)
                    .setDuration(FADE_MS)
                    .start()
            }
            .start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_TAB -> {
                val fwd = fwdSign()
                glideBy(if (event?.isShiftPressed == true) -fwd else fwd)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { glideBy(fwdSign()); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { glideBy(-fwdSign()); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                launchCurrent()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // EE1 / XE deliver tap + swipe as MotionEvents (SOURCE_TOUCHPAD).
    // EE2 gets key events via its GDK gesture detector, which onKeyDown
    // above already handles. Both paths route to glideBy / launchCurrent
    // / finish for consistency.
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleGesture(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)
                if (dy > 120 && absDy > absDx * 1.3f) { finish(); return true }
                if (absDx > 60 && absDx > absDy) {
                    val fwd = fwdSign()
                    glideBy(if (dx > 0) fwd else -fwd); return true
                }
                if (absDx < 25 && absDy < 25) { launchCurrent(); return true }
            }
        }
        return false
    }

    private fun glideBy(direction: Int) {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        val itemWidth = rv.width - rv.paddingLeft - rv.paddingRight
        if (itemWidth <= 0) return
        rv.smoothScrollBy(direction * itemWidth, 0, DecelerateInterpolator(), SCROLL_MS)

        val newPos = (pendingPosition + direction).coerceIn(0, entries.size - 1)
        if (newPos != pendingPosition) {
            pendingPosition = newPos
            labelHandler.removeCallbacks(labelUpdateRunnable)
            labelHandler.postDelayed(labelUpdateRunnable, SCROLL_MS.toLong() + 30L)
        }
    }

    private fun resolvesActivity(action: String): Boolean {
        return try {
            packageManager.resolveActivity(Intent(action), 0) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun launchCurrent() {
        val entry = entries.getOrNull(pendingPosition) ?: return
        if (entry.internalActivity != null) {
            try {
                startActivity(Intent(this, entry.internalActivity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this, "${entry.label} failed to open", Toast.LENGTH_SHORT).show()
            }
            return
        }
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
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)

    private fun fwdSign(): Int =
        if (com.glasshole.glassxe.BaseSettings.isNavInverted(this)) -1 else 1
}
