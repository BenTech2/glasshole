package com.glasshole.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.bt.ProtocolCodec
import com.glasshole.phone.debug.NotificationReplayStore
import com.glasshole.phone.service.BridgeService
import com.glasshole.phone.service.NotificationForwardingService
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import org.json.JSONArray
import org.json.JSONObject

class DebugActivity : AppCompatActivity() {

    private lateinit var appInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var textInput: EditText
    private lateinit var sendButton: Button
    private lateinit var sendReplyButton: Button
    private lateinit var sendOpenOnDeviceButton: Button
    private lateinit var sendStreamButton: Button
    private lateinit var sendMultiButton: Button
    private lateinit var sendImageButton: Button
    private lateinit var resetAdminPromptButton: Button
    private lateinit var statusText: TextView

    // Actions group
    private lateinit var wakeGlassButton: Button
    private lateinit var enableWirelessAdbButton: Button
    private lateinit var takePictureButton: Button
    private lateinit var screenshotButton: Button
    private lateinit var recordVideoButton: Button
    private lateinit var recordDurationSlider: Slider
    private lateinit var recordDurationLabel: TextView

    // Live streams group
    private lateinit var liveCameraButton: Button
    private lateinit var liveScreenButton: Button
    @Volatile private var liveRequestPending: Boolean = false

    // LAN file share — Debug-screen recovery server for downloading
    // APKs to the glass over Wi-Fi when USB / BT APK transfer isn't
    // working. See FileShareServer for the protocol.
    private lateinit var fileShareDirText: TextView
    private lateinit var fileShareUrlText: TextView
    private lateinit var fileShareToggleButton: Button
    private lateinit var fileShareCopyButton: Button
    private var fileShareServer: com.glasshole.phone.debug.FileShareServer? = null

    private lateinit var captureSwitch: MaterialSwitch
    private lateinit var captureLimitSpinner: Spinner
    private lateinit var captureCountText: TextView
    private lateinit var clearCacheButton: Button
    private lateinit var replaySpinner: Spinner
    private lateinit var replayButton: Button
    private lateinit var replayRefreshButton: Button
    private var replayEntries: List<NotificationReplayStore.Entry> = emptyList()

    // Cache-limit dropdown options. -1 maps to UNLIMITED in the store.
    private val limitOptions = listOf(100, 250, 500, 1000, NotificationReplayStore.UNLIMITED)
    private val limitLabels = listOf("100", "250", "500", "1000", "Unlimited")

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false
    private var testCounter = 0

