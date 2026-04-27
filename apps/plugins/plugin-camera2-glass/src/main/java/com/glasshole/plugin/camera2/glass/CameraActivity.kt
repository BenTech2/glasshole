package com.glasshole.plugin.camera2.glass

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Full-screen Camera2-based capture activity. Replaces the stock Glass
 * camera on EE2 via high-priority intent filters. UI is deliberately
 * minimal because input is extremely limited on-glass:
 *
 *   - Tap (DPAD_CENTER)       → still capture
 *   - Long-press (DPAD_CENTER)→ start/stop video recording
 *   - Swipe down (BACK)       → exit
 *
 * Follows the Camera2 open → create capture session → set repeating
 * preview request → still capture via ImageReader pattern from the
 * Google glass-enterprise-samples Camera2Sample.
 *
 * Note: EE2 has no flash LED and the sensor's HDR scene mode is not
 * exposed, so neither toggle is available in this plugin.
 */
class CameraActivity : Activity() {

    companion object {
        private const val TAG = "Camera2Glass"
        private const val REQUEST_PERMS = 101
        // WRITE_EXTERNAL_STORAGE is only a runtime permission on API ≤ 28.
        // On API 29+ scoped storage lets us write to DCIM without it, so we
        // build the list dynamically.
        private val PERMS: Array<String> = run {
            val base = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT <= 28) {
                base.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            base.toTypedArray()
        }
    }

    // --- Camera state ---
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String = ""
    private var previewSize: Size = Size(640, 360)
    private var imageReader: ImageReader? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // --- Background thread for camera callbacks ---
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // --- Views ---
    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var modeText: TextView

    // --- Modes ---
    @Volatile private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var currentVideoFile: File? = null

    // --- Long-press detection ---
    private var keyDownTime = 0L
    private val longPressThresholdMs: Long
        get() = settingsPrefs.getInt("long_press_ms", 500).toLong()

    private val settingsPrefs by lazy {
        getSharedPreferences(Camera2PluginService.PREFS_NAME, MODE_PRIVATE)
    }

    // --- Swipe-down to exit (EE2) ---
    private lateinit var backGestureDetector: GestureDetector
    // Set when a fling was recognised so the matching ACTION_UP doesn't
    // also fire a still-capture via onTouchEvent.
    private var suppressNextTap = false

    // --- Quick capture (XE-style "press camera button, get photo") ---
    /** Launched via the system camera-key / IMAGE_CAPTURE intent — if the
     *  quick_capture setting is on, fire the shutter as soon as the session
     *  is ready and auto-finish, no preview UI. */
    private var quickCaptureMode = false
    /** Guard against double-firing: once we schedule the still capture we
     *  clear this, and the repeating-preview capture callback checks it. */
    private var quickCaptureArmed = false
    private lateinit var quickCaptureOverlay: FrameLayout
    private lateinit var quickCapturePreview: ImageView
    private lateinit var quickCaptureText: TextView

