package com.glasshole.streamplayer.xe

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.glasshole.streamplayer.xe.databinding.ActivityPlayerBinding
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    // Queue state (single-track for standalone URLs, multi-track for
    // YouTube / YouTube Music playlists).
    private var queue: List<QueueItem> = emptyList()
    private var cursor: Int = 0

    // Touchpad gesture tracking — XE emits MotionEvents for swipes.
    private var downX = 0f
    private var downY = 0f

    private val trackInfoHideRunnable = Runnable {
        binding.trackInfo.visibility = View.GONE
    }

    companion object {
        const val EXTRA_URL = "stream_url"
        private const val TAG = "GlassStreamPlayer"
        private val artClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            Toast.makeText(this, "No URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val name = StreamResolver.displayName(url)
        binding.loadingText.text = "Connecting to $name..."
        binding.loadingText.visibility = View.VISIBLE

        if (StreamResolver.identify(url) == StreamPlatform.YOUTUBE_PLAYLIST) {
            loadPlaylist(url)
        } else {
            queue = listOf(QueueItem(sourceUrl = url))
            cursor = 0
            playCurrent()
        }
    }

    private fun loadPlaylist(url: String) {
        lifecycleScope.launch {
            val result = StreamResolver.resolvePlaylist(url)
            result.onSuccess { items ->
                queue = items
                cursor = 0
                Log.i(TAG, "Playlist loaded: ${items.size} tracks")
                playCurrent()
            }.onFailure { error ->
                Log.w(TAG, "Playlist resolve failed: ${error.message}")
                Toast.makeText(
                    this@PlayerActivity,
                    "Couldn't load playlist: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun playCurrent() {
        val item = queue.getOrNull(cursor)
        if (item == null) {
            Toast.makeText(this, "Playlist finished", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        showTrackInfo(item)
        binding.loadingText.text = "Loading..."
        binding.loadingText.visibility = View.VISIBLE

        player?.release()
        player = null

        lifecycleScope.launch {
            val result = StreamResolver.resolve(item.sourceUrl)
            result.onSuccess { resolved ->
                Log.d(TAG, "Resolved (hls=${resolved.isHls}, audio=${resolved.isAudioOnly}): ${resolved.url}")
                val enriched = resolved.copy(
                    title = resolved.title ?: item.title,
                    artworkUrl = resolved.artworkUrl ?: item.artworkUrl
                )
                showTrackInfo(item, total = queue.size, resolved = enriched)
                loadAlbumArt(enriched.artworkUrl, enriched.isAudioOnly)
                initPlayer(enriched)
            }.onFailure { error ->
                Log.w(TAG, "Track resolve failed, falling back to WebView: ${error.message}")
                Toast.makeText(
                    this@PlayerActivity,
                    "Opening in browser: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
                startActivity(
                    Intent(this@PlayerActivity, WebViewPlayerActivity::class.java).apply {
                        putExtra(WebViewPlayerActivity.EXTRA_URL, item.sourceUrl)
                    }
                )
                finish()
            }
        }
    }

    private fun initPlayer(resolved: ResolvedStream) {
        binding.loadingText.visibility = View.GONE

        val dataSourceFactory = Tls12DataSource.Factory()

        val mediaItem = MediaItem.fromUri(Uri.parse(resolved.url))
        val source = if (resolved.isHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        player = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this

            setMediaSource(source)
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            binding.loadingText.text = "Buffering..."
                            binding.loadingText.visibility = View.VISIBLE
                        }
                        Player.STATE_READY -> {
                            binding.loadingText.visibility = View.GONE
                        }
                        Player.STATE_ENDED -> {
                            if (cursor + 1 < queue.size) {
                                cursor++
                                playCurrent()
                            } else {
                                Toast.makeText(
                                    this@PlayerActivity,
                                    if (queue.size > 1) "Playlist finished" else "Stream ended",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                        }
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Playback error", error)
                    Toast.makeText(
                        this@PlayerActivity,
                        "Playback error: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    if (cursor + 1 < queue.size) {
                        cursor++
                        playCurrent()
                    }
                }
            })

            prepare()
        }
    }

    private fun showTrackInfo(item: QueueItem, total: Int = queue.size, resolved: ResolvedStream? = null) {
        val title = resolved?.title ?: item.title
        val line = buildString {
            if (total > 1) append("Track ${cursor + 1} of $total")
            if (!title.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(title)
            }
        }
        if (line.isBlank()) {
            binding.trackInfo.visibility = View.GONE
            return
        }
        binding.trackInfo.text = line
        binding.trackInfo.visibility = View.VISIBLE
        binding.trackInfo.removeCallbacks(trackInfoHideRunnable)
        binding.trackInfo.postDelayed(trackInfoHideRunnable, 3_500L)
    }

    /**
     * Fetch album art on IO and apply to the background ImageView on the
     * main thread. Only shown for audio-only tracks (YouTube Music) so the
     * ExoPlayer surface doesn't sit black.
     */
    private fun loadAlbumArt(url: String?, isAudioOnly: Boolean) {
        if (!isAudioOnly || url.isNullOrBlank()) {
            binding.albumArt.visibility = View.GONE
            binding.albumArtDim.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val response = artClient.newCall(Request.Builder().url(url).build()).execute()
                    if (!response.isSuccessful) return@withContext null
                    response.body()?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Album art fetch failed: ${e.message}")
                    null
                }
            }
            if (bitmap != null) {
                binding.albumArt.setImageBitmap(bitmap)
                binding.albumArt.visibility = View.VISIBLE
                binding.albumArtDim.visibility = View.VISIBLE
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                player?.let {
                    it.playWhenReady = !it.playWhenReady
                    val state = if (it.playWhenReady) "Playing" else "Paused"
                    Toast.makeText(this, state, Toast.LENGTH_SHORT).show()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB -> {
                if (queue.size > 1) { skipNext(); true } else false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (queue.size > 1) { skipPrev(); true } else false
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // XE (and EE1) touchpad uses SOURCE_TOUCHPAD via onGenericMotionEvent;
    // EE2-style screen touches come through onTouchEvent. Both forward to
    // handleGesture so playlist next/prev works on all variants.
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
                if (absDy > 80 && absDy > absDx) { finish(); return true }
                if (queue.size > 1 && absDx > 80 && absDx > absDy) {
                    if (dx > 0) skipNext() else skipPrev()
                    return true
                }
                // Tap (small movement) → toggle play/pause. We do this
                // here rather than relying on KEYCODE_DPAD_CENTER because
                // consuming ACTION_DOWN in onGenericMotionEvent stops the
                // GDK gesture detector from emitting that key on XE.
                if (absDx < 25 && absDy < 25) {
                    player?.let {
                        it.playWhenReady = !it.playWhenReady
                        val state = if (it.playWhenReady) "Playing" else "Paused"
                        Toast.makeText(this, state, Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun skipNext() {
        if (cursor + 1 < queue.size) {
            cursor++
            playCurrent()
        }
    }

    private fun skipPrev() {
        if (cursor > 0) {
            cursor--
            playCurrent()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.trackInfo.removeCallbacks(trackInfoHideRunnable)
        player?.release()
        player = null
    }
}
