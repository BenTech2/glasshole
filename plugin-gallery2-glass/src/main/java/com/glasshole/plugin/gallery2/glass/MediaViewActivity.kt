package com.glasshole.plugin.gallery2.glass

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
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
        }

        setContentView(root)
    }

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

    override fun onPause() {
        videoView?.pause()
        super.onPause()
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
                    if (vv.isPlaying) vv.pause() else vv.start()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
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
