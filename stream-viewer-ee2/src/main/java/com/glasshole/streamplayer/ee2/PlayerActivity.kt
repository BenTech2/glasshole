package com.glasshole.streamplayer.ee2

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    companion object {
        const val EXTRA_URL = "stream_url"
        private const val TAG = "GlassStreamPlayer"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive fullscreen for Glass
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

        resolveStream(url)
    }

    private fun resolveStream(url: String) {
        lifecycleScope.launch {
            val result = StreamResolver.resolve(url)

            result.onSuccess { resolved ->
                Log.d(TAG, "Resolved stream (hls=${resolved.isHls}): ${resolved.url}")
                initPlayer(resolved)
            }.onFailure { error ->
                Log.w(TAG, "Native resolve failed, falling back to WebView: ${error.message}")
                Toast.makeText(
                    this@PlayerActivity,
                    "Opening in browser: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
                startActivity(
                    Intent(this@PlayerActivity, WebViewPlayerActivity::class.java).apply {
                        putExtra(WebViewPlayerActivity.EXTRA_URL, url)
                    }
                )
                finish()
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initPlayer(resolved: ResolvedStream) {
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
                            Toast.makeText(this@PlayerActivity, "Stream ended", Toast.LENGTH_SHORT).show()
                            finish()
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
                }
            })

            prepare()
        }
    }

    /**
     * Glass EE2 controls:
     * - Swipe back (mapped to BACK) = exit player, return to scanner
     * - Tap = toggle play/pause
     * - Camera button = no-op in player
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                // Tap on Glass touchpad = toggle play/pause
                player?.let {
                    it.playWhenReady = !it.playWhenReady
                    val state = if (it.playWhenReady) "Playing" else "Paused"
                    Toast.makeText(this, state, Toast.LENGTH_SHORT).show()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Swipe back = exit to scanner
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
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
        player?.release()
        player = null
    }
}
