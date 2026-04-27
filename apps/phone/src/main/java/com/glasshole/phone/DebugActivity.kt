package com.glasshole.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.bt.ProtocolCodec
import com.glasshole.phone.service.BridgeService
import com.glasshole.phone.service.NotificationForwardingService
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

        sendButton.setOnClickListener { sendVariant(Variant.PLAIN) }
        sendReplyButton.setOnClickListener { sendVariant(Variant.REPLY) }
        sendOpenOnDeviceButton.setOnClickListener { sendVariant(Variant.OPEN_PHONE) }
        sendStreamButton.setOnClickListener { sendVariant(Variant.STREAM) }
        sendMultiButton.setOnClickListener { sendVariant(Variant.MULTI) }
        sendImageButton.setOnClickListener { sendVariant(Variant.IMAGE) }
        resetAdminPromptButton.setOnClickListener { sendResetAdminPrompt() }

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
    }

    private fun sendResetAdminPrompt() {
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            toast("Glass not connected")
            updateStatus()
            return
        }
        val ok = bridge.sendResetHomeAdminPrompt()
        toast(if (ok) "Sent — open GlassHole Home on the glass" else "Send failed")
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
}
