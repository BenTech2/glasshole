package com.glasshole.plugin.media.glass

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * On-glass media / now-playing view. Shows whatever the phone's active
 * MediaSession (Spotify, YouTube Music, Pocket Casts, etc.) exposes —
 * album art, title, artist, album, source app, play state.
 *
 * Gestures drive phone-side MediaController.transportControls:
 *   tap            → play/pause toggle
 *   swipe right    → next track
 *   swipe left     → previous track
 *   swipe down     → close activity
 *
 * The audio itself never leaves the phone. This is a remote control /
 * glance plugin, not a player.
 */
class MediaActivity : Activity() {

    companion object {
        private const val TAG = "MediaActivity"
    }

    private lateinit var mediaContent: LinearLayout
    private lateinit var albumArt: ImageView
    private lateinit var stateIcon: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var appText: TextView
    private lateinit var waitingText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var durationText: TextView
    private lateinit var progressBar: ProgressBar

    // Local interpolation of playback position. We cache whatever the phone
    // last told us (positionMs at the moment it was received, elapsedAtRecv)
    // and tick locally off elapsedRealtime so the progress bar moves
    // smoothly between phone updates.
    private var cachedPositionMs: Long = 0L
    private var cachedDurationMs: Long = 0L
    private var cachedAtRealtime: Long = 0L
    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshProgress()
            ticker.postDelayed(this, 500L)
        }
    }

    private var currentlyPlaying: Boolean = false
    // Dedup key so we don't wipe the album art on follow-up NOW_PLAYING
    // updates for the same track — the phone's art cache intentionally
    // skips re-sending the bitmap when title+album are unchanged.
    private var lastTrackKey: String = ""

    // EE2 touchpad's swipe direction feels reversed relative to the natural
    // "swipe forward to advance" instinct the other variants (EE1/XE) use.
    // Flip the horizontal swipe meaning only for EE2.
    private val reverseHorizontalSwipe: Boolean = android.os.Build.PRODUCT == "glass_v3"

    // Gesture tracking (same pattern as other plugins).
    private var downX = 0f
    private var downY = 0f

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applyNowPlaying(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_media)
        mediaContent = findViewById(R.id.mediaContent)
        albumArt = findViewById(R.id.albumArt)
        stateIcon = findViewById(R.id.stateIcon)
        titleText = findViewById(R.id.titleText)
        artistText = findViewById(R.id.artistText)
        albumText = findViewById(R.id.albumText)
        appText = findViewById(R.id.appText)
        waitingText = findViewById(R.id.waitingText)
        elapsedText = findViewById(R.id.elapsedText)
        durationText = findViewById(R.id.durationText)
        progressBar = findViewById(R.id.progressBar)

        showWaiting()

        val filter = IntentFilter(MediaGlassPluginService.ACTION_NOW_PLAYING)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // Ask the phone for the current state in case we launched while
        // something was already playing.
        sendToPhone("REFRESH", "")
    }

    private fun applyNowPlaying(intent: Intent) {
        val hasSession = intent.getBooleanExtra(MediaGlassPluginService.EXTRA_HAS_SESSION, false)
        val artB64Len = intent.getStringExtra(MediaGlassPluginService.EXTRA_ART_B64).orEmpty().length
        Log.i(TAG, "applyNowPlaying hasSession=$hasSession artLen=$artB64Len")
        if (!hasSession) {
            showWaiting()
            return
        }

        val title = intent.getStringExtra(MediaGlassPluginService.EXTRA_TITLE).orEmpty()
        val artist = intent.getStringExtra(MediaGlassPluginService.EXTRA_ARTIST).orEmpty()
        val album = intent.getStringExtra(MediaGlassPluginService.EXTRA_ALBUM).orEmpty()
        val app = intent.getStringExtra(MediaGlassPluginService.EXTRA_APP_NAME).orEmpty()
        currentlyPlaying = intent.getBooleanExtra(MediaGlassPluginService.EXTRA_PLAYING, false)
        val artB64 = intent.getStringExtra(MediaGlassPluginService.EXTRA_ART_B64).orEmpty()

        titleText.text = if (title.isNotEmpty()) title else "(unknown)"
        artistText.text = artist
        albumText.text = album
        appText.text = app
        stateIcon.text = if (currentlyPlaying) "▶" else "⏸"

        cachedPositionMs = intent.getLongExtra(MediaGlassPluginService.EXTRA_POSITION_MS, 0L)
        cachedDurationMs = intent.getLongExtra(MediaGlassPluginService.EXTRA_DURATION_MS, 0L)
        cachedAtRealtime = SystemClock.elapsedRealtime()
        refreshProgress()

        val incomingKey = "$title|$album|$app"
        val trackChanged = incomingKey != lastTrackKey
        lastTrackKey = incomingKey

        if (artB64.isNotEmpty()) {
            try {
                // JSON roundtrip can leave literal backslashes from \/ escapes
                // when the on-device JSONObject doesn't fully decode them.
                // Base64 never contains backslashes legitimately — strip defensively.
                val clean = artB64.replace("\\", "")
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    albumArt.setImageBitmap(bmp)
                } else {
                    Log.w(TAG, "Art bitmap decode null (bytes=${bytes.size}, b64len=${clean.length})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Art decode failed: ${e.message}")
            }
        } else if (trackChanged) {
            // New track, no art available — clear so we don't keep showing
            // the old song's cover. Same-track follow-ups (state / position /
            // dedup'd updates) leave the existing art alone.
            albumArt.setImageDrawable(null)
        }

        mediaContent.visibility = View.VISIBLE
        waitingText.visibility = View.GONE
    }

    private fun showWaiting() {
        mediaContent.visibility = View.GONE
        waitingText.visibility = View.VISIBLE
    }

    private fun refreshProgress() {
        val duration = cachedDurationMs
        val base = cachedPositionMs
        val since = if (currentlyPlaying) SystemClock.elapsedRealtime() - cachedAtRealtime else 0L
        val current = (base + since).coerceIn(0L, if (duration > 0) duration else base + since)

        if (duration <= 0) {
            // Live stream or unknown duration — hide progress extras.
            progressBar.progress = 0
            elapsedText.text = formatTime(current)
            durationText.text = "--:--"
            return
        }
        progressBar.progress = ((current.toDouble() / duration) * 1000).toInt().coerceIn(0, 1000)
        elapsedText.text = formatTime(current)
        durationText.text = formatTime(duration)
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSec = ms / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onStart() {
        super.onStart()
        ticker.post(tickRunnable)
    }

    override fun onStop() {
        super.onStop()
        ticker.removeCallbacks(tickRunnable)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)
                // Swipe down → close (wins over horizontal if strong enough)
                if (dy > 120 && absDy > absDx * 1.3f) { finish(); return true }
                // Horizontal swipe → next / prev
                if (absDx > 80 && absDx > absDy) {
                    val rightwardIsNext = !reverseHorizontalSwipe
                    val goNext = if (dx > 0) rightwardIsNext else !rightwardIsNext
                    sendToPhone(if (goNext) "NEXT" else "PREV", "")
                    return true
                }
                // Tap → toggle play/pause
                if (absDx < 25 && absDy < 25) {
                    sendToPhone("TOGGLE", "")
                    return true
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun sendToPhone(type: String, payload: String) {
        try {
            val intent = Intent("com.glasshole.glass.MESSAGE_TO_PHONE").apply {
                putExtra("plugin_id", "media")
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
