package com.glasshole.plugin.gallery2.glass

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import kotlin.math.abs
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Cover Flow style media browser. Uses ViewPager2 with a PageTransformer
 * that scales / rotates / overlaps sibling pages to reproduce the iPod
 * carousel look. Structure deliberately mirrors the Home app drawer so
 * EE2's touchpad gesture → keycode pipeline lights up the same way.
 */
class CoverFlowActivity : Activity() {

    companion object {
        private const val REQUEST_PERMS = 201
        /** Duration of one keycode's smooth scroll. Short enough that
         *  stacked TABs from consecutive swipes feel like one motion. */
        private const val SCROLL_MS = 220
    }

    private lateinit var pager: ViewPager2
    private lateinit var emptyText: TextView
    private lateinit var titleText: TextView
    private var items: List<MediaItem> = emptyList()

    // EE2 touchpad gesture tracking — on plugin APKs the system doesn't
    // deliver swipes as TAB keycodes the way it does for activities inside
    // the base glass-ee2 package. We read MotionEvents directly instead,
    // matching the pattern the Notes / Chat plugins use.
    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cover_flow)

        pager = findViewById(R.id.coverPager)
        titleText = findViewById(R.id.titleText)
        emptyText = findViewById(R.id.emptyText)

        pager.offscreenPageLimit = 4
        pager.isUserInputEnabled = false
        pager.setPageTransformer(CoverFlowTransformer())

        (pager.getChildAt(0) as? RecyclerView)?.apply {
            val pad = (resources.displayMetrics.widthPixels * 0.28f).toInt()
            setPadding(pad, 0, pad, 0)
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        if (!hasReadPermission()) {
            ActivityCompat.requestPermissions(this, readPermissionArray(), REQUEST_PERMS)
        } else {
            loadMedia()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasReadPermission() && items.isNotEmpty()) {
            // Rescan in case new photos were captured via the camera plugin
            loadMedia()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMS) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                loadMedia()
            } else {
                emptyText.text = "Storage permission denied"
                emptyText.visibility = View.VISIBLE
            }
        }
    }

    private fun hasReadPermission(): Boolean {
        val perms = readPermissionArray()
        return perms.any {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun readPermissionArray(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun loadMedia() {
        // Preserve scroll position across reloads (onResume fires one when
        // the viewer activity closes) — otherwise the user lands back on
        // image #1 every time.
        val previousKey = items.getOrNull(pager.currentItem)?.file?.absolutePath
        val alreadyInitialised = pager.adapter != null

        object : AsyncTask<Void, Void, List<MediaItem>>() {
            override fun doInBackground(vararg params: Void?): List<MediaItem> =
                MediaScanner.scan(applicationContext)

            override fun onPostExecute(result: List<MediaItem>) {
                // Fast path: if the file list matches what we already have,
                // leave the adapter and scroll position alone entirely.
                if (alreadyInitialised &&
                    result.size == items.size &&
                    result.zip(items).all { (a, b) -> a.file.absolutePath == b.file.absolutePath }
                ) {
                    return
                }
                items = result
                if (items.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    titleText.visibility = View.GONE
                    return
                }
                emptyText.visibility = View.GONE
                titleText.visibility = View.VISIBLE
                pager.adapter = CoverFlowAdapter(this@CoverFlowActivity, items)
                pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        updateTitle(position)
                    }
                })
                // If the previously-centered item still exists, jump back
                // to it. Otherwise fall back to the closest index.
                val restoreIdx = previousKey
                    ?.let { key -> items.indexOfFirst { it.file.absolutePath == key } }
                    ?.takeIf { it >= 0 } ?: 0
                pager.setCurrentItem(restoreIdx, false)
                updateTitle(restoreIdx)
            }
        }.execute()
    }

    private fun updateTitle(position: Int) {
        val item = items.getOrNull(position) ?: return
        titleText.text = "${item.file.name}  —  ${position + 1}/${items.size}"
    }

    private fun openViewer(item: MediaItem) {
        val intent = Intent(this, MediaViewActivity::class.java)
            .putExtra(MediaViewActivity.EXTRA_PATH, item.file.absolutePath)
            .putExtra(MediaViewActivity.EXTRA_IS_VIDEO, item.isVideo)
        startActivity(intent)
    }

    // EE2's touchpad surfaces via onTouchEvent, EE1/XE via
    // onGenericMotionEvent. Both route through the same handler so
    // gestures feel identical across all three variants.
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && handleTouchpad(event)) return true
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && handleTouchpad(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleTouchpad(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiped = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                // Swipe-down = back (Glass's standard exit gesture).
                if (dy > 80f && abs(dy) > abs(dx)) {
                    finish()
                    return true
                }
                // Forward swipe (finger right, dx > 0) advances to the
                // next item — matches every other carousel surface in the
                // app (Home cover-flow drawer, Settings drawer) and the
                // KEYCODE_TAB path below. Earlier the sign was flipped
                // for an "album paging" feel but it was inconsistent with
                // the rest of the system.
                if (!swiped && abs(dx) > 30f && abs(dx) > abs(dy)) {
                    swiped = true
                    glideBy(if (dx > 0f) 1 else -1)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                // A release without a detected swipe = tap = open viewer.
                if (!swiped) {
                    items.getOrNull(pager.currentItem)?.let { openViewer(it) }
                    return true
                }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                items.getOrNull(pager.currentItem)?.let { openViewer(it) }
                true
            }
            KeyEvent.KEYCODE_TAB -> {
                val dir = if (event?.isShiftPressed == true) -1 else 1
                glideBy(dir)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { glideBy(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { glideBy(-1); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Pixel-level smooth pan on the ViewPager2's inner RecyclerView —
     * mirrors the Home app drawer's scroll so successive swipes stack
     * into one continuous glide and the snap helper lands on the
     * closest item.
     */
    private fun glideBy(direction: Int) {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        val itemWidth = rv.width - rv.paddingLeft - rv.paddingRight
        if (itemWidth <= 0) return
        rv.smoothScrollBy(direction * itemWidth, 0, DecelerateInterpolator(), SCROLL_MS)
    }
}
