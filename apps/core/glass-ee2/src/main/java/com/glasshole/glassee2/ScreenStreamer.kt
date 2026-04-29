package com.glasshole.glassee2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * EE2-only screen capture using MediaProjection (API 21+, EE2 is API
 * 27). Pulls frames from an ImageReader, downscales, JPEG-encodes,
 * and pushes to the supplied [onFrame] sink. One-shot per consent —
 * call [start] with the result Intent from
 * MediaProjectionManager.createScreenCaptureIntent().
 *
 * Note: MediaProjection.Callback fires when the user revokes the
 * grant via the system "Stop sharing" notification — we treat that
 * as a stop signal.
 */
class ScreenStreamer(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onStopped: () -> Unit,
    private val targetWidth: Int = 480,
    private val targetFps: Int = 6,
    private val jpegQuality: Int = 60
) {
    companion object {
        private const val TAG = "ScreenStreamer"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    @Volatile private var lastEmitMs: Long = 0L
    @Volatile private var running = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection.onStop — user revoked")
            stopAndNotify()
        }
    }

    @Synchronized
    fun start(resultCode: Int, resultData: android.content.Intent): Boolean {
        if (running) return true
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, resultData)
            if (mp == null) {
                Log.w(TAG, "getMediaProjection returned null")
                return false
            }
            projection = mp
            // Caller is on a worker thread without a Looper —
            // registerCallback's `null` handler argument tries
            // `new Handler()` against the current thread and crashes
            // "Can't create handler inside thread that has not called
            // Looper.prepare()". Force the main Looper instead; the
            // callback only fires on user revoke so it doesn't matter
            // which thread it lands on.
            mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            captureThread = HandlerThread("ScreenStreamer-cap").apply { start() }
            captureHandler = Handler(captureThread!!.looper)

            // Match the source display aspect at the requested width.
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val src = android.graphics.Point().also { display.getRealSize(it) }
            val srcW = src.x.coerceAtLeast(1)
            val srcH = src.y.coerceAtLeast(1)
            val w = targetWidth
            val h = (targetWidth.toLong() * srcH / srcW).toInt().coerceAtLeast(1)
            val density = context.resources.displayMetrics.densityDpi

            // 2 buffers is enough — we sample at low FPS, slow encoder
            // is fine.
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ ir ->
                if (!running) {
                    try { ir.acquireLatestImage()?.close() } catch (_: Exception) {}
                    return@setOnImageAvailableListener
                }
                val now = android.os.SystemClock.uptimeMillis()
                val gap = 1000L / targetFps.coerceAtLeast(1)
                if (now - lastEmitMs < gap) {
                    try { ir.acquireLatestImage()?.close() } catch (_: Exception) {}
                    return@setOnImageAvailableListener
                }
                lastEmitMs = now
                val image = try { ir.acquireLatestImage() } catch (_: Exception) { null }
                    ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val rowPadding = rowStride - pixelStride * w
                    val bmp = Bitmap.createBitmap(
                        w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val cropped = if (rowPadding == 0) bmp else
                        Bitmap.createBitmap(bmp, 0, 0, w, h)
                    if (cropped !== bmp) bmp.recycle()
                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                    cropped.recycle()
                    onFrame(baos.toByteArray())
                } catch (e: Exception) {
                    Log.w(TAG, "frame encode failed: ${e.message}")
                } finally {
                    try { image.close() } catch (_: Exception) {}
                }
            }, captureHandler)

            virtualDisplay = mp.createVirtualDisplay(
                "GlassHoleScreenStreamer",
                w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler
            )
            running = true
            Log.i(TAG, "started (${w}x${h} from ${srcW}x${srcH})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            stopInternal()
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        stopInternal()
    }

    private fun stopAndNotify() {
        stop()
        try { onStopped() } catch (_: Exception) {}
    }

    private fun stopInternal() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {}
        projection = null
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        captureHandler = null
        Log.i(TAG, "stopped")
    }
}
