package com.glasshole.plugin.gallery2.glass

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Full-screen slideshow runner for the gallery2 plugin. Reads its
 * config from the same SharedPreferences the rest of the plugin uses
 * (driven by the phone-side settings schema):
 *
 *   slideshow_duration_sec     seconds per image (2..30)
 *   slideshow_transition       "fade" | "glide" | "none"
 *   slideshow_order            "newest" | "oldest" | "random"
 *   slideshow_loop             checkbox — loop at end vs finish
 *   slideshow_keep_screen_on   checkbox — FLAG_KEEP_SCREEN_ON
 *
 * Two layered ImageViews ([incoming] in front, [outgoing] behind)
 * trade roles on each advance — that way one frame loads its bitmap
 * off the UI thread while the previous one is still being viewed
 * underneath. Transitions:
 *
 *   fade   — outgoing alpha 1→0 in 350 ms while incoming holds at 1
 *   glide  — incoming translateX width→0 in 400 ms
 *   none   — instant swap
 *
 * Tap pauses / resumes; swipe-down or Back exits and returns to the
 * MediaViewActivity that started us (which still has the original
 * tapped photo on screen).
 *
 * Launched from [MediaViewActivity] via [EXTRA_START_PATH] — we
 * re-scan the media list ourselves so the slideshow's own
 * `slideshow_order` pref (which may differ from the cover-flow's
 * sort_order) takes effect.
 */
class SlideshowActivity : Activity() {

    companion object {
        const val EXTRA_START_PATH = "start_path"
        /** Crossfade / slide duration. */
        private const val TRANSITION_MS = 350L
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var incoming: ImageView
    private lateinit var outgoing: ImageView
    private lateinit var statusText: TextView
    private lateinit var swipeDetector: GestureDetector

    private val handler = Handler(Looper.getMainLooper())
    private var items: List<MediaItem> = emptyList()
    private var index: Int = 0
    private var paused: Boolean = false
    private var durationMs: Long = 5_000L
    private var transition: String = "fade"
    private var loop: Boolean = true

    private val advance = Runnable { advanceOnce() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Gallery2PluginService.PREFS_NAME, Context.MODE_PRIVATE)

        durationMs = prefs.getInt("slideshow_duration_sec", 5)
            .coerceIn(2, 30).toLong() * 1000L
        transition = prefs.getString("slideshow_transition", "fade") ?: "fade"
        loop = prefs.getBoolean("slideshow_loop", true)
        val keepOn = prefs.getBoolean("slideshow_keep_screen_on", true)
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        outgoing = newImageView()
        incoming = newImageView()
        root.addView(outgoing)
        root.addView(incoming)

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 14f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = dp(24)
            layoutParams = lp
            visibility = View.GONE
        }
        root.addView(statusText)

        setContentView(root)

        // Swipe-down to exit, mirroring MediaViewActivity.
        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
            ): Boolean {
                val dy = if (e1 != null) e2.y - e1.y else 0f
                val dx = if (e1 != null) e2.x - e1.x else 0f
                if (velocityY > 1200 && dy > 80 && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    finish(); return true
                }
                return false
            }
        })

        val startPath = intent.getStringExtra(EXTRA_START_PATH).orEmpty()
        // Off the UI thread — scan can read hundreds of files.
        @Suppress("DEPRECATION")
        object : AsyncTask<Void, Void, List<MediaItem>>() {
            override fun doInBackground(vararg p: Void?): List<MediaItem> = scanWithOrder()
            override fun onPostExecute(result: List<MediaItem>) {
                items = result.filter { !it.isVideo }
                if (items.isEmpty()) { finish(); return }
                index = items.indexOfFirst { it.file.absolutePath == startPath }
                    .coerceAtLeast(0)
                loadInto(incoming, index)
                handler.postDelayed(advance, durationMs)
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    private fun newImageView(): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    /** Scan + sort/shuffle per the slideshow's own order pref. The
     *  cover-flow's `sort_order` is intentionally separate so the
     *  user can keep "Newest first" in the carousel but pick
     *  "Shuffle" for the slideshow. */
    private fun scanWithOrder(): List<MediaItem> {
        val raw = MediaScanner.scan(this)
        return when (prefs.getString("slideshow_order", "newest")) {
            "oldest" -> raw.sortedBy { it.dateModified }
            "random" -> raw.shuffled()
            else -> raw.sortedByDescending { it.dateModified }
        }
    }

    private fun loadInto(target: ImageView, pos: Int) {
        val item = items.getOrNull(pos) ?: return
        target.setImageDrawable(null)
        target.tag = item.file.absolutePath
        @Suppress("DEPRECATION")
        object : AsyncTask<Void, Void, android.graphics.Bitmap?>() {
            override fun doInBackground(vararg p: Void?): android.graphics.Bitmap? {
                return decodeForSlideshow(item.file.absolutePath)
            }
            override fun onPostExecute(result: android.graphics.Bitmap?) {
                if (result != null && target.tag == item.file.absolutePath) {
                    target.setImageBitmap(result)
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    private fun decodeForSlideshow(path: String): android.graphics.Bitmap? {
        return try {
            val dm = resources.displayMetrics
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            val sample = maxOf(1, minOf(
                opts.outWidth / dm.widthPixels,
                opts.outHeight / dm.heightPixels
            ))
            val loadOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, loadOpts)
        } catch (_: Exception) { null }
    }

    private fun advanceOnce() {
        if (paused) return
        val nextIndex = index + 1
        if (nextIndex >= items.size) {
            if (loop) {
                index = 0
            } else {
                finish(); return
            }
        } else {
            index = nextIndex
        }
        // Swap roles: the front view becomes the new "outgoing" backdrop;
        // the back view becomes the new "incoming" that animates in.
        val tmp = outgoing
        outgoing = incoming
        incoming = tmp
        incoming.bringToFront()
        // Reset transform state in case the previous frame used glide.
        incoming.translationX = 0f
        incoming.alpha = 1f
        outgoing.alpha = 1f
        loadInto(incoming, index)
        playTransition()
        handler.postDelayed(advance, durationMs)
    }

    private fun playTransition() {
        when (transition) {
            "fade" -> ObjectAnimator.ofFloat(outgoing, "alpha", 1f, 0f).apply {
                duration = TRANSITION_MS
                start()
            }
            "glide" -> {
                val w = (incoming.parent as? View)?.width?.toFloat()
                    ?: resources.displayMetrics.widthPixels.toFloat()
                incoming.translationX = w
                ObjectAnimator.ofFloat(incoming, "translationX", w, 0f).apply {
                    duration = TRANSITION_MS
                    start()
                }
                ObjectAnimator.ofFloat(outgoing, "translationX", 0f, -w).apply {
                    duration = TRANSITION_MS
                    start()
                }
            }
            else -> outgoing.alpha = 0f // "none" — just hide the old layer
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(advance)
    }

    override fun onResume() {
        super.onResume()
        if (items.isNotEmpty() && !paused) {
            handler.removeCallbacks(advance)
            handler.postDelayed(advance, durationMs)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePause(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            handler.removeCallbacks(advance)
            statusText.text = "Paused"
            statusText.visibility = View.VISIBLE
        } else {
            statusText.text = "Resumed"
            statusText.visibility = View.VISIBLE
            handler.postDelayed({ statusText.visibility = View.GONE }, 1_200L)
            handler.postDelayed(advance, durationMs)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finish() }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
