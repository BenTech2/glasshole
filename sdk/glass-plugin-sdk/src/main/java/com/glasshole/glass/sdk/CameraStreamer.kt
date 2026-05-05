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
    private val targetFps: Int = 10,
    private val jpegQuality: Int = 60,
    /** Rotation applied to each NV21 frame before JPEG encode.
     *  Supported: 0 and 90 (clockwise). EE1's camera sensor is mounted
     *  90° off the display so frames need a CW rotation before they
     *  reach the phone viewer. EE2 reports 0° and stays as-is. */
    private val rotationDegrees: Int = 0
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
    /** Reusable scratch buffer for 90° NV21 rotation. Same total byte
     *  count as the source so a single allocation services every
     *  frame; only touched on the encoder thread. */
    private var rotationScratch: ByteArray? = null

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

            // Pick a preview size near the hint, prefer the one closest
            // to (and not exceeding) the requested width.
            val previewSizes = params.supportedPreviewSizes
            val chosen = previewSizes
                .filter { it.width <= targetWidthHint * 2 } // skip hideously huge ones
                .minByOrNull { Math.abs(it.width - targetWidthHint) }
                ?: previewSizes.first()
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
        // Encode off the camera thread so we don't starve the HAL.
        encoderHandler?.post {
            try {
                val (data, encW, encH) = if (rotationDegrees == 90) {
                    val scratch = rotationScratch
                        ?: ByteArray(w * h * 3 / 2).also { rotationScratch = it }
                    rotateNV21Cw90(nv21, w, h, scratch)
                    Triple(scratch, h, w) // dims swap after CW 90
                } else {
                    Triple(nv21, w, h)
                }
                val yuv = YuvImage(data, ImageFormat.NV21, encW, encH, null)
                val baos = ByteArrayOutputStream()
                yuv.compressToJpeg(Rect(0, 0, encW, encH), jpegQuality, baos)
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

    /**
     * Rotate an NV21 frame 90° clockwise into [dst]. NV21 is a Y plane
     * of W×H bytes followed by an interleaved VU plane at half
     * resolution (each VU pair covers a 2×2 block of Y). Rotating swaps
     * the dimensions: dst is treated as H×W. Y rotates per-pixel; the
     * VU plane rotates per-pair (V then U) to keep chroma aligned.
     */
    private fun rotateNV21Cw90(src: ByteArray, w: Int, h: Int, dst: ByteArray) {
        var di = 0
        for (x in 0 until w) {
            for (y in h - 1 downTo 0) {
                dst[di++] = src[y * w + x]
            }
        }
        val frameSize = w * h
        di = frameSize + frameSize / 2 - 1
        var x = w - 1
        while (x > 0) {
            for (y in 0 until h / 2) {
                dst[di--] = src[frameSize + y * w + x]      // V
                dst[di--] = src[frameSize + y * w + x - 1]  // U
            }
            x -= 2
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
