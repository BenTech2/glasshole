package com.glasshole.streamplayer.ee2

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.streamplayer.ee2.databinding.ActivityMainBinding

/**
 * Headless URL receiver. The glasshole share-to-glass pipeline launches this
 * activity with EXTRA_URL set; we immediately hand off to PlayerActivity.
 * No QR scanner anymore — launching without a URL just shows a waiting screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val TAG = "GlassStream"
        const val EXTRA_URL = "com.glasshole.streamplayer.EXTRA_URL"
        const val ACTION_PLAY_URL = "com.glasshole.streamplayer.ACTION_PLAY_URL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val externalUrl = intent?.getStringExtra(EXTRA_URL)
        if (!externalUrl.isNullOrBlank()) {
            launchPlayer(externalUrl)
        } else {
            binding.statusText.text = "Share a video URL from your phone"
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val externalUrl = intent?.getStringExtra(EXTRA_URL)
        if (!externalUrl.isNullOrBlank()) {
            launchPlayer(externalUrl)
        }
    }

    private fun launchPlayer(url: String) {
        Log.d(TAG, "Received URL: $url")
        val platform = StreamResolver.identify(url)
        val name = StreamResolver.displayName(url)
        binding.statusText.text = "$name found! Loading..."

        val target = if (platform == StreamPlatform.WEBVIEW_FALLBACK) {
            Intent(this, WebViewPlayerActivity::class.java).apply {
                putExtra(WebViewPlayerActivity.EXTRA_URL, url)
            }
        } else {
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, url)
            }
        }
        // Every PLAY_URL replaces whatever's playing — wipe the stream
        // player's task first so old PlayerActivity / WebViewPlayerActivity
        // instances can't stack up behind the new one.
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(target)
        finish()
    }
}
