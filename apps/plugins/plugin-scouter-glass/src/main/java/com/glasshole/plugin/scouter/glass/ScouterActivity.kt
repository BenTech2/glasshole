package com.glasshole.plugin.scouter.glass

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.graphics.SurfaceTexture
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.random.Random

/**
 * The Scouter — a fullscreen camera viewfinder with a Saiyan-style
 * scanning reticle. Tap the side touchpad to "lock on" the current
 * frame; the activity freezes the preview, runs a brief analysis
 * animation, then reveals a power level.
 *
 * Power level derivation:
 *   1. 3% chance the legendary "9001" easter egg fires — same scene
 *      every time wouldn't sell the joke if 9001 came up too often.
 *   2. Otherwise: SHA-1 the captured preview frame's bytes and map
 *      the hash to a number in 50–50,000. Same scene → same number,
 *      different framing → different number, which sells the bit
 *      better than pure random.
 *   3. Classification picked by hash-modulo from a fixed list of
 *      DBZ-flavored species.
 *
 * Camera1 API throughout for compatibility — XE, EE1, and EE2 all
 * speak it (EE2 supports both Camera1 and Camera2; we pick Camera1
 * to ship one binary).
 */
class ScouterActivity : Activity() {

    companion object {
        private const val TAG = "ScouterActivity"
        private const val CAMERA_PERM_REQUEST = 1001
        private const val ANALYZE_DURATION_MS = 1800L
        private const val OVER_9000_PROBABILITY = 0.03f

        private val CLASSIFICATIONS = arrayOf(
            "HUMAN", "SAIYAN", "NAMEKIAN", "ANDROID", "ALIEN",
            "BIO-ENGINEERED", "TUFFLE", "FRIEZA RACE", "MAJIN",
            "ARLIAN", "DEMON", "GUARDIAN", "EARTHLING", "UNKNOWN"
        )
    }

    private enum class State { SCANNING, ANALYZING, LOCKED }

    private var camera: Camera? = null
    /** TextureView instead of SurfaceView so the camera preview
     *  composites within the activity's window hierarchy — SurfaceView
     *  punches through its window space to a separate compositor
     *  layer, and on EE2 that hand-off can race with surface creation
     *  (we hit a "Failed to find layer in layer parent (no-parent)"
     *  SurfaceFlinger crash that left the camera with a null surface
     *  and the viewfinder black). TextureView avoids the punch-through
     *  entirely. */
    private lateinit var preview: TextureView
    private lateinit var overlay: ScannerOverlayView
    private var lastPreviewFrame: ByteArray? = null
    private var state: State = State.SCANNING

    private val mainHandler = Handler(Looper.getMainLooper())
    private val revealRunnable = Runnable { revealPowerLevel() }

    /** Glass touchpad swipe-down → finish the activity. EE2 also
     *  synthesizes KEYCODE_BACK for swipe-down (handled in onKeyDown),
     *  but EE1 / XE only deliver the gesture as MotionEvent flings, so
     *  the GestureDetector path is required for those editions. Same
     *  trick the camera plugin uses. */
    private lateinit var swipeDownDetector: GestureDetector