    private val quickCaptureTriggerActions = setOf(
        MediaStore.ACTION_IMAGE_CAPTURE,
        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
        "android.media.action.STILL_IMAGE_CAMERA_SECURE",
        "android.media.action.IMAGE_CAPTURE_SECURE",
        Intent.ACTION_CAMERA_BUTTON
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        // Quick-capture mode: if the activity was launched by a camera-key
        // or IMAGE_CAPTURE style intent AND the user hasn't turned the
        // setting off, we'll fire the shutter as soon as the preview
        // session is configured. Default is on.
        quickCaptureMode = intent?.action in quickCaptureTriggerActions &&
            settingsPrefs.getBoolean("quick_capture", true)
        quickCaptureArmed = quickCaptureMode

        backGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val dy = if (e1 != null) e2.y - e1.y else 0f
                val dx = if (e1 != null) e2.x - e1.x else 0f
                if (velocityY > 1200 && dy > 80 && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    suppressNextTap = true
                    if (isRecording) stopRecording()
                    finish()
                    return true
                }
                return false
            }
        })

        setContentView(buildUi())
        statusText.text = if (quickCaptureMode) "" else "Tap to capture · long-press to record"
        if (quickCaptureMode) {
            quickCaptureOverlay.visibility = View.VISIBLE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) backGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isRecording) stopRecording()
        finish()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(textureView)

        // Bottom status
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = dp(16)
            layoutParams = lp
        }
        root.addView(statusText)

        // Top-right mode indicator
        modeText = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 12f
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = dp(12)
            lp.rightMargin = dp(16)
            layoutParams = lp
        }
        root.addView(modeText)

        // Opaque overlay used in quick-capture mode. Hides the TextureView
        // preview so the user doesn't see a brief preview flash before the
        // shutter fires. After the JPEG saves, the decoded thumbnail fills
        // the frame for a moment — XE-style "see what you got" feedback.
        quickCaptureOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        quickCapturePreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        quickCaptureOverlay.addView(quickCapturePreview)
        quickCaptureText = TextView(this).apply {
            text = "CAPTURING…"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            layoutParams = lp
        }
        quickCaptureOverlay.addView(quickCaptureText)
        root.addView(quickCaptureOverlay)

        return root
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, PERMS, REQUEST_PERMS)
            return
        }
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
        updateModeText()
    }

    override fun onPause() {
        if (isRecording) stopRecording()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (textureView.isAvailable) openCamera(textureView.width, textureView.height)
            else textureView.surfaceTextureListener = surfaceTextureListener
        } else {
            statusText.text = "Camera permission required"
        }
    }

    private fun hasPermissions(): Boolean =
        PERMS.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    // --- SurfaceTextureListener ---
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    // --- Background thread ---
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2Background").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Stop background thread: ${e.message}")
        }
    }

    // --- Camera open / close ---
    private fun openCamera(width: Int, height: Int) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraId = manager.cameraIdList.firstOrNull {
                val chars = manager.getCameraCharacteristics(it)
                chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.first()

            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
            val largestJpeg = jpegSizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)
            previewSize = map.getOutputSizes(SurfaceTexture::class.java).firstOrNull {
                it.width <= 1280 && it.height <= 720
            } ?: Size(1280, 720)

            imageReader = ImageReader.newInstance(largestJpeg.width, largestJpeg.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) return

            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Couldn't acquire camera lock")
                return
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera: ${e.message}")
        } catch (e: InterruptedException) {
            Log.e(TAG, "openCamera interrupted")
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "closeCamera interrupted")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            Log.e(TAG, "CameraDevice error $error")
        }
    }

    // --- Preview session ---
    private fun createPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)

            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)
            applyModeFlags(previewRequestBuilder)

            cameraDevice!!.createCaptureSession(
                listOf(previewSurface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        previewRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        try {
                            // In quick-capture mode we listen for the first
                            // preview result, then fire the still immediately.
                            // That gives AE one frame to converge — still under
                            // ~200ms total — without waiting for an explicit
                            // precapture sequence that Glass's fixed-focus
                            // sensor doesn't meaningfully benefit from.
                            val previewCallback = if (quickCaptureArmed) {
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        s: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult
                                    ) {
                                        if (quickCaptureArmed) {
                                            quickCaptureArmed = false
                                            runOnUiThread { captureStillPicture() }
                                        }
                                    }
                                }
                            } else null
                            session.setRepeatingRequest(previewRequestBuilder.build(), previewCallback, backgroundHandler)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "setRepeatingRequest: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configure failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createPreviewSession: ${e.message}")
        }
    }

    private fun applyModeFlags(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    // --- Still capture ---
    private fun captureStillPicture() {
        try {
            val device = cameraDevice ?: return
            val session = captureSession ?: return
            statusText.text = "Capturing…"

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            applyModeFlags(captureBuilder)
            captureBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 0)

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    runOnUiThread { statusText.text = "Saved" }
                    // Restart preview
                    createPreviewSession()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "captureStillPicture: ${e.message}")
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            saveJpeg(bytes)
        } finally {
            image.close()
        }
    }

    private fun saveJpeg(bytes: ByteArray) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "IMG_$ts.jpg")
        try {
            FileOutputStream(file).use { it.write(bytes) }
            notifyMediaScanner(file)
            // Decode a downscaled thumbnail off the main thread — the JPEG
            // is 8MP, decoding it full-size would stall for ~500ms and we
            // only need to fill a 640x360 display.
            val thumb = if (quickCaptureMode) decodeThumbnail(bytes) else null
            runOnUiThread {
                statusText.text = "Saved ${file.name}"
                if (quickCaptureMode) {
                    if (thumb != null) quickCapturePreview.setImageBitmap(thumb)
                    // Brief confirmation, then exit so the user ends up back
                    // on whatever screen they were on when they hit the
                    // button (or on the lockscreen / Home).
                    quickCaptureText.visibility = View.GONE
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1200)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveJpeg: ${e.message}")
            runOnUiThread {
                statusText.text = "Save failed"
                if (quickCaptureMode) {
                    quickCaptureText.text = "SAVE FAILED"
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
                }
            }
        }
    }

    /** Decode JPEG bytes at roughly display resolution — fast enough that
     *  the user sees the shot within ~100ms of the save completing. */
    private fun decodeThumbnail(bytes: ByteArray): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val target = resources.displayMetrics.widthPixels.coerceAtLeast(640)
            var sample = 1
            while (bounds.outWidth / sample > target * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) { null }
    }

    private fun notifyMediaScanner(file: File) {
        sendBroadcast(
            Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = android.net.Uri.fromFile(file)
            }
        )
    }

    // --- Video recording ---
    private fun startRecording() {
        try {
            closeCameraSessionOnly()
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)

            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            currentVideoFile = File(dir, "VID_$ts.mp4")

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(currentVideoFile!!.absolutePath)
                setVideoEncodingBitRate(settingsPrefs.getInt("video_bitrate_mbps", 10) * 1_000_000)
                setVideoFrameRate(
                    (settingsPrefs.getString("video_framerate", "30") ?: "30").toIntOrNull() ?: 30
                )
                setVideoSize(previewSize.width, previewSize.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                prepare()
            }

            val recorderSurface = mediaRecorder!!.surface
            val recordBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            recordBuilder.addTarget(previewSurface)
            recordBuilder.addTarget(recorderSurface)
            applyModeFlags(recordBuilder)
            recordBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )

            cameraDevice!!.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(recordBuilder.build(), null, backgroundHandler)
                        runOnUiThread {
                            isRecording = true
                            mediaRecorder?.start()
                            statusText.text = "● Recording…"
                            updateModeText()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Record session configure failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: ${e.message}")
            runOnUiThread { statusText.text = "Record start failed" }
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording: ${e.message}")
        }
        mediaRecorder = null
        isRecording = false
        currentVideoFile?.let { notifyMediaScanner(it) }
        statusText.text = "Saved ${currentVideoFile?.name ?: "video"}"
        currentVideoFile = null
        updateModeText()
        createPreviewSession()
    }

    private fun closeCameraSessionOnly() {
        captureSession?.close()
        captureSession = null
    }

    // --- Input ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CAMERA -> {
                if (event?.repeatCount == 0) keyDownTime = System.currentTimeMillis()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isRecording) {
                    stopRecording()
                    true
                } else {
                    finish()
                    true
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CAMERA -> {
                val held = System.currentTimeMillis() - keyDownTime
                if (held >= longPressThresholdMs) {
                    if (isRecording) stopRecording() else startRecording()
                } else {
                    if (!isRecording) captureStillPicture()
                }
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    // Touch fallback (EE2 touchscreen): single tap = capture
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> suppressNextTap = false
            MotionEvent.ACTION_UP -> {
                if (suppressNextTap) {
                    suppressNextTap = false
                    return true
                }
                if (!isRecording) {
                    captureStillPicture()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateModeText() {
        modeText.text = if (isRecording) "REC" else ""
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
