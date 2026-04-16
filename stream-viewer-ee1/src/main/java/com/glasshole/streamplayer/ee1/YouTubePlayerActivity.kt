package com.glasshole.streamplayer.ee1

import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.streamplayer.ee1.databinding.ActivityPlayerBinding
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

class YouTubePlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var extractTask: AsyncTask<*, *, *>? = null

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        private const val TAG = "GlassYouTubePlayer"
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

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        if (videoId == null) {
            Toast.makeText(this, "No video ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.loadingText.text = "Extracting YouTube stream..."
        binding.loadingText.visibility = View.VISIBLE

        val videoUrl = "https://www.youtube.com/watch?v=$videoId"
        extractTask = ExtractStreamTask(videoUrl).execute()
    }

    @Suppress("DEPRECATION")
    private inner class ExtractStreamTask(private val videoUrl: String) :
        AsyncTask<Void, Void, StreamInfo?>() {

        override fun doInBackground(vararg params: Void?): StreamInfo? {
            return try {
                NewPipe.init(NewPipeDownloader.getInstance())
                StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting stream", e)
                null
            }
        }

        override fun onPostExecute(streamInfo: StreamInfo?) {
            if (streamInfo == null) {
                Toast.makeText(this@YouTubePlayerActivity, "Failed to load YouTube stream", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            try {
                // For live streams, use HLS
                if (streamInfo.streamType == StreamType.LIVE_STREAM) {
                    val hlsUrl = streamInfo.hlsUrl
                    if (!hlsUrl.isNullOrEmpty()) {
                        Log.d(TAG, "Playing live HLS stream")
                        initHlsPlayer(hlsUrl)
                        return
                    }
                }

                // For regular videos, get direct stream URL
                val videoStreams = streamInfo.videoStreams
                if (videoStreams != null && videoStreams.isNotEmpty()) {
                    val streamUrl = videoStreams[0].content
                    Log.d(TAG, "Playing video stream: ${videoStreams[0].resolution}")
                    initDirectPlayer(streamUrl)
                } else {
                    Toast.makeText(this@YouTubePlayerActivity, "No playable streams found", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up playback", e)
                Toast.makeText(this@YouTubePlayerActivity, "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initDirectPlayer(streamUrl: String) {
        binding.loadingText.visibility = View.GONE

        val mediaItem = MediaItem.Builder().setUri(Uri.parse(streamUrl)).build()

        player = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this
            setMediaItem(mediaItem)
            playWhenReady = true
            addListener(playerListener)
            prepare()
        }
    }

    private fun initHlsPlayer(hlsUrl: String) {
        binding.loadingText.visibility = View.GONE

        val dataSourceFactory = Tls12DataSource.Factory()
        val hlsSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(hlsUrl)))

        player = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this
            setMediaSource(hlsSource)
            playWhenReady = true
            addListener(playerListener)
            prepare()
        }
    }

    private val playerListener = object : Player.Listener {
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
                    Toast.makeText(this@YouTubePlayerActivity, "Video ended", Toast.LENGTH_SHORT).show()
                    finish()
                }
                Player.STATE_IDLE -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error", error)
            Toast.makeText(this@YouTubePlayerActivity, "Playback error: ${error.message}", Toast.LENGTH_LONG).show()
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
        extractTask?.cancel(true)
        player?.release()
        player = null
    }
}
