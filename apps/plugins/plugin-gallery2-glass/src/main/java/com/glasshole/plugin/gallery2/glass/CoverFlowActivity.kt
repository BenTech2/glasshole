package com.glasshole.plugin.gallery2.glass

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
 *
 * The bottom title chip is updated via a crossfade once the cover
 * flow settles on a new item, not during the scroll itself —
 * matching the AppDrawerActivity behavior. Position is tracked
 * manually inside [glideBy] because ViewPager2's own page-change
 * callbacks fire unreliably with our offset-padded RecyclerView.
 */
class CoverFlowActivity : Activity() {

    companion object {
        private const val REQUEST_PERMS = 201
        /** Duration of one keycode's smooth scroll. Short enough that
         *  stacked TABs from consecutive swipes feel like one motion. */
        private const val SCROLL_MS = 220
        /** Each half of the title-chip fade transition (out, then in). */
        private const val FADE_MS = 110L
        /** Duration of the zoom-to-fullscreen animation that runs
         *  before MediaViewActivity is launched. Long enough to read
         *  as a smooth grow, short enough not to feel laggy. */
        private const val OPEN_ANIM_MS = 280L
        /** Tag we stick on the open-animation overlay so we can find
         *  + remove it on return without keeping a field around. */
        private const val OVERLAY_TAG = "gallery_open_overlay"
    }

    private lateinit var pager: ViewPager2
    private lateinit var emptyText: TextView
    private lateinit var titleText: TextView
    private var items: List<MediaItem> = emptyList()

    /** Index whose label the title chip currently shows. */
    private var shownPosition: Int = -1
    /** Target index after the in-flight glideBy completes — updated
     *  synchronously when the swipe is recognized, not via
     *  ViewPager2's callbacks. */
    private var pendingPosition: Int = 0

    private val labelHandler = Handler(Looper.getMainLooper())
    private val labelUpdateRunnable = Runnable {
        if (pendingPosition != shownPosition) {
            crossfadeTitle(titleFor(pendingPosition))
            shownPosition = pendingPosition
        }
    }

