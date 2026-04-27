package com.glasshole.plugin.opencv.glass

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * OpenCV Demo: live object bounding boxes + labels over the camera
 * viewfinder.
 *
 * Backend is ML Kit's bundled on-device object detector: returns a
 * list of [DetectedObject] each with a boundingBox + coarse label
 * (Home good / Fashion good / Food / Place / Plant by default, or a
 * generic tracking ID if unclassified). OpenCV proper isn't pulled in
 * — the overlay shape stays the same if we swap to TFLite / OpenCV
 * DNN later.
 */
class OpenCvActivity : ComponentActivity() {

    companion object {
        private const val TAG = "OpenCvDemo"
        private const val REQUEST_CAMERA = 101
    }

    private lateinit var previewView: PreviewView
    private lateinit var errorText: TextView
    private lateinit var overlay: BoundingBoxOverlay

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: ObjectDetector
    @Volatile private var inflight: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opencv)

        previewView = findViewById(R.id.previewView)
        errorText = findViewById(R.id.errorText)
        overlay = findViewById(R.id.boxOverlay)

        cameraExecutor = Executors.newSingleThreadExecutor()
        detector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                showError("Camera permission denied")
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
                showError("Camera unavailable: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || inflight) {
            proxy.close()
            return
        }
        inflight = true
        val rotation = proxy.imageInfo.rotationDegrees
        // Effective dimensions after the detector applies rotation — these
        // are the coordinates the returned bounding boxes are expressed in.
        val effectiveW: Int
        val effectiveH: Int
        if (rotation == 90 || rotation == 270) {
            effectiveW = proxy.height
            effectiveH = proxy.width
        } else {
            effectiveW = proxy.width
            effectiveH = proxy.height
        }

        val input = InputImage.fromMediaImage(media, rotation)
        detector.process(input)
            .addOnSuccessListener { detected ->
                runOnUiThread {
                    renderDetections(detected, effectiveW, effectiveH)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Detector failed: ${e.message}")
            }
            .addOnCompleteListener {
                inflight = false
                proxy.close()
            }
    }

    private fun renderDetections(
        detected: List<DetectedObject>,
        imageW: Int,
        imageH: Int
    ) {
        val viewW = overlay.width.toFloat()
        val viewH = overlay.height.toFloat()
        if (viewW <= 0f || viewH <= 0f || imageW <= 0 || imageH <= 0) {
            overlay.setBoxes(emptyList())
            return
        }

        // PreviewView defaults to FILL_CENTER — the image is scaled uniformly
        // to cover the view, cropping whichever axis overflows. Mirror that
        // math here so boxes line up with what the user sees.
        val scale = maxOf(viewW / imageW, viewH / imageH)
        val offsetX = (viewW - imageW * scale) / 2f
        val offsetY = (viewH - imageH * scale) / 2f

        val mapped = detected.map { obj ->
            val r = obj.boundingBox
            val rect = RectF(
                r.left * scale + offsetX,
                r.top * scale + offsetY,
                r.right * scale + offsetX,
                r.bottom * scale + offsetY
            )
            val best = obj.labels.maxByOrNull { it.confidence }
            val label = best?.text ?: "object"
            val confidence = best?.confidence ?: 0f
            BoundingBoxOverlay.Box(rect, label, confidence)
        }
        overlay.setBoxes(mapped)
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { detector.close() } catch (_: Exception) {}
        cameraExecutor.shutdown()
    }
}
