package com.glasshole.glassee2.home

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.glasshole.glassee2.R

/**
 * Cover-flow app drawer. Opens from Home's Time card. Lists every
 * launcher-capable activity on the device; GlassHole plugin packages
 * (`com.glasshole.plugin.*`) are pinned to the front so the user
 * doesn't have to swipe past unrelated Glass system apps to reach
 * ours.
 *
 * Gestures: swipe forward/back to page, tap to launch, swipe down to
 * close drawer and return to Home.
 */
class AppDrawerActivity : Activity() {

    companion object {
        /** Duration of one keycode's smooth scroll. Short enough that
         *  stacked TABs from consecutive swipes feel like one motion. */
        private const val SCROLL_MS = 220
        /** Each half of the overlay-label fade transition (out, then in). */
        private const val FADE_MS = 110L
    }

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val icon: Drawable?,
        val isGlassHole: Boolean
    )

    private lateinit var pager: ViewPager2
    private lateinit var adapter: AppAdapter
    private lateinit var emptyText: TextView
    private lateinit var overlayLabel: TextView
    private lateinit var apps: List<AppEntry>

    /** Position whose label the overlay currently shows. We only swap the
     *  overlay text once the cover-flow scroll has fully settled. */
    private var shownPosition: Int = -1
    /** Target position after the in-flight glideBy completes — updated
     *  synchronously when the swipe is recognized, not via ViewPager2's
     *  callbacks (which don't reliably fire with our offset-padding
     *  RecyclerView setup). */
    private var pendingPosition: Int = 0

    private val labelHandler = Handler(Looper.getMainLooper())
    private val labelUpdateRunnable = Runnable {
        if (pendingPosition != shownPosition) {
            crossfadeOverlayLabel(apps.getOrNull(pendingPosition)?.label ?: "")
            shownPosition = pendingPosition
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_drawer)

        pager = findViewById(R.id.appPager)
        emptyText = findViewById(R.id.emptyText)
        overlayLabel = findViewById(R.id.overlayLabel)

        apps = loadLauncherApps()
        if (apps.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }

        adapter = AppAdapter(apps)
        pager.adapter = adapter

        // Seed the overlay with the initial page's label — no fade since
        // the drawer just opened and there's nothing to transition from.
        overlayLabel.text = apps[0].label
        shownPosition = 0
        pendingPosition = 0

        // Force software rendering on the chip + its wrapper. KitKat's
        // hardware-accelerated alpha + width-change combo leaves edge
        // ghosting around the chip as it re-centers on each text swap;
        // CPU rendering of one tiny TextView is cheap and pixel-clean.
        overlayLabel.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        (overlayLabel.parent as? View)?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        // Keep 4 pages on each side in memory so the transformer has
        // something to tilt/shrink — cover flow without neighbors loaded
        // just looks like a regular single-page pager.
        // Keep plenty of neighbors alive so the denser cover-flow has
        // real icons to tilt/shrink on each side.
        pager.offscreenPageLimit = 6
        pager.isUserInputEnabled = false
        pager.setPageTransformer(CoverFlowTransformer())

        // Moderate side padding — the cover-flow transformer's inner-edge
        // pivot already tilts neighbors back into the depth, so we don't
        // need to shrink the page slot as aggressively. Apply padding
        // synchronously so the *first* layout pass uses the right page
        // slot width — deferring this to inner.post made the drawer open
        // mid-transform until the user swiped. Since the pager fills the
        // display, screen width is the right source for this.
        val inner = pager.getChildAt(0) as? RecyclerView
        if (inner != null) {
            val pad = (resources.displayMetrics.widthPixels * 0.28f).toInt()
            inner.setPadding(pad, 0, pad, 0)
            inner.clipToPadding = false
            inner.clipChildren = false
        }
        // Also stop the pager from clipping its children so rotated
        // neighbors that stick out the sides aren't chopped.
        pager.clipChildren = false
        pager.clipToPadding = false
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

    /**
     * Pixel-level smooth pan on the ViewPager2's inner RecyclerView.
     * Same mechanism the Notes / Chat reader uses with ScrollView —
     * each keycode adds another page-width of scroll, rapid keycodes
     * accumulate into a single continuous glide, and ViewPager2's
     * built-in PagerSnapHelper snaps to the nearest page when the
     * scroll settles.
     */
    private fun glideBy(direction: Int) {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        // With clipToPadding=false + 25% side padding, each MATCH_PARENT
        // child is rv.width - 2*padding wide (about 50% of the view).
        val itemWidth = rv.width - rv.paddingLeft - rv.paddingRight
        if (itemWidth <= 0) return
        rv.smoothScrollBy(direction * itemWidth, 0, DecelerateInterpolator(), SCROLL_MS)

        // Advance our tracked position and schedule the overlay label
        // swap so it fires once the cover-flow has finished animating.
        // Doing this manually (rather than via an OnPageChangeCallback
        // reading pager.currentItem) avoids ViewPager2's tracking
        // quirks with our offset-padded RecyclerView.
        val newPos = (pendingPosition + direction).coerceIn(0, apps.size - 1)
        if (newPos != pendingPosition) {
            pendingPosition = newPos
            labelHandler.removeCallbacks(labelUpdateRunnable)
            labelHandler.postDelayed(labelUpdateRunnable, SCROLL_MS.toLong() + 30L)
        }
    }

    private fun launchCurrent() {
        if (!::adapter.isInitialized) return
        val pkg = adapter.pkgAt(pendingPosition) ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(launch) } catch (_: Exception) {}
        finish()
    }

    private fun loadLauncherApps(): List<AppEntry> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = pm.queryIntentActivities(launcherIntent, 0)
        val seen = mutableSetOf<String>()
        val apps = mutableListOf<AppEntry>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == packageName) continue // hide ourselves (GlassHole base)
            if (!seen.add(pkg)) continue
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
            apps.add(
                AppEntry(
                    pkg = pkg,
                    label = label,
                    icon = icon,
                    isGlassHole = pkg.startsWith("com.glasshole.")
                )
            )
        }
        // GlassHole plugins pinned to the front, then everything else alphabetical.
        return apps.sortedWith(
            compareByDescending<AppEntry> { it.isGlassHole }
                .thenBy { it.label.lowercase() }
        )
    }

    /** Fade the overlay label out, swap its text, then fade it back in.
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

    private class AppAdapter(private val items: List<AppEntry>) :
        RecyclerView.Adapter<PageHolder>() {

        fun pkgAt(position: Int): String? = items.getOrNull(position)?.pkg

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.page_app, parent, false)
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val e = items[position]
            holder.itemView.findViewById<ImageView>(R.id.appIcon)?.setImageDrawable(e.icon)
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)

    /** +1 default, -1 when the user has flipped "Invert glass nav"
     *  on the phone. Read fresh on each gesture for live response. */
    private fun fwdSign(): Int =
        if (com.glasshole.glassee2.BaseSettings.isNavInverted(this)) -1 else 1
}
