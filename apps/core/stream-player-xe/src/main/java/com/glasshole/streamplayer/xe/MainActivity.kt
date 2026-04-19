package com.glasshole.streamplayer.xe

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GlassStream"
        private const val KEY_SCANNER_LAUNCHED = "scanner_launched"
        const val EXTRA_URL = "com.glasshole.streamplayer.EXTRA_URL"
        const val ACTION_PLAY_URL = "com.glasshole.streamplayer.ACTION_PLAY_URL"
    }

    private lateinit var statusText: TextView
    private var scannerLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_explorer)
        statusText = findViewById(R.id.statusText)

        scannerLaunched = savedInstanceState?.getBoolean(KEY_SCANNER_LAUNCHED, false) ?: false

        val externalUrl = intent?.getStringExtra(EXTRA_URL)
        if (!externalUrl.isNullOrBlank()) {
            handleScannedUrl(externalUrl)
            return
        }

        if (!scannerLaunched) {
            launchScanner()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val externalUrl = intent?.getStringExtra(EXTRA_URL)
        if (!externalUrl.isNullOrBlank()) {
            handleScannedUrl(externalUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SCANNER_LAUNCHED, scannerLaunched)
    }

    private fun launchScanner() {
        scannerLaunched = true
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Scan a stream QR code")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(true)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            scannerLaunched = false
            if (result.contents != null) {
                handleScannedUrl(result.contents)
            } else {
                statusText.text = "Tap to scan again"
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleScannedUrl(url: String) {
        Log.d(TAG, "Scanned URL: $url")

        val platform = StreamResolver.identify(url)
        val name = StreamResolver.displayName(url)
        statusText.text = "$name found! Loading..."

        val intent = when (platform) {
            StreamPlatform.WEBVIEW_FALLBACK -> Intent(this, WebViewPlayerActivity::class.java).apply {
                putExtra(WebViewPlayerActivity.EXTRA_URL, url)
            }
            else -> Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, url)
            }
        }
        startActivity(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                launchScanner()
                true
            }
            KeyEvent.KEYCODE_CAMERA -> {
                launchScanner()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