    /** Set true when a swipe-down fling is detected, to keep the
     *  matching ACTION_UP from triggering handleTap() before the
     *  activity finishes. Reset on the next ACTION_DOWN. */
    @Volatile private var suppressNextTap = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen + show-when-locked + turn-screen-on so the
        // scouter "wakes" the headset on launch.
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        preview = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isOpaque = true
        }
        overlay = ScannerOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setMode(ScannerOverlayView.Mode.SCANNING)
        }
        root.addView(preview)
        root.addView(overlay)
        setContentView(root)

        swipeDownDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val dy = if (e1 != null) e2.y - e1.y else 0f
                val dx = if (e1 != null) e2.x - e1.x else 0f
                // Glass swipe-down: positive velocityY, dominant
                // vertical component. Same threshold ratio the camera
                // plugin uses, since EE2's touchpad reports the same
                // velocity scale as XE / EE1.
                if (velocityY > 1200 && dy > 80 && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    suppressNextTap = true
                    finish()
                    return true
                }
                return false
            }
        })

        preview.surfaceTextureListener = textureListener

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERM_REQUEST
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Camera bind happens once the texture is available; if it's
        // already there (e.g. coming back from background), open now.
        if (preview.isAvailable) openCameraIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(revealRunnable)
        releaseCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERM_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openCameraIfNeeded()
        } else {
            finish()
        }
    }

    // --- Surface lifecycle ---

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
            openCameraIfNeeded()
        }
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
            // No-op — Glass screen is fixed orientation/size; once we
            // started preview in onSurfaceTextureAvailable we don't
            // need to reconfigure here.
        }
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
            releaseCamera()
            return true  // we own the SurfaceTexture; let TextureView release it
        }
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) { /* per-frame */ }
    }

    @Suppress("DEPRECATION")
    private fun openCameraIfNeeded() {
        if (camera != null) return
        if (!hasCameraPermission()) return
        try {
            val cam = Camera.open()
            camera = cam
            val params = cam.parameters
            // Pick a preview size near the screen size — Glass display
            // is 640×360. Camera1 won't necessarily give us exactly
            // that, so pick the smallest that doesn't downsample
            // grossly.
            val target = 640 to 360
            val best = params.supportedPreviewSizes.minByOrNull { sz ->
                abs(sz.width - target.first) + abs(sz.height - target.second)
            }
            if (best != null) params.setPreviewSize(best.width, best.height)
            // Continuous AF when supported — keeps the reticle sharp
            // as the wearer pans across a scene.
            if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            } else if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            cam.parameters = params
            cam.setPreviewTexture(preview.surfaceTexture)
            cam.setPreviewCallback { data, _ ->
                // Grab the latest frame for power-level hashing on
                // capture. Cheap (just a reference swap, the array
                // belongs to the camera buffer).
                lastPreviewFrame = data
            }
            cam.startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Camera open failed: ${e.message}")
            releaseCamera()
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseCamera() {
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
        } catch (_: Exception) {}
        try { camera?.release() } catch (_: Exception) {}
        camera = null
    }

    private fun hasCameraPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    // --- Input ---

    @Suppress("DEPRECATION")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleTap(); true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            if (ev.action == MotionEvent.ACTION_DOWN) suppressNextTap = false
            swipeDownDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_UP) {
            if (suppressNextTap) {
                suppressNextTap = false
                return true
            }
            handleTap()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap() {
        when (state) {
            State.SCANNING -> startAnalyzing()
            State.LOCKED -> resumeScanning()
            State.ANALYZING -> { /* ignore taps mid-analyze */ }
        }
    }

    // --- State transitions ---

    @Suppress("DEPRECATION")
    private fun startAnalyzing() {
        state = State.ANALYZING
        // Freeze the preview by stopping it (the surface keeps the
        // last frame visible). The analyze animation runs over the
        // frozen frame.
        try { camera?.stopPreview() } catch (_: Exception) {}
        overlay.setMode(ScannerOverlayView.Mode.ANALYZING)
        mainHandler.postDelayed(revealRunnable, ANALYZE_DURATION_MS)
    }

    private fun revealPowerLevel() {
        state = State.LOCKED
        val (power, klass) = computePowerLevel()
        overlay.setLockedReveal(power, klass)
    }

    @Suppress("DEPRECATION")
    private fun resumeScanning() {
        state = State.SCANNING
        overlay.setMode(ScannerOverlayView.Mode.SCANNING)
        try { camera?.startPreview() } catch (e: Exception) {
            Log.w(TAG, "startPreview after lock failed: ${e.message}")
            // Try a clean re-open as a fallback.
            releaseCamera()
            openCameraIfNeeded()
        }
    }

    /** Hash-from-frame power level, with a small chance of the
     *  canonical 9001 easter egg. Returns (power, classification). */
    private fun computePowerLevel(): Pair<Long, String> {
        if (Random.nextFloat() < OVER_9000_PROBABILITY) {
            return 9001L to "OVER 9000"
        }
        val frame = lastPreviewFrame
        val seed: Long = if (frame == null || frame.isEmpty()) {
            Random.nextLong()
        } else {
            try {
                val digest = MessageDigest.getInstance("SHA-1").digest(frame)
                // Fold the 20-byte digest into a long.
                var acc = 0L
                for (i in 0 until digest.size step 8) {
                    var chunk = 0L
                    for (j in 0 until 8) {
                        if (i + j >= digest.size) break
                        chunk = (chunk shl 8) or (digest[i + j].toLong() and 0xFF)
                    }
                    acc = acc xor chunk
                }
                acc
            } catch (_: Exception) { Random.nextLong() }
        }
        // Map to 50–50,000 range. Most readings should feel
        // ordinary; occasional big numbers feel earned.
        val absSeed = if (seed == Long.MIN_VALUE) 1L else (if (seed < 0) -seed else seed)
        val power = 50L + (absSeed % 49_950L)
        val klass = CLASSIFICATIONS[(absSeed.ushr(8) % CLASSIFICATIONS.size).toInt()]
        return power to klass
    }
}
