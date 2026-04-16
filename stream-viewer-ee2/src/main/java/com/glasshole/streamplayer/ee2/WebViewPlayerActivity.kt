package com.glasshole.streamplayer.ee2

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Last-resort player: opens an arbitrary URL in a fullscreen WebView so sites
 * we don't natively extract (TikTok, Facebook, Twitter, Dailymotion embeds,
 * anything else) at least get a chance to play. Not every site cooperates —
 * many render mobile layouts with cookie banners, some block embedding
 * outright — but it's better than a "Unsupported URL" dead end.
 *
 * Controls:
 *   - BACK / swipe down → finish
 *   - TAP (DPAD_CENTER) → no-op here (the page decides)
 *
 * Fullscreen HTML5 video is supported via WebChromeClient.onShowCustomView.
 */
class WebViewPlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "web_url"
        private const val TAG = "WebViewPlayer"
    }

    private lateinit var container: FrameLayout
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(container)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "No URL provided")
            finish()
            return
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            webChromeClient = GlassChromeClient()
            setBackgroundColor(Color.BLACK)
            loadUrl(url)
        }
        container.addView(webView)
    }

    private inner class GlassChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            webView?.visibility = View.GONE
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            container.removeView(v)
            customView = null
            webView?.visibility = View.VISIBLE
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (customView != null) {
                    (webView?.webChromeClient as? GlassChromeClient)?.onHideCustomView()
                } else if (webView?.canGoBack() == true) {
                    webView?.goBack()
                } else {
                    finish()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        (webView?.webChromeClient as? GlassChromeClient)?.onHideCustomView()
        webView?.let {
            it.loadUrl("about:blank")
            it.stopLoading()
            container.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }
}

/**
 * Convenience helper used by both MainActivity (up-front WEBVIEW_FALLBACK
 * routing) and PlayerActivity (resolver-failure fallback). Call and finish.
 */
fun Activity.launchWebViewPlayer(url: String) {
    startActivity(
        Intent(this, WebViewPlayerActivity::class.java).apply {
            putExtra(WebViewPlayerActivity.EXTRA_URL, url)
        }
    )
}
