package com.glasshole.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.service.BridgeService
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Renders an MJPEG (multipart/x-mixed-replace) HTTP stream from the
 * glass — used by the Debug screen's "Live Camera" and "Glass Screen
 * Mirror" buttons. Sends LIVE_*_STOP back to glass on finish so the
 * camera HAL / MediaProjection is freed promptly.
 *
 * Two extras drive the activity:
 *   EXTRA_URL — full http://ip:port/stream?token=… URL from glass
 *   EXTRA_KIND — "camera" or "screen"; only affects the title and the
 *                stop opcode we send when the user backs out.
 */
class LiveStreamActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveStreamActivity"
        const val EXTRA_URL = "url"
        const val EXTRA_KIND = "kind"
        const val KIND_CAMERA = "camera"
        const val KIND_SCREEN = "screen"
    }

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var titleView: TextView

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false
    private var streamThread: Thread? = null
    @Volatile private var running = false
    private val ui = Handler(Looper.getMainLooper())

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            bridgeService = (b as BridgeService.LocalBinder).getService()
            bridgeBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bridgeBound = false
        }
    }

    private lateinit var streamUrl: String
    private lateinit var streamKind: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        streamUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        streamKind = intent.getStringExtra(EXTRA_KIND) ?: KIND_CAMERA
        if (streamUrl.isEmpty()) {
            finish()
            return
        }

        // Keep the screen on while the user is watching.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Build the UI in code — single-purpose page, not worth a layout file.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(image)

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        titleView = TextView(this).apply {
            text = if (streamKind == KIND_SCREEN) "Glass Screen Mirror" else "Glass Camera"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, dp(12))
            gravity = android.view.Gravity.CENTER
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
        }
        status = TextView(this).apply {
            text = "Connecting…"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(24), dp(12), dp(24), 0)
            gravity = android.view.Gravity.CENTER
        }
        overlay.addView(titleView)
        overlay.addView(spinner)
        overlay.addView(status)
        root.addView(overlay)

        setContentView(root)

        bindService(
            Intent(this, BridgeService::class.java),
            bridgeConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStart() {
        super.onStart()
        running = true
        streamThread = Thread { runStream() }.apply {
            isDaemon = true
            name = "LiveStream-fetch"
            start()
        }
    }

    override fun onStop() {
        super.onStop()
        running = false
        streamThread?.interrupt()
        streamThread = null
    }

    override fun onDestroy() {
        // Tell glass to tear down the camera / projection. Best-effort
        // — if the bridge is gone we just leak a few seconds of capture
        // until the BT disconnect handler also stops it.
        try {
            when (streamKind) {
                KIND_SCREEN -> bridgeService?.sendLiveScreenStop()
                else -> bridgeService?.sendLiveCamStop()
            }
        } catch (_: Exception) {}
        if (bridgeBound) {
            unbindService(bridgeConnection)
            bridgeBound = false
        }
        super.onDestroy()
    }

    private fun setStatus(msg: String?, showSpinner: Boolean) {
        ui.post {
            status.text = msg.orEmpty()
            status.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
            spinner.visibility = if (showSpinner) View.VISIBLE else View.GONE
            // Hide the title once frames start flowing — overlay clutter
            // belongs only on the loading state.
            titleView.visibility = if (showSpinner) View.VISIBLE else View.GONE
        }
    }

    private fun showFrame(bytes: ByteArray) {
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "decode frame failed: ${e.message}")
            null
        } ?: return
        ui.post {
            image.setImageBitmap(bmp)
            // First successful frame: hide the loading overlay.
            if (status.visibility == View.VISIBLE) {
                setStatus(null, false)
            }
        }
    }

    private fun runStream() {
        var attempts = 0
        while (running) {
            attempts++
            try {
                val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                }
                val code = conn.responseCode
                if (code != 200) {
                    setStatus("HTTP $code from glass", false)
                    return
                }
                val ctype = conn.contentType ?: ""
                val boundary = parseBoundary(ctype)
                if (boundary == null) {
                    setStatus("Stream missing multipart boundary", false)
                    return
                }
                BufferedInputStream(conn.inputStream).use { input ->
                    parseMjpeg(input, boundary)
                }
                if (running) {
                    setStatus("Reconnecting…", true)
                    Thread.sleep(1_000)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: IOException) {
                if (!running) return
                setStatus("Network error: ${e.message ?: "connect failed"}", true)
                if (attempts >= 4) {
                    setStatus("Could not reach glass at $streamUrl", false)
                    return
                }
                try { Thread.sleep(1_500) } catch (_: InterruptedException) { return }
            } catch (e: Exception) {
                Log.w(TAG, "stream loop crash: ${e.message}")
                if (!running) return
                setStatus("Stream error: ${e.message}", false)
                return
            }
        }
    }

    private fun parseBoundary(contentType: String): String? {
        val idx = contentType.indexOf("boundary=")
        if (idx < 0) return null
        val raw = contentType.substring(idx + "boundary=".length).substringBefore(";").trim()
        return raw.removeSurrounding("\"")
    }

    /**
     * Streaming multipart parser. MJPEG framing is a sequence of:
     *   --<boundary>\r\n
     *   Content-Type: image/jpeg\r\n
     *   Content-Length: <n>\r\n
     *   \r\n
     *   <n bytes of JPEG>
     *   \r\n
     * Some servers omit Content-Length; we handle that by looking for
     * the JPEG end-of-image marker (0xFF 0xD9). Glass-side server
     * always includes Content-Length so the slow-path is rarely used.
     */
    private fun parseMjpeg(input: InputStream, boundary: String) {
        val boundaryBytes = ("--$boundary").toByteArray()
        // Find the first boundary.
        if (!seekTo(input, boundaryBytes)) return
        while (running) {
            // Read header lines until empty line.
            var contentLength = -1
            while (running) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: -1
                }
            }

            val frame: ByteArray? = if (contentLength > 0) {
                readN(input, contentLength)
            } else {
                // Slow path: read until we hit the next boundary.
                readUntil(input, boundaryBytes)
            }
            if (frame == null) return
            if (frame.isNotEmpty()) showFrame(frame)

            if (contentLength > 0) {
                // Skip the \r\n separator and the next boundary line.
                if (!seekTo(input, boundaryBytes)) return
            }
        }
    }

    private fun seekTo(input: InputStream, needle: ByteArray): Boolean {
        var matched = 0
        while (running) {
            val b = input.read()
            if (b < 0) return false
            if (b.toByte() == needle[matched]) {
                matched++
                if (matched == needle.size) {
                    // Consume up to and including the trailing newline so the
                    // next read sees header bytes.
                    while (running) {
                        val n = input.read()
                        if (n < 0) return false
                        if (n == '\n'.code) return true
                    }
                    return false
                }
            } else if (matched > 0) {
                matched = if (b.toByte() == needle[0]) 1 else 0
            }
        }
        return false
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (running) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                // Strip a trailing \r if present.
                val s = sb.toString()
                return if (s.endsWith('\r')) s.dropLast(1) else s
            }
            sb.append(b.toChar())
        }
        return null
    }

    private fun readN(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var read = 0
        while (read < n && running) {
            val r = input.read(out, read, n - read)
            if (r < 0) return null
            read += r
        }
        return if (running) out else null
    }

    private fun readUntil(input: InputStream, needle: ByteArray): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        var matched = 0
        while (running) {
            val b = input.read()
            if (b < 0) return null
            if (b.toByte() == needle[matched]) {
                matched++
                if (matched == needle.size) {
                    val arr = buf.toByteArray()
                    // Drop the trailing \r\n that precedes the boundary.
                    val drop = if (arr.size >= 2 &&
                        arr[arr.size - 2] == '\r'.code.toByte() &&
                        arr[arr.size - 1] == '\n'.code.toByte()) 2 else 0
                    return arr.copyOfRange(0, arr.size - drop)
                }
            } else {
                if (matched > 0) {
                    // Re-emit the partial match into the buffer.
                    for (i in 0 until matched) buf.write(needle[i].toInt())
                    matched = if (b.toByte() == needle[0]) 1 else 0
                    if (matched == 0) buf.write(b)
                } else {
                    buf.write(b)
                }
            }
        }
        return null
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