    // EE2 touchpad gesture tracking — on plugin APKs the system doesn't
    // deliver swipes as TAB keycodes the way it does for activities inside
    // the base glass-ee2 package. We read MotionEvents directly instead,
    // matching the pattern the Notes / Chat plugins use.
    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    /**
     * EE2 reverses gallery swipe direction relative to other
     * carousels (see handleGesture / onKeyDown). EE1 and XE keep the
     * project-standard convention because their raw MotionEvent
     * touchpad already feels right in the original direction.
     * Detected by API level — EE2 is the only edition at API 27+.
     */
    private val galleryReverseSwipe: Boolean = Build.VERSION.SDK_INT >= 27

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cover_flow)

        pager = findViewById(R.id.coverPager)
        titleText = findViewById(R.id.titleText)
        emptyText = findViewById(R.id.emptyText)

        pager.offscreenPageLimit = 4
        pager.isUserInputEnabled = false
        pager.setPageTransformer(CoverFlowTransformer())
        pager.clipChildren = false
        pager.clipToPadding = false

        (pager.getChildAt(0) as? RecyclerView)?.apply {
            val pad = (resources.displayMetrics.widthPixels * 0.28f).toInt()
            setPadding(pad, 0, pad, 0)
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        // Force software rendering on the title chip + its wrapper.
        // KitKat's hardware-accelerated alpha + width-change combo
        // leaves edge ghosting around the chip as it re-centers on
        // each filename swap; CPU rendering of one tiny TextView is
        // cheap and pixel-clean.
        titleText.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        (titleText.parent as? View)?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        if (!hasReadPermission()) {
            ActivityCompat.requestPermissions(this, readPermissionArray(), REQUEST_PERMS)
        } else {
            loadMedia()
        }
    }

    override fun onResume() {
        super.onResume()
        // Clear the zoom overlay + restore pager/title alpha if we
        // got here from a MediaViewActivity launch. Cheap no-op
        // when nothing's been hidden.
        cleanupOpenAnimation()
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
        val previousKey = items.getOrNull(pendingPosition)?.file?.absolutePath
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
                // If the previously-centered item still exists, jump back
                // to it. Otherwise fall back to the closest index.
                val restoreIdx = previousKey
                    ?.let { key -> items.indexOfFirst { it.file.absolutePath == key } }
                    ?.takeIf { it >= 0 } ?: 0
                pager.setCurrentItem(restoreIdx, false)
                pendingPosition = restoreIdx
                shownPosition = restoreIdx
                // No fade on initial seed — there's nothing to
                // transition from.
                titleText.text = titleFor(restoreIdx)
            }
        }.execute()
    }

    private fun titleFor(position: Int): String {
        val item = items.getOrNull(position) ?: return ""
        return "${item.file.name}  —  ${position + 1}/${items.size}"
    }

    /** Fade the title out, swap text, fade back in. Mirrors the
     *  AppDrawerActivity's crossfadeOverlayLabel. */
    private fun crossfadeTitle(newText: String) {
        titleText.animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction {
                titleText.text = newText
                titleText.animate()
                    .alpha(1f)
                    .setDuration(FADE_MS)
                    .start()
            }
            .start()
    }

    private fun openViewer(item: MediaItem) {
        // Try to animate the selected card up to fullscreen before
        // launching the viewer. Falls back to a direct launch if the
        // focused image's drawable isn't available yet (e.g. its
        // thumbnail load is still pending).
        val drawable = findCenterImageDrawable()
        val root = window.decorView.findViewById<View>(android.R.id.content) as? FrameLayout
        if (drawable == null || root == null || root.width <= 0 || root.height <= 0) {
            launchViewer(item)
            return
        }

        // Approximate visual size of the focused card: layout 160dp
        // square × the transformer's center-emphasis scale (1.22).
        val density = resources.displayMetrics.density
        val startSize = (160 * density * 1.22f).toInt()

        val overlay = ImageView(this).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            tag = OVERLAY_TAG
            layoutParams = FrameLayout.LayoutParams(
                startSize, startSize, Gravity.CENTER
            )
        }
        root.addView(overlay)

        // Hide the cover flow + title so the overlay isn't competing
        // with the original card during the grow.
        titleText.animate().alpha(0f).setDuration(FADE_MS).start()
        pager.animate().alpha(0f).setDuration(FADE_MS).start()

        // Grow the overlay's *layout* dimensions, not its scaleX/Y.
        // Animating scale separately on each axis stretches the
        // bitmap (the screen isn't square but the start size is);
        // animating width/height keeps the ImageView's FIT_CENTER
        // running every frame against a rectangle that matches the
        // current bounds, so the photo's aspect stays right
        // throughout the zoom.
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        val targetW = root.width
        val targetH = root.height
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_ANIM_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                lp.width = (startSize + (targetW - startSize) * t).toInt()
                lp.height = (startSize + (targetH - startSize) * t).toInt()
                overlay.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Launch the viewer with no activity transition
                    // so the overlay → MediaViewActivity handoff
                    // reads as a single continuous motion.
                    launchViewer(item)
                    overridePendingTransition(0, 0)
                }
            })
            start()
        }
    }

    private fun launchViewer(item: MediaItem) {
        val intent = Intent(this, MediaViewActivity::class.java)
            .putExtra(MediaViewActivity.EXTRA_PATH, item.file.absolutePath)
            .putExtra(MediaViewActivity.EXTRA_IS_VIDEO, item.isVideo)
        startActivity(intent)
    }

    /** Pick out the ImageView for whichever cover-flow page is
     *  currently centered. We use [pendingPosition] as the source of
     *  truth (same field that drives the title chip) rather than
     *  ViewPager2's currentItem, which can lag behind the manual
     *  glideBy tracking. */
    private fun findCenterImageDrawable(): android.graphics.drawable.Drawable? {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.getChildViewHolder(child) as? CoverFlowAdapter.Holder ?: continue
            @Suppress("DEPRECATION")
            if (holder.adapterPosition == pendingPosition) {
                return holder.image.drawable
            }
        }
        return null
    }

    private fun cleanupOpenAnimation() {
        val root = window.decorView.findViewById<View>(android.R.id.content) as? FrameLayout
            ?: return
        var i = 0
        while (i < root.childCount) {
            val c = root.getChildAt(i)
            if (c.tag == OVERLAY_TAG) {
                root.removeView(c)
            } else {
                i++
            }
        }
        // Restore the cover-flow visibility we hid during the launch.
        pager.alpha = 1f
        titleText.alpha = 1f
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
                // EE2 only: gallery is the one carousel where the
                // convention is intentionally inverted from Home /
                // Settings / App drawer. With newest-first sort,
                // swipe-forward → position +1 lands on an OLDER photo
                // which feels counterintuitive when the content is
                // directional. EE1/XE keep the convention because
                // their physical touchpad reports raw MotionEvents
                // and the user found the original direction correct
                // there. EE2 reverses it to glideBy(-1) on forward.
                if (!swiped && abs(dx) > 30f && abs(dx) > abs(dy)) {
                    swiped = true
                    val forward = if (galleryReverseSwipe) -1 else 1
                    glideBy(if (dx > 0f) forward else -forward)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                // A release without a detected swipe = tap = open viewer.
                if (!swiped) {
                    items.getOrNull(pendingPosition)?.let { openViewer(it) }
                    return true
                }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                items.getOrNull(pendingPosition)?.let { openViewer(it) }
                true
            }
            KeyEvent.KEYCODE_TAB -> {
                // EE2 only: inverted from other carousels — see
                // handleGesture above for the rationale. EE1/XE keep
                // the project standard.
                val forward = if (galleryReverseSwipe) -1 else 1
                val dir = if (event?.isShiftPressed == true) -forward else forward
                glideBy(dir)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                glideBy(if (galleryReverseSwipe) -1 else 1); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                glideBy(if (galleryReverseSwipe) 1 else -1); true
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Pixel-level smooth pan on the ViewPager2's inner RecyclerView —
     * mirrors the Home app drawer's scroll so successive swipes stack
     * into one continuous glide and the snap helper lands on the
     * closest item. Also advances [pendingPosition] and schedules
     * the title-chip crossfade so the label only swaps once the
     * scroll has settled (matches AppDrawerActivity).
     */
    private fun glideBy(direction: Int) {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        val itemWidth = rv.width - rv.paddingLeft - rv.paddingRight
        if (itemWidth <= 0) return
        rv.smoothScrollBy(direction * itemWidth, 0, DecelerateInterpolator(), SCROLL_MS)

        val newPos = (pendingPosition + direction).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (newPos != pendingPosition) {
            pendingPosition = newPos
            labelHandler.removeCallbacks(labelUpdateRunnable)
            labelHandler.postDelayed(labelUpdateRunnable, SCROLL_MS.toLong() + 30L)
        }
    }
}
