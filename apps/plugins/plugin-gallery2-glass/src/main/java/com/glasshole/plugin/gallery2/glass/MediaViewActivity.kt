package com.glasshole.plugin.gallery2.glass

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import java.io.File

/**
 * Full-screen viewer for a single photo or video. Back dismisses.
 * Tap toggles video playback; any key wakes the auto-dismiss timer.
 */
class MediaViewActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_IS_VIDEO = "is_video"
    }

    private var videoView: VideoView? = null
    private lateinit var backGestureDetector: GestureDetector
    private var currentPath: String = ""
    private var isVideoPath: Boolean = false
    private var slideshowPill: TextView? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayDismissRunnable = Runnable { hideSlideshowOverlay() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        val path = intent.getStringExtra(EXTRA_PATH)
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        if (path.isNullOrEmpty()) { finish(); return }
        currentPath = path
        isVideoPath = isVideo

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        if (isVideo) {
            val vv = VideoView(this).apply {
                setVideoURI(Uri.fromFile(File(path)))
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                lp.gravity = Gravity.CENTER
                layoutParams = lp
                val controller = MediaController(this@MediaViewActivity)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { start() }
            }
            root.addView(vv)
            videoView = vv
        } else {
            val iv = ImageView(this).apply {
                setImageBitmap(loadScaled(path))
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            root.addView(iv)

            // "▶ Play slideshow" pill — appears on the FIRST tap and
            // auto-dismisses after 3.5s. Tapping it (second tap)
            // launches SlideshowActivity. Lets the user discover the
            // feature without us hijacking single-photo viewing.
            val pill = TextView(this).apply {
                text = "▶  Play slideshow"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setBackgroundColor(0xCC212121.toInt())
                setPadding(dp(18), dp(10), dp(18), dp(10))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                setOnClickListener { startSlideshow() }
            }
            root.addView(pill)
            slideshowPill = pill
        }

        setContentView(root)
    }

    private fun showSlideshowOverlay() {
        val pill = slideshowPill ?: return
        pill.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(overlayDismissRunnable)
        overlayHandler.postDelayed(overlayDismissRunnable, 3_500L)
    }

    private fun hideSlideshowOverlay() {
        slideshowPill?.visibility = View.GONE
        overlayHandler.removeCallbacks(overlayDismissRunnable)
    }

    private fun startSlideshow() {
        hideSlideshowOverlay()
        startActivity(Intent(this, SlideshowActivity::class.java)
            .putExtra(SlideshowActivity.EXTRA_START_PATH, currentPath))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun loadScaled(path: String): android.graphics.Bitmap? {
        return try {
            val dm = resources.displayMetrics
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            val sample = maxOf(
                1,
                minOf(
                    opts.outWidth / dm.widthPixels,
                    opts.outHeight / dm.heightPixels
                )
            )
            val loadOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, loadOpts)
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        videoView?.stopPlayback()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val vv = videoView
                if (vv != null) {
                    // Video: keep existing play/pause behaviour.
                    if (vv.isPlaying) vv.pause() else vv.start()
                } else {
                    // Photo: toggle the slideshow-entry overlay. Two
                    // taps in quick succession start playback (first
                    // shows the pill, second is a click on the pill).
                    val pill = slideshowPill
                    if (pill?.visibility == View.VISIBLE) startSlideshow()
                    else showSlideshowOverlay()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        videoView?.pause()
        overlayHandler.removeCallbacks(overlayDismissRunnable)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) backGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
