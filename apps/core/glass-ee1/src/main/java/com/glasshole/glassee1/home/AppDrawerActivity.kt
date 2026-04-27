package com.glasshole.glassee1.home

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.glasshole.glassee1.R

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_drawer)

        pager = findViewById(R.id.appPager)
        emptyText = findViewById(R.id.emptyText)

        val apps = loadLauncherApps()
        if (apps.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }

        adapter = AppAdapter(apps)
        pager.adapter = adapter
        // Keep plenty of neighbors alive so the denser cover-flow has
        // real icons to tilt/shrink on each side.
        pager.offscreenPageLimit = 6
        pager.isUserInputEnabled = false
        pager.setPageTransformer(CoverFlowTransformer())

        // Moderate side padding — the cover-flow transformer's inner-edge
        // pivot already tilts neighbors back into the depth, so we don't
        // need to shrink the page slot as aggressively. Apply padding
        // synchronously so the *first* layout pass uses the right page
        // slot width. Since the pager fills the display, screen width is
        // the right source for this.
        val inner = pager.getChildAt(0) as? RecyclerView
        if (inner != null) {
            val pad = (resources.displayMetrics.widthPixels * 0.28f).toInt()
            inner.setPadding(pad, 0, pad, 0)
            inner.clipToPadding = false
            inner.clipChildren = false
        }
        pager.clipChildren = false
        pager.clipToPadding = false
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

    // EE1 / XE deliver tap + swipe as MotionEvents (SOURCE_TOUCHPAD).
    // EE2 gets key events via its GDK gesture detector, which onKeyDown
    // above already handles.
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
                    glideBy(if (dx > 0) 1 else -1); return true
                }
                if (absDx < 25 && absDy < 25) { launchCurrent(); return true }
            }
        }
        return false
    }

    /**
     * Pixel-level smooth pan on the ViewPager2's inner RecyclerView.
     * Each keycode adds another page-width of scroll, rapid keycodes
     * accumulate into a single continuous glide, and ViewPager2's
     * built-in PagerSnapHelper snaps to the nearest page when the
     * scroll settles.
     */
    private fun glideBy(direction: Int) {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        val itemWidth = rv.width - rv.paddingLeft - rv.paddingRight
        if (itemWidth <= 0) return
        rv.smoothScrollBy(direction * itemWidth, 0, DecelerateInterpolator(), SCROLL_MS)
    }

    private fun launchCurrent() {
        if (!::adapter.isInitialized) return
        val pkg = adapter.pkgAt(pager.currentItem) ?: return
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
        return apps.sortedWith(
            compareByDescending<AppEntry> { it.isGlassHole }
                .thenBy { it.label.lowercase() }
        )
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
            val label = holder.itemView.findViewById<TextView>(R.id.appLabel)
            label?.text = e.label
            // Reset alpha in case this view was recycled from a faded-out
            // neighbor slot. The transformer will override on the next pass
            // if this page isn't the center.
            label?.alpha = 1f
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)
}
