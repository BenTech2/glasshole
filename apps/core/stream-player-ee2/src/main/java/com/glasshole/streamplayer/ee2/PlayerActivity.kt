package com.glasshole.streamplayer.ee2

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.glasshole.streamplayer.ee2.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    // Queue state (single-track for non-playlist URLs, multi-track for
    // YouTube / YouTube Music playlists).
    private var queue: List<QueueItem> = emptyList()
    private var cursor: Int = 0

    // Bumped every time a new track load begins. Async resolve callbacks
    // compare against this before touching the player — otherwise a
    // rapid-skip or STATE_ENDED race could let two resolves both finish
    // and each spawn an ExoPlayer, leaving multiple songs playing at once.
    private var loadGeneration: Long = 0

    // Touchpad gesture tracking (EE2 also gets KEYCODE_DPAD_LEFT / RIGHT for
    // swipes, but fall back to raw motion events if they come that way).
    private var downX = 0f
    private var downY = 0f

    // Control-overlay auto-hide.
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
        val myGen = ++loadGeneration
        showTrackInfo(item)
        binding.loadingText.text = "Loading..."
        binding.loadingText.visibility = View.VISIBLE

        // Release any previous player before starting the next track.
        player?.release()
        player = null

        lifecycleScope.launch {
            val result = StreamResolver.resolve(item.sourceUrl)
            if (myGen != loadGeneration) return@launch
            result.onSuccess { resolved ->
                if (myGen != loadGeneration) return@onSuccess
                Log.d(TAG, "Resolved (hls=${resolved.isHls}, audio=${resolved.isAudioOnly}): ${resolved.url}")
                // Merge metadata — playlist entries know title/art already,
                // standalone resolution may also report them.
                val enriched = resolved.copy(
                    title = resolved.title ?: item.title,
                    artworkUrl = resolved.artworkUrl ?: item.artworkUrl
                )
                showTrackInfo(item, total = queue.size, resolved = enriched)
                loadAlbumArt(enriched.artworkUrl, enriched.isAudioOnly)
                initPlayer(enriched)
            }.onFailure { error ->
                if (myGen != loadGeneration) return@onFailure
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

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initPlayer(resolved: ResolvedStream) {
        // Belt-and-suspenders: if any prior ExoPlayer survived a race,
        // kill it before creating the new one.
        player?.release()
        player = null
        binding.loadingText.visibility = View.GONE

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaItem = MediaItem.fromUri(resolved.url)
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
                    // Auto-advance on failure — one dead track shouldn't
                    // kill the whole playlist.
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
     * Fetch the album art bitmap on IO, then apply to the background
     * ImageView on the main thread. Only shown when we're playing an
     * audio-only track (YouTube Music or podcast-style content) so the
     * PlayerView surface doesn't sit black.
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
                    response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
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

    // EE2 touchpad — swipes come through as both key events (KEYCODE_DPAD_*)
    // and raw motion events; handle both for next/prev.
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

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event ?: return super.onTouchEvent(event)
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
            }
        }
        return super.onTouchEvent(event)
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
