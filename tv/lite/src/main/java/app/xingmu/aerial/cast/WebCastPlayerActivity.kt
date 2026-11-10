package app.xingmu.aerial.cast

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Plays a stream through the backend's own web player (DPlayer + hls.js) in a fullscreen WebView.
 *
 * Why this exists: Android 4.4's MediaPlayer cannot play HLS at all, and its WebView can run hls.js,
 * so the only way an old TV plays an m3u8 the phone pushed is to let the backend page do it.
 */
class WebCastPlayerActivity : Activity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isEmpty()) {
            finish()
            return
        }
        CastPlayer.currentUri = intent.getStringExtra(EXTRA_STREAM).orEmpty().ifEmpty { url }
        CastPlayer.state = CastPlayer.State.PLAYING

        WebView.setWebContentsDebuggingEnabled(true)
        val view = WebView(this)
        web = view
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            // Desktop-ish UA: the backend page is written for browsers, not for a TV WebView.
            userAgentString = "Mozilla/5.0 (Linux; Android; TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/34.0.1847.114 Safari/537.36"
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
        }
        // Hardware layer made the whole page black on the 4.4 box; the WebView already inherits
        // hardware acceleration from the activity, which is what <video> needs.
        view.setBackgroundColor(0xFF000000.toInt())
        view.webViewClient = WebViewClient()
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(v: View?, callback: CustomViewCallback?) {
                // Some players go fullscreen through the element API; keep the WebView visible.
            }
        }
        setContentView(view)
        view.loadUrl(url)
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        runCatching { web?.evaluateJavascript("document.querySelector('video')?.pause()", null) }
        CastPlayer.state = CastPlayer.State.PAUSED
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.onPause()
        web?.destroy()
        web = null
        CastPlayer.state = CastPlayer.State.STOPPED
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_STREAM = "stream"
    }
}
