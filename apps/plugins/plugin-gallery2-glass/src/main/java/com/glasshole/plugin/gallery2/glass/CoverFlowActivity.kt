package com.glasshole.plugin.gallery2.glass

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Cover Flow style media browser. Uses ViewPager2 with a PageTransformer
 * that scales / rotates / overlaps sibling pages to reproduce the iPod
 * carousel look.
 */
class CoverFlowActivity : Activity() {

    companion object {
        private const val REQUEST_PERMS = 201
    }

    private lateinit var pager: ViewPager2
    private lateinit var emptyText: TextView
    private lateinit var titleText: TextView
    private var items: List<MediaItem> = emptyList()
    private lateinit var backGestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // EE2 swipe-down dismiss: ViewPager2's RecyclerView swallows vertical
        // touches, so the system never gets the chance to fire KEYCODE_BACK.
        // Detect a downward fling at dispatchTouchEvent time and finish.
        backGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val dy = if (e1 != null) e2.y - e1.y else 0f
                val dx = if (e1 != null) e2.x - e1.x else 0f
                if (velocityY > 1200 && dy > 80 && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    finish()
                    return true
                }
                return false
            }
        })

        setContentView(buildUi())

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

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        pager = ViewPager2(this).apply {
            offscreenPageLimit = 3
            clipToPadding = false
            clipChildren = false
            // Padding lets neighbor pages peek in at the sides for the carousel feel
            val pad = (resources.displayMetrics.widthPixels * 0.28f).toInt()
            setPadding(pad, 0, pad, 0)
            (getChildAt(0) as? RecyclerView)?.apply {
                clipToPadding = false
                clipChildren = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            setPageTransformer(CoverFlowTransformer())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(pager)

        titleText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundColor(0x99000000.toInt())
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }
        root.addView(titleText)

        emptyText = TextView(this).apply {
            text = "No photos or videos"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(emptyText)

        return root
    }

    private fun loadMedia() {
        object : AsyncTask<Void, Void, List<MediaItem>>() {
            override fun doInBackground(vararg params: Void?): List<MediaItem> =
                MediaScanner.scan()

            override fun onPostExecute(result: List<MediaItem>) {
                items = result
                if (items.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    titleText.visibility = View.GONE
                    return
                }
                emptyText.visibility = View.GONE
                titleText.visibility = View.VISIBLE
                val adapter = CoverFlowAdapter(this@CoverFlowActivity, items) { item ->
                    openViewer(item)
                }
                pager.adapter = adapter
                pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        updateTitle(position)
                    }
                })
                updateTitle(0)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                items.getOrNull(pager.currentItem)?.let { openViewer(it) }
                true
            }
            KeyEvent.KEYCODE_TAB -> {
                val shift = event?.isShiftPressed == true
                val next = if (shift) pager.currentItem - 1 else pager.currentItem + 1
                if (next in 0 until items.size) pager.currentItem = next
                true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // The system back (or swipe-down on EE2) sometimes bypasses onKeyDown and
    // goes straight to onBackPressed — honor it by finishing. Without this,
    // ViewPager2's internal scroll state can swallow the back gesture.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    // Intercept touches at the top level so the downward fling is seen even
    // if ViewPager2 is mid-drag. We don't consume the event (return false),
    // we just watch for the dismissal gesture.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) backGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
