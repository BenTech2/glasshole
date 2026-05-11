package com.glasshole.glass.sdk

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Headless Camera1 capture that pushes raw, full-frame JPEGs to a
 * supplied [onFrame] sink. Camera1 is the only API guaranteed to work
 * across all glass editions (XE/EE1 are API 19; Camera2 is API 21+
 * and badly supported on those devices anyway).
 *
 *  - No preview surface — we attach a SurfaceTexture(0) sink so the
 *    camera HAL is happy but no UI is needed
 *  - Uses NV21 preview frames + YuvImage.compressToJpeg, which works
 *    on every Android since 1.0
 *  - JPEG quality is intentionally low-ish (60) so a debug stream
 *    over LAN sits in a couple Mbps
 *  - Frame rate is throttled in software so we don't slam the
 *    BufferedReader on the phone side; ~10 fps target
 */
class CameraStreamer(
    private val onFrame: (ByteArray) -> Unit,
    private val targetWidthHint: Int = 640,
    /** 15fps is the sweet spot on Glass EE1 — 10 feels visibly choppy
     *  and 20 starts losing frames to the encoder taking >50ms per
     *  JPEG on KitKat's CPU. */
    private val targetFps: Int = 15,
    /** JPEG quality 50 trims ~20% off encode time vs. 60 and the
     *  resulting frame still looks fine for a low-latency live
     *  preview. Bandwidth drops too, which helps over BT-tethered
     *  Wi-Fi setups. */
    private val jpegQuality: Int = 50,
) {
    companion object {
        private const val TAG = "CameraStreamer"
    }

    private var camera: Camera? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    @Volatile private var lastEmitMs: Long = 0L
    @Volatile private var running = false

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return try {
            workerThread = HandlerThread("CameraStreamer-cam").apply { start() }
            workerHandler = Handler(workerThread!!.looper)
            encoderThread = HandlerThread("CameraStreamer-jpg").apply { start() }
            encoderHandler = Handler(encoderThread!!.looper)

            // Open + configure on the camera's own thread so HAL
            // callbacks live on the same Looper that opened it. Some
            // Glass HALs are picky about cross-thread access.
            val ok = postAndWaitBool(workerHandler!!) { openAndConfigure() }
            if (!ok) {
                Log.e(TAG, "openAndConfigure failed")
                stop()
                return false
            }
            running = true
            Log.i(TAG, "started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            workerHandler?.let {
                postAndWaitVoid(it) {
                    try { camera?.setPreviewCallbackWithBuffer(null) } catch (_: Exception) {}
                    try { camera?.stopPreview() } catch (_: Exception) {}
                    try { camera?.release() } catch (_: Exception) {}
                    camera = null
                    try { surfaceTexture?.release() } catch (_: Exception) {}
                    surfaceTexture = null
                }
            }
        } catch (_: Exception) {}
        try { workerThread?.quit() } catch (_: Exception) {}
        try { encoderThread?.quit() } catch (_: Exception) {}
        workerThread = null
        encoderThread = null
        workerHandler = null
        encoderHandler = null
        Log.i(TAG, "stopped")
    }

    private fun openAndConfigure(): Boolean {
        return try {
            val cam = Camera.open() ?: return false
            camera = cam
            val params = cam.parameters

            // Pick a preview size near the hint that matches the
            // sensor's native 4:3 aspect. Glass EE1 reports a mix of
            // 1:1 (640×640), 9:16 (720×1280), and 4:3 (480×640) options;
            // 1:1 produces an obviously squashed image (1:1 of a 4:3
            // scene) and 9:16 looks slightly horizontally stretched.
            // Sort by aspect-distance-from-4:3 first, then by how close
            // the width is to the hint.
            val previewSizes = params.supportedPreviewSizes
            Log.i(TAG, "supportedPreviewSizes: " +
                previewSizes.joinToString { "${it.width}x${it.height}" })
            val targetAspect = 4f / 3f
            fun aspectDelta(s: Camera.Size): Float {
                val longSide = maxOf(s.width, s.height).toFloat()
                val shortSide = minOf(s.width, s.height).toFloat()
                if (shortSide == 0f) return Float.MAX_VALUE
                return Math.abs(longSide / shortSide - targetAspect)
            }
            val chosen = previewSizes
                .filter { it.width <= targetWidthHint * 2 }
                .minWithOrNull(
                    compareBy<Camera.Size>({ aspectDelta(it) })
                        .thenBy { Math.abs(it.width - targetWidthHint) }
                )
                ?: previewSizes.first()
            Log.i(TAG, "chose preview size ${chosen.width}x${chosen.height}")
            params.setPreviewSize(chosen.width, chosen.height)
            frameWidth = chosen.width
            frameHeight = chosen.height
            params.previewFormat = ImageFormat.NV21

            // Continuous-video focus when available — least disruptive
            // for a live stream. Fallback to whatever the device offers.
            val focusModes = params.supportedFocusModes
            when {
                focusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                focusModes?.contains(Camera.Parameters.FOCUS_MODE_INFINITY) == true ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_INFINITY
            }
            cam.parameters = params

            val st = SurfaceTexture(0)
            surfaceTexture = st
            cam.setPreviewTexture(st)

            // Pre-allocate three NV21 buffers so the HAL has something
            // to fill while we're encoding.
            val frameBytes = chosen.width * chosen.height * 3 / 2
            for (i in 0 until 3) cam.addCallbackBuffer(ByteArray(frameBytes))
            cam.setPreviewCallbackWithBuffer { data, _ ->
                onPreviewFrame(data)
            }

            cam.startPreview()
            true
        } catch (e: Exception) {
            Log.e(TAG, "open/config failed: ${e.message}")
            try { camera?.release() } catch (_: Exception) {}
            camera = null
            false
        }
    }

    private fun onPreviewFrame(nv21: ByteArray?) {
        val cam = camera ?: return
        if (nv21 == null) {
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        val targetGap = 1000L / targetFps.coerceAtLeast(1)
        val due = now - lastEmitMs >= targetGap
        if (!due) {
            // Toss this frame back to the HAL without encoding.
            try { cam.addCallbackBuffer(nv21) } catch (_: Exception) {}
            return
        }
        lastEmitMs = now

        val w = frameWidth
        val h = frameHeight
        // Encode off the camera thread so we don't starve the HAL. Any
        // orientation correction the viewer needs happens phone-side —
        // we used to rotate NV21 bytes here, but the chroma
        // realignment had subtle bugs on some preview sizes that
        // produced stretched output.
        encoderHandler?.post {
            try {
                val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
                val baos = ByteArrayOutputStream()
                yuv.compressToJpeg(Rect(0, 0, w, h), jpegQuality, baos)
                onFrame(baos.toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "encode failed: ${e.message}")
            } finally {
                // Always recycle the buffer back to the HAL, even on
                // encode failure — otherwise the HAL runs out and
                // preview silently dies.
                try { cam.addCallbackBuffer(nv21) } catch (_: Exception) {}
            }
        } ?: run {
            try { cam.addCallbackBuffer(nv21) } catch (_: Exception) {}
        }
    }

    private fun postAndWaitBool(handler: Handler, block: () -> Boolean): Boolean {
        var result = false
        val done = Object()
        var finished = false
        handler.post {
            result = try { block() } catch (e: Exception) {
                Log.e(TAG, "postAndWait block failed: ${e.message}")
                false
            }
            synchronized(done) { finished = true; (done as Object).notifyAll() }
        }
        synchronized(done) {
            while (!finished) {
                try { (done as Object).wait(5_000) } catch (_: InterruptedException) { break }
                if (!finished) break
            }
        }
        return result
    }

    private fun postAndWaitVoid(handler: Handler, block: () -> Unit) {
        postAndWaitBool(handler) { block(); true }
    }
}
