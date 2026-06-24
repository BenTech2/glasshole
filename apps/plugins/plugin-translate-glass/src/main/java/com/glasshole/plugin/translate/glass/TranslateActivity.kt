// SPDX-License-Identifier: MIT
package com.glasshole.plugin.translate.glass

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.glasshole.glass.sdk.GlassPluginMessage
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Translate — tap-to-translate using on-device ML Kit OCR + Translation
 * running on the paired phone. The glass activity captures a single
 * camera frame, ships the JPEG to the phone over BT, and renders the
 * returned translations as either an overlay on the frozen frame or
 * as a scrollable card list.
 *
 * State machine:
 *
 *   LIVE_PREVIEW ── tap ──▶ CAPTURING ── result ──▶ RESULT
 *        ▲                      │                     │
 *        │                      └── error ──▶ ERROR ──┘
 *        │                                            │
 *        └──── tap (RESULT) or swipe-down (any) ──────┘
 *
 * Glass XE uses [CameraGlView] (GL_OES_EGL_image_external) because
 * TextureView/SurfaceView paths produce channel-scrambled garbage on
 * the TI OMAP4 HAL. EE1 and EE2 use TextureView. Same Camera1 + string-
 * vs-typed-API split as the Scouter plugin.
 */
class TranslateActivity : Activity() {

    companion object {
        private const val TAG = "TranslateActivity"
        private const val CAMERA_PERM_REQUEST = 2001
        /** Empirically tuned — quality 60 keeps 640x360 frames around
         *  20-25 KB which transfers over BT 2.1 in ~1 s. Higher
         *  quality buys minimal OCR improvement at noticeable
         *  bandwidth cost. */
        private const val JPEG_QUALITY = 60
    }

    private enum class State { LIVE_PREVIEW, CAPTURING, RESULT, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isXE: Boolean by lazy {
        Build.MODEL?.equals("Glass 1", ignoreCase = true) == true
    }

    // Camera
    private var camera: Camera? = null
    private var preview: TextureView? = null
    private var glPreview: CameraGlView? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    /** Last raw NV21 frame from setPreviewCallback. Volatile because
     *  the camera thread writes it and the UI thread reads it on tap. */
    @Volatile private var lastNv21: ByteArray? = null
    private var previewW = 640
    private var previewH = 360

    // UI layers
    private lateinit var root: FrameLayout
    private lateinit var reticle: ReticleView
    private lateinit var frozenView: ImageView
    private lateinit var statusBanner: TextView
    private lateinit var cardScroller: ScrollView
    private lateinit var cardContainer: LinearLayout
    private var frozenBitmap: Bitmap? = null

    private var state: State = State.LIVE_PREVIEW

    // Touchpad swipe-down tracking (XE/EE1)
    private var downX = 0f
    private var downY = 0f
    @Volatile private var suppressNextTap = false

    // Auto-dismiss timer for the RESULT state
    private val backToLiveRunnable = Runnable { showLivePreview() }

    private val phoneMessageListener = TranslatePluginService.Listener { msg ->
        mainHandler.post { handlePhoneMessage(msg) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Camera surface — XE uses the GL view (samplerExternalOES);
        // EE1/EE2 use TextureView.
        if (isXE) {
            glPreview = CameraGlView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setRedFilter(false)  // Translate isn't a Scouter-style filtered HUD
                setOnSurfaceReady { st ->
                    previewSurfaceTexture = st
                    openCameraIfNeeded()
                }
            }
            root.addView(glPreview)
        } else {
            preview = TextureView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isOpaque = true
                surfaceTextureListener = textureListener
            }
            root.addView(preview)
        }

        // Frozen-frame display (shown when CAPTURING / RESULT).
        // Stacked above the camera surface so the camera can keep
        // running underneath without visible distraction.
        frozenView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(frozenView)

        // Reticle — small crosshair to aim by.
        reticle = ReticleView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(reticle)

