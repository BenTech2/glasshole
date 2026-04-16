package com.glasshole.streamplayer.ee1

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.glasshole.streamplayer.ee1.databinding.ActivityPlayerBinding
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
            KeyEvent.KEYCODE_BACK -> {
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
