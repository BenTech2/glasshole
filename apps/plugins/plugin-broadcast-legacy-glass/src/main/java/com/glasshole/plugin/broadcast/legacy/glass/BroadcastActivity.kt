package com.glasshole.plugin.broadcast.legacy.glass

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpCamera1
import com.pedro.rtplibrary.view.OpenGlView

/**
 * RTMP broadcaster using Camera1 for EE1 / XE. Functionally identical
 * to the Camera2 variant — same config protocol, same display modes
 * (viewfinder / preview_off / screen_off / chat), same gesture +
 * chat-overlay handling. Separate module because the encoder library's
 * Camera1 and Camera2 classes don't share a base type.
 */
class BroadcastActivity : Activity() {

    companion object {
        private const val TAG = "BroadcastLegacy"
        private const val REQUEST_PERMS = 11
        private const val SCREEN_OFF_DELAY_MS = 3_000L
        private const val FALLBACK_MAX_CHAT_MESSAGES = 200
        private const val ABSOLUTE_MAX_CHAT_MESSAGES = 1000
    }

    private lateinit var surface: OpenGlView
    private lateinit var statusText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var previewCover: View
    private lateinit var liveBadge: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout

    private var camera: RtmpCamera1? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    private var pendingConfig: BroadcastPrefs.Config? = null
    private var surfaceReady = false
    private var permissionsReady = false
    private var streamingStarted = false
    private var maxChatMessages = FALLBACK_MAX_CHAT_MESSAGES

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private val horizScrollFactor = 2.5f
    private var stickToBottom = true

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(BroadcastGlassPluginService.EXTRA_CONFIG_JSON)
                ?: return
            BroadcastPrefs.save(this@BroadcastActivity, json)
            pendingConfig = BroadcastPrefs.load(this@BroadcastActivity)
            tryStart()
        }
    }

    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(BroadcastGlassPluginService.EXTRA_KIND)) {
                "chat" -> {
                    val cap = intent.getIntExtra(BroadcastGlassPluginService.EXTRA_MAX_MSGS, 0)
                    if (cap in 10..ABSOLUTE_MAX_CHAT_MESSAGES) maxChatMessages = cap
                    appendChat(
                        user = intent.getStringExtra(BroadcastGlassPluginService.EXTRA_USER) ?: "",
                        text = intent.getStringExtra(BroadcastGlassPluginService.EXTRA_TEXT) ?: "",
                        color = intent.getStringExtra(BroadcastGlassPluginService.EXTRA_COLOR) ?: "",
                        sizeSp = intent.getIntExtra(BroadcastGlassPluginService.EXTRA_SIZE, 0)
                    )
                }
                "status" -> appendChatStatus(
                    intent.getStringExtra(BroadcastGlassPluginService.EXTRA_TEXT) ?: ""
                )
            }
        }
    }

    private val connectionListener = object : ConnectCheckerRtmp {
        override fun onConnectionStartedRtmp(rtmpUrl: String) {
            main.post { statusText.text = "Connecting…" }
        }

        override fun onConnectionSuccessRtmp() {
            main.post {
                statusText.text = "LIVE"
                liveBadge.visibility = View.VISIBLE
            }
        }

        override fun onConnectionFailedRtmp(reason: String) {
            Log.w(TAG, "RTMP failed: $reason")
            main.post {
                statusText.text = "Connection failed — $reason"
                liveBadge.visibility = View.GONE
                bitrateText.text = ""
                camera?.stopStream()
                streamingStarted = false
            }
        }

        override fun onNewBitrateRtmp(bitrate: Long) {
            val kbps = bitrate / 1024
            main.post { bitrateText.text = "${kbps} kbps" }
        }

        override fun onDisconnectRtmp() {
            main.post {
                statusText.text = "Disconnected"
                liveBadge.visibility = View.GONE
                bitrateText.text = ""
            }
        }

        override fun onAuthErrorRtmp() {
            main.post { statusText.text = "Auth error" }
        }

        override fun onAuthSuccessRtmp() {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_broadcast)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = findViewById(R.id.surface)
        statusText = findViewById(R.id.statusText)
        bitrateText = findViewById(R.id.bitrateText)
        previewCover = findViewById(R.id.previewCover)
        liveBadge = findViewById(R.id.liveBadge)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)

        camera = RtmpCamera1(surface, connectionListener)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                surfaceReady = true
                tryStart()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfaceReady = false
                stopStreaming("Surface destroyed")
            }
        })

        val cfgFilter = IntentFilter(BroadcastGlassPluginService.ACTION_CONFIG)
        val chatFilter = IntentFilter(BroadcastGlassPluginService.ACTION_CHAT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(configReceiver, cfgFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(chatReceiver, chatFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(configReceiver, cfgFilter)
            registerReceiver(chatReceiver, chatFilter)
        }

        pendingConfig = BroadcastPrefs.load(this)

        if (!hasPermissions()) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    REQUEST_PERMS
                )
            } else {
                // API 19 has no runtime permissions — manifest grants at install.
                permissionsReady = true
            }
        } else {
            permissionsReady = true
        }

        sendToPhone("START", "")
        statusText.text = if (pendingConfig == null) {
            "Requesting config from phone…"
        } else {
            "Starting stream…"
        }

        acquireWakeLock()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_PERMS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                permissionsReady = true
                tryStart()
            } else {
                statusText.text = "Camera / mic permission denied"
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return cam && mic
    }

    private fun tryStart() {
        val cfg = pendingConfig ?: return
        if (!surfaceReady || !permissionsReady) return
        if (streamingStarted) return

        val cam = camera ?: return
        try {
            if (!cfg.audio) cam.disableAudio()
            val audioOk = cam.prepareAudio()
            val videoOk = cam.prepareVideo(
                cfg.width, cfg.height, cfg.fps, cfg.bitrateKbps * 1024, 2, 0
            )
            if (!videoOk || !audioOk) {
                statusText.text = "Encoder prepare failed"
                return
            }
            applyDisplayMode(cfg.displayMode)
            cam.startStream(cfg.url)
            streamingStarted = true
            statusText.text = "Connecting to RTMP…"
        } catch (e: Exception) {
            Log.e(TAG, "startStream error", e)
            statusText.text = "Start failed: ${e.message}"
        }
    }

    private fun applyDisplayMode(mode: String) {
        when (mode) {
            "preview_off" -> {
                previewCover.visibility = View.VISIBLE
                chatScroll.visibility = View.GONE
                setScreenBrightness(-1f)
            }
            "screen_off" -> {
                previewCover.visibility = View.VISIBLE
                chatScroll.visibility = View.GONE
                main.postDelayed({ setScreenBrightness(0.01f) }, SCREEN_OFF_DELAY_MS)
            }
            "chat" -> {
                previewCover.visibility = View.VISIBLE
                chatScroll.visibility = View.VISIBLE
                setScreenBrightness(-1f)
            }
            else -> {
                previewCover.visibility = View.GONE
                chatScroll.visibility = View.GONE
                setScreenBrightness(-1f)
            }
        }
    }

    private fun setScreenBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleGesture(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event ?: return super.onGenericMotionEvent(event)
        if (handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        val chatMode = chatScroll.visibility == View.VISIBLE

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                setScreenBrightness(-1f)
                if (chatMode) return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (chatMode) {
                    val dx = event.x - lastX
                    if (kotlin.math.abs(dx) > 1f) {
                        chatScroll.scrollBy(0, (dx * horizScrollFactor).toInt())
                        lastX = event.x
                        stickToBottom = isChatAtBottom()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                val absDx = kotlin.math.abs(totalDx)
                val absDy = kotlin.math.abs(totalDy)

                if (totalDy > 120 && absDy > absDx * 1.3f) {
                    finish()
                    return true
                }
                if (chatMode) {
                    if (absDx < 30 && absDy < 30) {
                        stickToBottom = true
                        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                        return true
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> if (chatMode) return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        sendToPhone("STOP", "")
        try { unregisterReceiver(configReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(chatReceiver) } catch (_: Exception) {}
        stopStreaming("Activity destroyed")
        releaseWakeLock()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun stopStreaming(reason: String) {
        try {
            camera?.let {
                if (it.isStreaming) it.stopStream()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopStream error: ${e.message}")
        }
        streamingStarted = false
        Log.d(TAG, "Stopped: $reason")
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GlassHole:Broadcast"
            )
            wl.setReferenceCounted(false)
            wl.acquire(30 * 60 * 1000L)
            wakeLock = wl
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun appendChat(user: String, text: String, color: String, sizeSp: Int) {
        main.post {
            val userColor = parseColor(color) ?: 0xFF4FC3F7.toInt()
            val spanned = SpannableStringBuilder()
            if (user.isNotEmpty()) {
                val start = spanned.length
                spanned.append(user)
                spanned.setSpan(
                    ForegroundColorSpan(userColor),
                    start, spanned.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spanned.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start, spanned.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spanned.append(": ")
            }
            spanned.append(text)

            val row = TextView(this).apply {
                setText(spanned)
                setTextColor(Color.WHITE)
                textSize = if (sizeSp in 8..40) sizeSp.toFloat() else 14f
                setPadding(0, 4, 0, 4)
            }
            chatContainer.addView(row)
            while (chatContainer.childCount > maxChatMessages) {
                chatContainer.removeViewAt(0)
            }
            if (stickToBottom) {
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun appendChatStatus(text: String) {
        if (text.isEmpty()) return
        main.post {
            val row = TextView(this).apply {
                setText(text)
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 12f
                setPadding(0, 4, 0, 4)
            }
            chatContainer.addView(row)
            while (chatContainer.childCount > maxChatMessages) {
                chatContainer.removeViewAt(0)
            }
            if (stickToBottom) {
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun isChatAtBottom(): Boolean {
        val child = chatScroll.getChildAt(0) ?: return true
        return chatScroll.scrollY + chatScroll.height >= child.height - 20
    }

    private fun parseColor(c: String): Int? {
        if (c.isBlank()) return null
        return try { Color.parseColor(c) } catch (_: Exception) { null }
    }

    private fun sendToPhone(type: String, payload: String) {
        try {
            val intent = Intent("com.glasshole.glass.MESSAGE_TO_PHONE").apply {
                putExtra("plugin_id", "broadcast")
                putExtra("message_type", type)
                putExtra("payload", payload)
            }
            for (pkg in listOf(
                "com.glasshole.glassee1",
                "com.glasshole.glassxe",
                "com.glasshole.glassee2"
            )) {
                intent.setPackage(pkg)
                sendBroadcast(intent)
            }
        } catch (_: Exception) {}
    }
}