        // Status banner (top-left chip). Shows "TAP TO TRANSLATE",
        // "TRANSLATING…", or error text.
        statusBanner = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val m = dp(8)
                setMargins(m, m, m, m)
            }
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text = "TAP TO TRANSLATE"
        }
        root.addView(statusBanner)

        // Card-mode scroller — only inflated/shown when display_mode
        // is "cards" and a result lands.
        cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        cardScroller = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(0xE6000000.toInt())
            addView(cardContainer)
        }
        root.addView(cardScroller)

        setContentView(root)

        TranslatePluginService.setListener(phoneMessageListener)

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERM_REQUEST
            )
        }
    }

    override fun onResume() {
        super.onResume()
        glPreview?.onResume()
        val ready = if (isXE) previewSurfaceTexture != null
                    else preview?.isAvailable == true
        if (ready) openCameraIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(backToLiveRunnable)
        releaseCamera()
        if (isXE) previewSurfaceTexture = null
        glPreview?.releaseCamera()
        glPreview?.onPause()
    }

    override fun onDestroy() {
        TranslatePluginService.setListener(null)
        frozenBitmap?.recycle()
        frozenBitmap = null
        super.onDestroy()
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

    // --- Camera lifecycle ---

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
            previewSurfaceTexture = s
            openCameraIfNeeded()
        }
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
            previewSurfaceTexture = null
            releaseCamera()
            return true
        }
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }

    @Suppress("DEPRECATION")
    private fun openCameraIfNeeded() {
        if (camera != null) return
        if (!hasCameraPermission()) return
        try {
            val cam = Camera.open()
            camera = cam
            val params = cam.parameters
            if (isXE) {
                // Glass XE camera HAL (TI OMAP4) only honours the
                // string-based parameter setters — see Scouter for the
                // full rationale. Use the canonical Glass GDK preview
                // sequence so the HAL writes standard NV21 instead of
                // its corrupt tiled-output mode.
                params.set("preview-size", "640x360")
                params.set("picture-size", "2528x1856")
                params.set("preview-frame-rate", "30")
                params.set("preview-fps-range", "30000,30000")
                previewW = 640; previewH = 360
                glPreview?.setCameraPreviewSize(previewW, previewH)
            } else {
                val target = 640 to 360
                val best = params.supportedPreviewSizes.minByOrNull { sz ->
                    Math.abs(sz.width - target.first) + Math.abs(sz.height - target.second)
                }
                if (best != null) {
                    params.setPreviewSize(best.width, best.height)
                    previewW = best.width; previewH = best.height
                }
            }
            // NV21 explicitly — we need to read raw frames out of
            // setPreviewCallback for the freeze + JPEG-encode path.
            if (params.supportedPreviewFormats.contains(ImageFormat.NV21)) {
                params.previewFormat = ImageFormat.NV21
            }
            if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            } else if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            cam.parameters = params
            try {
                if (isXE) {
                    cam.setDisplayOrientation(0)
                } else {
                    val info = Camera.CameraInfo()
                    Camera.getCameraInfo(0, info)
                    cam.setDisplayOrientation(info.orientation)
                }
            } catch (_: Exception) {}
            val st = previewSurfaceTexture
            if (st == null) {
                Log.w(TAG, "No SurfaceTexture available; aborting camera open")
                releaseCamera()
                return
            }
            cam.setPreviewTexture(st)
            cam.setPreviewCallback { data, _ -> lastNv21 = data }
            cam.startPreview()
            Log.i(TAG, "Camera started at ${previewW}x${previewH}")
        } catch (e: Exception) {
            Log.e(TAG, "Camera open failed: ${e.message}")
            releaseCamera()
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseCamera() {
        val cam = camera ?: return
        camera = null
        try { cam.setPreviewCallback(null) } catch (_: Exception) {}
        try { cam.stopPreview() } catch (_: Exception) {}
        try { cam.setPreviewTexture(null) } catch (_: Exception) {}
        try { cam.release() } catch (_: Exception) {}
    }

    private fun hasCameraPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    // --- Input handling ---

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleGesture(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                suppressNextTap = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val absDx = Math.abs(dx); val absDy = Math.abs(dy)
                if (dy > 120 && absDy > absDx * 1.3f) {
                    suppressNextTap = true
                    finish()
                    return true
                }
                if (absDx < 25 && absDy < 25 && !suppressNextTap) {
                    onTap()
                    return true
                }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onTap(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun onTap() {
        when (state) {
            State.LIVE_PREVIEW -> startCapture()
            State.RESULT, State.ERROR -> showLivePreview()
            State.CAPTURING -> { /* ignore — already in flight */ }
        }
    }

    // --- State transitions ---

    private fun startCapture() {
        val nv21 = lastNv21
        if (nv21 == null || nv21.isEmpty()) {
            statusBanner.text = "No frame yet — try again"
            return
        }

        // YUV → frozen Bitmap for display + JPEG bytes for transport.
        // YuvImage's built-in JPEG codec works fine here because the
        // bytes ARE standard NV21 (we asked for it via params on EE1/
        // EE2; on XE the string-API path produces standard NV21 too).
        val jpegBytes: ByteArray
        try {
            val baos = ByteArrayOutputStream(64 * 1024)
            YuvImage(nv21, ImageFormat.NV21, previewW, previewH, null)
                .compressToJpeg(Rect(0, 0, previewW, previewH), JPEG_QUALITY, baos)
            jpegBytes = baos.toByteArray()
        } catch (e: Throwable) {
            Log.e(TAG, "JPEG encode failed: ${e.message}")
            statusBanner.text = "Capture failed — try again"
            return
        }

        // Decode back to a Bitmap so we can show + later overlay on it.
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (bmp != null) {
            frozenBitmap?.recycle()
            frozenBitmap = bmp
            frozenView.setImageBitmap(bmp)
            frozenView.visibility = View.VISIBLE
            reticle.visibility = View.GONE
        }

        state = State.CAPTURING
        statusBanner.text = "TRANSLATING…"

        // Pull the user's current language picks from glass prefs
        // (managed by the dynamic-plugin settings UI on the phone).
        // We send them alongside the JPEG so the phone can pick the
        // right ML Kit recognizer + translator without keeping a
        // parallel copy of these settings.
        val prefs = getSharedPreferences(TranslatePluginService.PREFS_NAME, MODE_PRIVATE)
        val srcLang = prefs.getString("source_lang", "ja") ?: "ja"
        val tgtLang = prefs.getString("target_lang", "en") ?: "en"

        // Fire off to phone — JSON wrapper so we can ship metadata
        // alongside the JPEG without parsing magic.
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP or Base64.NO_PADDING)
        val payload = JSONObject().apply {
            put("src", srcLang)
            put("tgt", tgtLang)
            put("jpeg", b64)
        }.toString()
        TranslatePluginService.sendToPhoneFromActivity(
            GlassPluginMessage("TRANSLATE_REQUEST", payload)
        )
        Log.i(TAG, "Sent ${jpegBytes.size}-byte JPEG, $srcLang→$tgtLang")
    }

    private fun handlePhoneMessage(msg: GlassPluginMessage) {
        when (msg.type) {
            "TRANSLATE_RESULT" -> showResult(msg.payload)
            "TRANSLATE_ERROR" -> showError(msg.payload)
            else -> { /* ignore */ }
        }
    }

    private fun showResult(payload: String) {
        if (state != State.CAPTURING) return
        val parsed = try {
            parseResult(payload)
        } catch (e: JSONException) {
            Log.e(TAG, "Result parse failed: ${e.message}")
            showError("Bad response from phone")
            return
        }
        Log.i(TAG, "Result: ${parsed.size} block(s)")
        for ((i, b) in parsed.withIndex()) {
            Log.i(TAG, "  [$i] bbox=${b.bbox} \"${b.original}\" → \"${b.translated}\"")
        }

        val prefs = getSharedPreferences(TranslatePluginService.PREFS_NAME, MODE_PRIVATE)
        val mode = prefs.getString("display_mode", "overlay") ?: "overlay"
        val dismissMs = (prefs.getString("auto_dismiss_ms", "10000") ?: "10000").toLongOrNull()
            ?: 10_000L

        if (parsed.isEmpty()) {
            statusBanner.text = "No text detected"
        } else if (mode == "cards") {
            statusBanner.text = "${parsed.size} block${if (parsed.size == 1) "" else "s"}"
            renderCards(parsed)
        } else {
            statusBanner.text = "${parsed.size} block${if (parsed.size == 1) "" else "s"}"
            renderOverlay(parsed)
        }
        state = State.RESULT
        if (dismissMs > 0) {
            mainHandler.removeCallbacks(backToLiveRunnable)
            mainHandler.postDelayed(backToLiveRunnable, dismissMs)
        }
    }

    private fun showError(payload: String) {
        val err = try {
            JSONObject(payload).optString("error", payload)
        } catch (_: Exception) { payload }
        statusBanner.text = "Error: $err"
        state = State.ERROR
        mainHandler.removeCallbacks(backToLiveRunnable)
        mainHandler.postDelayed(backToLiveRunnable, 4_000L)
    }

    private fun showLivePreview() {
        mainHandler.removeCallbacks(backToLiveRunnable)
        frozenView.visibility = View.GONE
        frozenView.setImageBitmap(null)
        frozenBitmap?.recycle()
        frozenBitmap = null
        cardScroller.visibility = View.GONE
        cardContainer.removeAllViews()
        reticle.visibility = View.VISIBLE
        statusBanner.text = "TAP TO TRANSLATE"
        state = State.LIVE_PREVIEW
    }

    // --- Rendering helpers ---

    private data class ResultBlock(
        val original: String,
        val translated: String,
        val bbox: Rect,
    )

    private fun parseResult(payload: String): List<ResultBlock> {
        val obj = JSONObject(payload)
        val arr = obj.optJSONArray("blocks") ?: return emptyList()
        val out = mutableListOf<ResultBlock>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(ResultBlock(
                original = o.optString("o", ""),
                translated = o.optString("t", ""),
                bbox = Rect(
                    o.optInt("l", 0),
                    o.optInt("u", 0),
                    o.optInt("r", 0),
                    o.optInt("b", 0),
                ),
            ))
        }
        return out
    }

    private fun renderOverlay(blocks: List<ResultBlock>) {
        val src = frozenBitmap ?: return
        // Paint on a copy so the source frozen frame stays intact.
        val target = src.copy(Bitmap.Config.ARGB_8888, true) ?: return
        val canvas = Canvas(target)

        // 1. Cyan outline around each detected source text region —
        //    a marker, not an opaque mask. Vertical Japanese text has
        //    super narrow bboxes (~30 px wide × 130 tall); covering
        //    them up makes the translation unreadable, so we anchor
        //    instead.
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E5FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat().coerceAtLeast(1f)
        }
        // 2. Solid label background — sized to fit the English text,
        //    placed above (or below if no room) the source bbox.
        val labelBg = Paint().apply {
            color = 0xE6000000.toInt()
            style = Paint.Style.FILL
        }
        val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(11).toFloat()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val fm = labelText.fontMetrics
        val textH = (fm.descent - fm.ascent).toInt()
        val padH = dp(4)
        val padV = dp(2)

        for (b in blocks) {
            if (b.translated.isBlank()) continue
            // Outline the source text bbox.
            canvas.drawRect(b.bbox, outlinePaint)

            // Size the label by the translation's intrinsic width,
            // not the source bbox. Clamp to the bitmap width if the
            // translation is monstrously long.
            val measured = labelText.measureText(b.translated)
            val labelW = (measured.toInt() + padH * 2).coerceAtMost(target.width)
            val labelH = textH + padV * 2

            // Prefer above the source; flip below if it would clip.
            var labelTop = b.bbox.top - labelH - dp(2)
            if (labelTop < 0) {
                labelTop = (b.bbox.bottom + dp(2)).coerceAtMost(target.height - labelH)
            }
            // Anchor the label to the bbox's left, but keep it inside
            // the bitmap horizontally.
            val labelLeft = b.bbox.left.coerceIn(0, target.width - labelW)
            val labelRect = Rect(
                labelLeft,
                labelTop,
                labelLeft + labelW,
                labelTop + labelH,
            )
            canvas.drawRect(labelRect, labelBg)
            val baseline = labelTop + padV - fm.ascent
            canvas.drawText(
                b.translated,
                (labelLeft + padH).toFloat(),
                baseline,
                labelText,
            )
        }

        frozenView.setImageBitmap(target)
        frozenBitmap = target
    }

    private fun renderCards(blocks: List<ResultBlock>) {
        cardContainer.removeAllViews()
        for (b in blocks) {
            if (b.translated.isBlank() && b.original.isBlank()) continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF222222.toInt())
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(6)
                }
            }
            val src = TextView(this).apply {
                text = b.original
                setTextColor(0xFF888888.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            val tx = TextView(this).apply {
                text = b.translated
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            card.addView(src)
            card.addView(tx)
            cardContainer.addView(card)
        }
        cardScroller.visibility = View.VISIBLE
        frozenView.visibility = View.GONE  // hide the frozen frame; cards take the screen
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/** A small crosshair reticle painted in the center of the screen.
 *  Just a visual cue for "aim here" — no interaction. */
private class ReticleView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val arm = (width.coerceAtMost(height) * 0.18f).toInt()
        canvas.drawLine(cx - arm, cy, cx - arm * 0.3f, cy, paint)
        canvas.drawLine(cx + arm * 0.3f, cy, cx + arm, cy, paint)
        canvas.drawLine(cx, cy - arm, cx, cy - arm * 0.3f, paint)
        canvas.drawLine(cx, cy + arm * 0.3f, cx, cy + arm, paint)
        canvas.drawCircle(cx, cy, arm * 0.15f, paint)
    }
}