    // Cached test images, fetched eagerly so the test-send buttons
    // don't block on a network round-trip when tapped.
    @Volatile private var cachedTestImageBase64: String? = null
    @Volatile private var cachedYoutubeThumbBase64: String? = null

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as BridgeService.LocalBinder).getService()
            bridgeBound = true
            updateStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bridgeBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        appInput = findViewById(R.id.debugAppInput)
        titleInput = findViewById(R.id.debugTitleInput)
        textInput = findViewById(R.id.debugTextInput)
        sendButton = findViewById(R.id.debugSendButton)
        sendReplyButton = findViewById(R.id.debugSendReplyButton)
        sendOpenOnDeviceButton = findViewById(R.id.debugSendOpenOnDeviceButton)
        sendStreamButton = findViewById(R.id.debugSendStreamButton)
        sendMultiButton = findViewById(R.id.debugSendMultiButton)
        sendImageButton = findViewById(R.id.debugSendImageButton)
        resetAdminPromptButton = findViewById(R.id.debugResetAdminPromptButton)
        statusText = findViewById(R.id.debugStatusText)

        wakeGlassButton = findViewById(R.id.debugWakeGlassButton)
        enableWirelessAdbButton = findViewById(R.id.debugEnableWirelessAdbButton)
        takePictureButton = findViewById(R.id.debugTakePictureButton)
        screenshotButton = findViewById(R.id.debugScreenshotButton)
        recordVideoButton = findViewById(R.id.debugRecordVideoButton)
        recordDurationSlider = findViewById(R.id.debugRecordDurationSlider)
        recordDurationLabel = findViewById(R.id.debugRecordDurationLabel)

        liveCameraButton = findViewById(R.id.debugLiveCameraButton)
        liveScreenButton = findViewById(R.id.debugLiveScreenButton)

        fileShareDirText = findViewById(R.id.debugFileShareDirText)
        fileShareUrlText = findViewById(R.id.debugFileShareUrlText)
        fileShareToggleButton = findViewById(R.id.debugFileShareToggleButton)
        fileShareCopyButton = findViewById(R.id.debugFileShareCopyButton)
        setupFileShare()

        captureSwitch = findViewById(R.id.debugCaptureSwitch)
        captureLimitSpinner = findViewById(R.id.debugCaptureLimitSpinner)
        captureCountText = findViewById(R.id.debugCaptureCount)
        clearCacheButton = findViewById(R.id.debugClearCacheButton)
        replaySpinner = findViewById(R.id.debugReplaySpinner)
        replayButton = findViewById(R.id.debugReplayButton)
        replayRefreshButton = findViewById(R.id.debugReplayRefreshButton)

        sendButton.setOnClickListener { sendVariant(Variant.PLAIN) }
        sendReplyButton.setOnClickListener { sendVariant(Variant.REPLY) }
        sendOpenOnDeviceButton.setOnClickListener { sendVariant(Variant.OPEN_PHONE) }
        sendStreamButton.setOnClickListener { sendVariant(Variant.STREAM) }
        sendMultiButton.setOnClickListener { sendVariant(Variant.MULTI) }
        sendImageButton.setOnClickListener { sendVariant(Variant.IMAGE) }
        resetAdminPromptButton.setOnClickListener { sendResetAdminPrompt() }

        setupCaptureControls()
        setupActions()
        setupLiveStreams()

        bindService(
            Intent(this, BridgeService::class.java),
            bridgeConnection,
            Context.BIND_AUTO_CREATE
        )

        prefetchTestImages()
    }

    private fun prefetchTestImages() {
        Thread {
            if (cachedTestImageBase64 == null) {
                cachedTestImageBase64 = fetchAndEncode(
                    "https://upload.wikimedia.org/wikipedia/en/2/27/Bliss_%28Windows_XP%29.png"
                )
            }
            if (cachedYoutubeThumbBase64 == null) {
                // Matches the TEST_VIDEO_ID used by the STREAM variant. A real
                // YouTube notification attaches this same thumbnail image.
                cachedYoutubeThumbBase64 = fetchAndEncode(
                    "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
                )
            }
        }.apply { isDaemon = true; start() }
    }

    private fun fetchAndEncode(urlStr: String): String? {
        return try {
            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                instanceFollowRedirects = true
                // Wikimedia rejects default UAs; YouTube doesn't mind but it's
                // a reasonable default for any debug image fetch.
                setRequestProperty(
                    "User-Agent",
                    "GlassHole-Debug/1.0 (https://github.com/glasshole)"
                )
            }
            val bmp = conn.inputStream.use {
                android.graphics.BitmapFactory.decodeStream(it)
            } ?: return null
            // Keep the payload small — BT pipe chokes on full-res.
            val maxEdge = 480
            val longest = maxOf(bmp.width, bmp.height)
            val scaled = if (longest > maxEdge) {
                val ratio = maxEdge.toFloat() / longest
                android.graphics.Bitmap.createScaledBitmap(
                    bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true
                ).also { if (it !== bmp) bmp.recycle() }
            } else bmp
            val stream = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, stream)
            scaled.recycle()
            android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        if (bridgeBound) {
            unbindService(bridgeConnection)
            bridgeBound = false
        }
        // Don't leave a listening socket dangling if the user just
        // backs out of the Debug screen.
        stopFileShare()
        super.onDestroy()
    }

    private fun updateStatus() {
        val bridge = bridgeService
        statusText.text = when {
            bridge == null -> "BridgeService not bound"
            bridge.isConnected -> "Glass connected — test notifications ready"
            else -> "Glass not connected — tap Connect on the main screen first"
        }
        val enabled = bridge?.isConnected == true
        sendButton.isEnabled = enabled
        sendReplyButton.isEnabled = enabled
        sendOpenOnDeviceButton.isEnabled = enabled
        sendStreamButton.isEnabled = enabled
        sendMultiButton.isEnabled = enabled
        sendImageButton.isEnabled = enabled
        resetAdminPromptButton.isEnabled = enabled
        wakeGlassButton.isEnabled = enabled
        enableWirelessAdbButton.isEnabled = enabled
        takePictureButton.isEnabled = enabled
        screenshotButton.isEnabled = enabled && !liveRequestPending
        recordVideoButton.isEnabled = enabled
        liveCameraButton.isEnabled = enabled && !liveRequestPending
        liveScreenButton.isEnabled = enabled && !liveRequestPending
    }

    private fun setupLiveStreams() {
        liveCameraButton.setOnClickListener { requestLiveStream(camera = true) }
        liveScreenButton.setOnClickListener { requestLiveStream(camera = false) }
    }

    private fun requestLiveStream(camera: Boolean) {
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            toast("Glass not connected")
            updateStatus()
            return
        }
        if (liveRequestPending) {
            toast("Already requesting a stream — give it a moment")
            return
        }

        liveRequestPending = true
        updateStatus()

        // Simple single-shot listeners that clear themselves once a
        // response (URL or ERR) lands. The glass also has a 30s of
        // protocol-level latency budget; we add a 12s UI fallback so
        // a never-ending pending state doesn't block subsequent taps.
        val timeout = Runnable {
            runOnUiThread {
                if (liveRequestPending) {
                    liveRequestPending = false
                    bridge.onLiveCamUrl = null
                    bridge.onLiveCamErr = null
                    bridge.onLiveScreenUrl = null
                    bridge.onLiveScreenErr = null
                    toast("Glass timed out — no response")
                    updateStatus()
                }
            }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(timeout, 12_000L)

        val onUrl = { url: String ->
            handler.removeCallbacks(timeout)
            runOnUiThread {
                liveRequestPending = false
                bridge.onLiveCamUrl = null
                bridge.onLiveCamErr = null
                bridge.onLiveScreenUrl = null
                bridge.onLiveScreenErr = null
                openLiveStream(url, camera)
                updateStatus()
            }
        }
        val onErr = { reason: String ->
            handler.removeCallbacks(timeout)
            runOnUiThread {
                liveRequestPending = false
                bridge.onLiveCamUrl = null
                bridge.onLiveCamErr = null
                bridge.onLiveScreenUrl = null
                bridge.onLiveScreenErr = null
                toast(liveErrorMessage(reason, camera))
                updateStatus()
            }
        }

        if (camera) {
            bridge.onLiveCamUrl = onUrl
            bridge.onLiveCamErr = onErr
            bridge.sendLiveCamStart()
        } else {
            bridge.onLiveScreenUrl = onUrl
            bridge.onLiveScreenErr = onErr
            bridge.sendLiveScreenStart()
        }
    }

    private fun openLiveStream(url: String, camera: Boolean) {
        val intent = Intent(this, LiveStreamActivity::class.java).apply {
            putExtra(LiveStreamActivity.EXTRA_URL, url)
            putExtra(
                LiveStreamActivity.EXTRA_KIND,
                if (camera) LiveStreamActivity.KIND_CAMERA else LiveStreamActivity.KIND_SCREEN
            )
        }
        startActivity(intent)
    }

    /**
     * One-shot screen-grab from glass. Same handshake as the live
     * mirror (`LIVE_SCREEN_START` → wait for URL → ...) but instead of
     * launching the streaming viewer we hit the server's `/still`
     * endpoint, save the JPEG to Pictures/GlassHole/Screenshots, and
     * fire `LIVE_SCREEN_STOP` so the projection tears down.
     */
    private fun requestGlassScreenshot() {
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            toast("Glass not connected")
            updateStatus()
            return
        }
        if (liveRequestPending) {
            toast("Already requesting a stream — wait a moment")
            return
        }
        liveRequestPending = true
        updateStatus()

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeout = Runnable {
            runOnUiThread {
                if (liveRequestPending) {
                    liveRequestPending = false
                    bridge.onLiveScreenUrl = null
                    bridge.onLiveScreenErr = null
                    toast("Glass timed out — no response")
                    updateStatus()
                }
            }
        }
        handler.postDelayed(timeout, 12_000L)

        bridge.onLiveScreenUrl = { url ->
            handler.removeCallbacks(timeout)
            runOnUiThread {
                bridge.onLiveScreenUrl = null
                bridge.onLiveScreenErr = null
                fetchAndSaveStill(url) {
                    bridge.sendLiveScreenStop()
                    liveRequestPending = false
                    updateStatus()
                }
            }
        }
        bridge.onLiveScreenErr = { reason ->
            handler.removeCallbacks(timeout)
            runOnUiThread {
                liveRequestPending = false
                bridge.onLiveScreenUrl = null
                bridge.onLiveScreenErr = null
                toast(liveErrorMessage(reason, camera = false))
                updateStatus()
            }
        }
        toast("Capturing screenshot…")
        bridge.sendLiveScreenStart()
    }

    /**
     * Replace the `/stream` path in [streamUrl] with `/still`, GET it,
     * and save the response JPEG to Pictures/GlassHole/Screenshots.
     */
    private fun fetchAndSaveStill(streamUrl: String, onComplete: () -> Unit) {
        Thread {
            try {
                val stillUrl = streamUrl.replace("/stream", "/still")
                val conn = (java.net.URL(stillUrl).openConnection()
                    as java.net.HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 8_000
                }
                val bytes = try {
                    if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
                saveScreenshotJpeg(bytes, source = "screen")
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Screenshot failed: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { onComplete() }
            }
        }.apply { isDaemon = true; name = "GlassScreenshot"; start() }
    }

    /**
     * Persist [jpegBytes] to `Pictures/GlassHole/Screenshots/` via
     * MediaStore on Android 10+, direct file fallback below that.
     */
    private fun saveScreenshotJpeg(jpegBytes: ByteArray, source: String) {
        try {
            val name = "GlassHole-$source-${
                java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
            }.jpg"
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/GlassHole/Screenshots")
                }
                contentResolver.insert(
                    android.provider.MediaStore.Images.Media
                        .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                )
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                ).resolve("GlassHole/Screenshots")
                dir.mkdirs()
                val out = java.io.File(dir, name)
                android.net.Uri.fromFile(out)
            }
            if (uri == null) {
                runOnUiThread {
                    Toast.makeText(this, "Save failed", Toast.LENGTH_LONG).show()
                }
                return
            }
            contentResolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
            runOnUiThread {
                Toast.makeText(this,
                    "Saved $name to Pictures/GlassHole/Screenshots",
                    Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Save failed: ${e.message}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun liveErrorMessage(reason: String, camera: Boolean): String = when (reason) {
        "no_wifi" -> "Glass isn't on Wi-Fi — connect it first"
        "permission_required" -> "Grant camera permission on glass, then retry"
        "camera_busy" -> "Camera is in use by another app on glass"
        "unsupported_edition" -> "Screen mirror only works on Glass EE2"
        "consent_denied" -> "User denied the screen-record consent on glass"
        "capture_failed" -> "Glass failed to start screen capture"
        "user_revoked" -> "User stopped sharing on glass"
        "launch_failed" -> "Glass couldn't show the consent dialog"
        else -> if (camera) "Live camera failed: $reason" else "Screen mirror failed: $reason"
    }

    private fun setupActions() {
        recordDurationSlider.addOnChangeListener { _, value, _ ->
            recordDurationLabel.text = "${value.toInt()}s"
        }
        wakeGlassButton.setOnClickListener {
            sendPluginAction("device", "WAKE", "", "Wake sent")
        }
        enableWirelessAdbButton.setOnClickListener {
            val bridge = bridgeService
            if (bridge == null || !bridge.isConnected) {
                toast("Glass not connected"); updateStatus(); return@setOnClickListener
            }
            enableWirelessAdbButton.isEnabled = false
            toast("Asking glass — grant root on the headset if prompted")
            bridge.enableGlassWirelessAdb { ok, message ->
                enableWirelessAdbButton.isEnabled = bridge.isConnected
                toast(if (ok) "Wireless ADB on · $message" else "Failed: $message")
            }
        }
        takePictureButton.setOnClickListener {
            sendPluginAction("camera2", "CAPTURE_STILL", "", "Capture sent")
        }
        screenshotButton.setOnClickListener { requestGlassScreenshot() }
        recordVideoButton.setOnClickListener {
            val seconds = recordDurationSlider.value.toInt().coerceAtLeast(1)
            val payload = JSONObject().apply {
                put("duration_ms", seconds * 1000L)
            }.toString()
            sendPluginAction("camera2", "RECORD_VIDEO", payload, "Recording ${seconds}s")
        }
    }

    private fun sendPluginAction(
        pluginId: String,
        type: String,
        payload: String,
        successMessage: String
    ) {
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            toast("Glass not connected")
            updateStatus()
            return
        }
        val ok = bridge.sendPluginMessage(pluginId, type, payload)
        toast(if (ok) successMessage else "Send failed")
    }

    private fun sendResetAdminPrompt() {
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            toast("Glass not connected")
            updateStatus()
            return
        }
        val ok = bridge.sendResetHomeAdminPrompt()
        toast(if (ok) "Sent — open GlassHole on the glass" else "Send failed")
    }

    private enum class Variant { PLAIN, REPLY, OPEN_PHONE, STREAM, MULTI, IMAGE }

    private fun sendVariant(variant: Variant) {
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            toast("Glass not connected")
            updateStatus()
            return
        }
        val listener = NotificationForwardingService.instance
        if (listener == null) {
            toast("Notification listener not active — enable in Settings first")
            return
        }

        val app = appInput.text.toString().ifBlank { "GlassHole" }
        val titleFromInput = titleInput.text.toString().trim()
        val bodyFromInput = textInput.text.toString().trim()

        testCounter++
        val key = "debug-$testCounter-${System.currentTimeMillis()}"

        val (title, text, actions, handlers) = buildVariant(
            variant, app, titleFromInput, bodyFromInput
        )

        listener.registerDebugHandlers(key, handlers)

        // Stream test: also register a real open_phone action so tapping
        // "Open on Phone" on the glass relays through ACTION_VIEW and lands
        // on the actual YouTube video, not a toast.
        if (variant == Variant.STREAM) {
            listener.registerDebugOpenPhone(
                notifKey = key,
                actionId = "a1",
                pkg = "com.google.android.youtube",
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
        }

        val json = JSONObject().apply {
            put("key", key)
            put("pkg", "com.glasshole.phone.debug")
            put("app", app)
            put("title", title)
            put("text", text)
            put("actions", actions)
            if (variant == Variant.IMAGE) {
                val picture = cachedTestImageBase64 ?: generateTestImageBase64()
                if (picture != null) put("picture", picture)
            }
            if (variant == Variant.STREAM) {
                // Real YouTube notifications ship a video thumbnail as their
                // largeIcon/picture; mirror that so the Glass card matches.
                cachedYoutubeThumbBase64?.let { put("picture", it) }
            }
        }.toString()

        val sent = bridge.sendRaw(ProtocolCodec.encodeNotif(json))
        toast(if (sent) "Sent ${variant.name.lowercase().replace('_', ' ')}" else "Send failed")
    }

    private data class VariantPayload(
        val title: String,
        val text: String,
        val actions: JSONArray,
        val handlers: Map<String, (String?) -> Unit>
    )

    private fun buildVariant(
        variant: Variant,
        app: String,
        titleIn: String,
        bodyIn: String
    ): VariantPayload {
        fun plainTitle(default: String) = titleIn.ifBlank { default }
        fun plainBody(default: String) = bodyIn.ifBlank { default }

        return when (variant) {
            Variant.PLAIN -> VariantPayload(
                title = plainTitle("Test notification"),
                text = plainBody("Plain card — no actions"),
                actions = JSONArray(),
                handlers = emptyMap()
            )

            Variant.REPLY -> {
                val actions = JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "a0"); put("label", "Reply"); put("type", "reply")
                    })
                }
                val handlers = mapOf<String, (String?) -> Unit>(
                    "a0" to { reply ->
                        runOnUiThread {
                            toast("Got reply: ${reply ?: "<empty>"}")
                        }
                    }
                )
                VariantPayload(
                    title = plainTitle("Sarah"),
                    text = plainBody("Are you coming to dinner tonight?"),
                    actions = actions,
                    handlers = handlers
                )
            }

            Variant.OPEN_PHONE -> {
                val actions = JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "a0"); put("label", "Open on Phone"); put("type", "open_phone")
                    })
                }
                val handlers = mapOf<String, (String?) -> Unit>(
                    "a0" to { _ ->
                        runOnUiThread {
                            toast("Open on Phone triggered")
                        }
                    }
                )
                VariantPayload(
                    title = plainTitle("News alert"),
                    text = plainBody("Tap 'Open on Phone' to view the article on your device"),
                    actions = actions,
                    handlers = handlers
                )
            }

            Variant.STREAM -> {
                val testUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                val actions = JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "stream")
                        put("label", "Watch on Glass")
                        put("type", "open_glass_stream")
                        put("url", testUrl)
                    })
                    put(JSONObject().apply {
                        put("id", "a1"); put("label", "Open on Phone"); put("type", "open_phone")
                    })
                }
                // Only a1 is a debug toast — a1 is routed to the real relay
                // path below so tapping "Open on Phone" actually opens the
                // video. The "stream" handler just logs on the phone side.
                val handlers = mapOf<String, (String?) -> Unit>(
                    "stream" to { _ -> runOnUiThread { toast("Stream handed to glass") } }
                )
                VariantPayload(
                    title = plainTitle("Someone shared a video"),
                    text = plainBody("Check out this clip: $testUrl"),
                    actions = actions,
                    handlers = handlers
                )
            }

            Variant.IMAGE -> VariantPayload(
                title = plainTitle("Sarah"),
                text = plainBody("Check out the sunset from the beach!"),
                actions = JSONArray(),
                handlers = emptyMap()
            )

            Variant.MULTI -> {
                val actions = JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "reply"); put("label", "Reply"); put("type", "reply")
                    })
                    put(JSONObject().apply {
                        put("id", "open"); put("label", "Open"); put("type", "open_phone")
                    })
                }
                val handlers = mapOf<String, (String?) -> Unit>(
                    "reply" to { reply -> runOnUiThread { toast("Got reply: ${reply ?: "<empty>"}") } },
                    "open" to { _ -> runOnUiThread { toast("Open triggered") } }
                )
                VariantPayload(
                    title = plainTitle("Team Chat"),
                    text = plainBody("Alex: meeting moved to 3pm — see you there"),
                    actions = actions,
                    handlers = handlers
                )
            }
        }
    }

    private fun generateTestImageBase64(): String? {
        return try {
            val size = 200
            val bmp = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            // Sunset gradient (orange → deep red)
            val paint = android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, size.toFloat(),
                    intArrayOf(0xFFFFB74D.toInt(), 0xFFFF5722.toInt(), 0xFF7B1FA2.toInt()),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            // Sun
            val sunPaint = android.graphics.Paint().apply {
                color = 0xFFFFF176.toInt()
                isAntiAlias = true
            }
            canvas.drawCircle(size / 2f, size * 0.55f, size * 0.18f, sunPaint)
            val stream = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, stream)
            bmp.recycle()
            android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // --- LAN file share -------------------------------------------------

    /** Roots the share at an app-private external-files subdirectory
     *  (`Android/data/com.glasshole.phone/files/http-share/`). No
     *  storage permission needed; adb can still push into it. */
    private fun fileShareRoot(): java.io.File {
        val root = java.io.File(getExternalFilesDir(null), "http-share")
        if (!root.exists()) root.mkdirs()
        return root
    }

    private fun setupFileShare() {
        val root = fileShareRoot()
        fileShareDirText.text = "Sharing: ${root.absolutePath}"
        fileShareUrlText.text = "Not running."

        fileShareToggleButton.setOnClickListener {
            val running = fileShareServer?.url != null
            if (running) stopFileShare() else startFileShare()
        }

        fileShareCopyButton.setOnClickListener {
            val url = fileShareServer?.url ?: return@setOnClickListener
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("file share url", url))
            toast("Copied")
        }
    }

    private fun startFileShare() {
        val server = com.glasshole.phone.debug.FileShareServer(this, fileShareRoot())
        val url = server.start()
        if (url == null) {
            toast("Couldn't start — is the phone on Wi-Fi?")
            return
        }
        fileShareServer = server
        fileShareUrlText.text = url
        fileShareToggleButton.text = "Stop file server"
        fileShareCopyButton.isEnabled = true
        toast("File server running")
    }

    private fun stopFileShare() {
        fileShareServer?.stop()
        fileShareServer = null
        fileShareUrlText.text = "Not running."
        fileShareToggleButton.text = "Start file server"
        fileShareCopyButton.isEnabled = false
    }

    // --- Notification capture / replay (debug) ---

    private fun setupCaptureControls() {
        // Toggle reflects the persisted opt-in flag.
        captureSwitch.isChecked = NotificationReplayStore.isEnabled(this)
        captureSwitch.setOnCheckedChangeListener { _, checked ->
            NotificationReplayStore.setEnabled(this, checked)
            updateCaptureCount()
        }

        // Limit spinner — preselect the saved limit (or "Unlimited" if no
        // exact match in our discrete list).
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, limitLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        captureLimitSpinner.adapter = adapter
        val savedLimit = NotificationReplayStore.getLimit(this)
        val savedIdx = limitOptions.indexOf(savedLimit).let {
            if (it >= 0) it else limitOptions.indexOf(NotificationReplayStore.UNLIMITED)
        }
        captureLimitSpinner.setSelection(savedIdx)
        captureLimitSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    val newLimit = limitOptions[pos]
                    if (newLimit != NotificationReplayStore.getLimit(this@DebugActivity)) {
                        NotificationReplayStore.setLimit(this@DebugActivity, newLimit)
                        updateCaptureCount()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        clearCacheButton.setOnClickListener {
            NotificationReplayStore.clear(this)
            refreshReplaySpinner()
            updateCaptureCount()
            toast("Cleared")
        }

        replayRefreshButton.setOnClickListener { refreshReplaySpinner() }
        replayButton.setOnClickListener { replaySelected() }

        refreshReplaySpinner()
        updateCaptureCount()
    }

    private fun updateCaptureCount() {
        captureCountText.text = "${NotificationReplayStore.count(this)} stored"
    }

    private fun refreshReplaySpinner() {
        replayEntries = NotificationReplayStore.allNewestFirst(this)
        val labels = if (replayEntries.isEmpty()) {
            listOf("(no captured notifications)")
        } else {
            replayEntries.map { it.summary() }
        }
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        replaySpinner.adapter = adapter
        replayButton.isEnabled = replayEntries.isNotEmpty()
    }

    private fun replaySelected() {
        if (replayEntries.isEmpty()) return
        val idx = replaySpinner.selectedItemPosition.coerceIn(0, replayEntries.size - 1)
        val entry = replayEntries[idx]
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            toast("Glass not connected")
            return
        }
        // Captures from before the YouTube-thumbnail fix won't include
        // a "picture" field for Shorts notifications. Try to enrich
        // the replay payload before sending — sync cache hit fires
        // straight away, miss kicks off a download in the background
        // and re-fires the replay once the JPEG lands.
        Thread {
            val replayed = augmentForReplay(entry.json)
            runOnUiThread {
                val ok = bridge.sendRaw(ProtocolCodec.encodeNotif(replayed))
                toast(if (ok) "Replayed" else "Send failed")
            }
        }.apply { isDaemon = true; name = "ReplayAugment"; start() }
    }

    /**
     * StatusBarNotification keys are formatted
     * `<userId>|<pkg>|<id>|<tag>|<uid>` — when we replay a YouTube
     * notification captured before the video_id field was added, we
     * can still recover the tag from the saved key. For Shorts /
     * single-video alerts the tag is `<11charVideoId>::<uuid>`, so
     * the same regex that handles the live tag works here.
     */
    private fun extractYouTubeIdFromCapturedKey(key: String): String? {
        val parts = key.split("|")
        if (parts.size < 4) return null
        val tag = parts[3]
        return com.glasshole.phone.util.YouTubeThumbnail.extractVideoIdFromTag(tag)
    }

    /**
     * Re-keys the captured JSON and, if no `picture` is stored but the
     * notification carries a YouTube URL, fetches the thumbnail
     * (sync — we're already on a worker thread) and injects it. Falls
     * back to the original payload on any failure.
     */
    private fun augmentForReplay(originalJson: String): String {
        return try {
            val obj = JSONObject(originalJson)
            val originalKey = obj.optString("key", "")
            obj.put("key", "replay-${System.currentTimeMillis()}-$originalKey")

            val hasPicture = obj.optString("picture", "").isNotEmpty()
            if (!hasPicture) {
                // Newer captures stash the discovered video id under
                // "video_id"; older ones we re-derive on the fly
                // from the captured key (which contains the original
                // notification tag — for YouTube videos that's
                // "<videoId>::<uuid>").
                val storedId = obj.optString("video_id", "").ifEmpty { null }
                val videoId = storedId
                    ?: extractYouTubeIdFromCapturedKey(obj.optString("key", ""))
                    ?: com.glasshole.phone.util.YouTubeThumbnail.extractVideoId(
                        obj.optString("title", "") + "\n" + obj.optString("text", "")
                    )
                if (videoId != null) {
                    val pic = com.glasshole.phone.util.YouTubeThumbnail
                        .fetchAndEncodePicture(this, videoId)
                    if (pic != null) {
                        obj.put("picture", pic)
                        android.util.Log.i(
                            "DebugReplay",
                            "Injected yt thumb for replay (videoId=$videoId)"
                        )
                    } else {
                        android.util.Log.w(
                            "DebugReplay",
                            "yt thumb fetch returned null for $videoId"
                        )
                    }
                } else {
                    android.util.Log.d(
                        "DebugReplay",
                        "No video_id stored; can't enrich replay (capture predates fix?)"
                    )
                }
            }
            obj.toString()
        } catch (_: Exception) {
            originalJson
        }
    }
}
