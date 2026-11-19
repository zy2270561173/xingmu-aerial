package app.xingmu.aerial.cast

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.xingmu.aerial.R

/**
 * 抖音 on the TV: the PC web page in a fullscreen WebView, driven only by the remote.
 *
 * The page is a desktop SPA written for a mouse and a modern browser, so everything the viewer
 * needs is injected as HTML/CSS from here — a loading page, a top hint, key feedback, the help
 * layer and the "old WebView" notice. No Android widget is ever laid over the page: the only
 * native views are the WebView and, while HTML5 fullscreen video runs, the view the page hands to
 * [WebChromeClient.onShowCustomView].
 *
 * Android 4.0 keeps working: the WebView is created and configured inside try/catch, a failure
 * shows a Chinese explanation page instead of a crash, and the desktop UA/JS settings are all
 * guarded by API level. 抖音 itself needs a far newer Chromium than 4.4's (Chrome 30), so there
 * the page usually stays empty; the injected notice says so and Back still leaves.
 *
 * Cookies are left alone on purpose: the scanned-in login has to survive leaving the app.
 */
class DouyinActivity : Activity() {

    private lateinit var root: FrameLayout
    private var web: WebView? = null

    /** The page's fullscreen view while an HTML5 video is fullscreen, else null. */
    private var fullscreen: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private var helpShown = false
    private var pageReady = false
    private var chromeMajor = 0
    /** Set when the owner chose "try anyway" on an old WebView, so the notice is not shown twice. */
    private var forceWeb = false
    private var lastScrollAt = 0L
    private var lastSeekAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)
        hideBars()
        openWebView()
    }

    // ---- the WebView ----

    private fun openWebView() {
        val view = try {
            WebView(this)
        } catch (t: Throwable) {
            // 4.x boxes without a usable WebView provider throw here, not later.
            Log.w(TAG, "WebView could not be created; showing the explanation page", t)
            showMissingPage()
            return
        }
        web = view
        if (Build.VERSION.SDK_INT >= 19) runCatching { WebView.setWebContentsDebuggingEnabled(true) }

        // The stock UA, before it is replaced, is where the WebView's Chromium version shows.
        val defaultUa = runCatching {
            if (Build.VERSION.SDK_INT >= 17) WebSettings.getDefaultUserAgent(this) else view.settings.userAgentString
        }.getOrDefault("")
        chromeMajor = CHROME.find(defaultUa)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        Log.i(TAG, "WebView UA: $defaultUa")
        Log.i(TAG, "WebView Chromium major: $chromeMajor")
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            val info: PackageInfo? = WebView.getCurrentWebViewPackage()
            if (info != null) Log.i(TAG, "WebView package: ${info.packageName} ${info.versionName}")
        }

        configure(view)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Log.i(
            TAG,
            "hardware acceleration: view=${view.isHardwareAccelerated} layer=${view.layerType} " +
                "windowFlags=${window.attributes.flags} sdk=${Build.VERSION.SDK_INT}",
        )
        Log.i(TAG, "page controls: none; every overlay is injected HTML/CSS, not an Android widget")
        if (chromeMajor in 1 until CHROME_MIN && !forceWeb) showCompatPage() else showSplashThenLoad()
    }

    @Suppress("DEPRECATION")
    private fun configure(view: WebView) {
        val settings = view.settings
        runCatching {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // The desktop page: 抖音's TV-friendly layout is its PC one, and its login page is a
            // QR code there instead of an app hand-off.
            settings.userAgentString = DESKTOP_UA
            if (Build.VERSION.SDK_INT >= 17) settings.mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= 21) settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }.onFailure { Log.w(TAG, "some WebSettings could not be set", it) }

        if (Build.VERSION.SDK_INT >= 21) {
            // The page gets its own hardware layer (the full app does the same for video). On API
            // 19 this made the WebView draw black on the 4.4 test box (WebCastPlayerActivity hit
            // the same thing), so old systems keep the window's acceleration only.
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } else {
            Log.i(TAG, "hardware layer skipped on API ${Build.VERSION.SDK_INT}: 4.4's WebView went black with one")
        }
        view.setBackgroundColor(Color.BLACK)
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        // Login lives in these cookies: accept them, never clear them.
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= 21) cookies.setAcceptThirdPartyCookies(view, true)
        }.onFailure { Log.w(TAG, "cookie settings failed", it) }

        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.i(TAG, "page started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url.isNullOrEmpty() || url == "about:blank" || url.startsWith("data:")) return
                if (!url.contains("douyin.com")) return
                pageReady = true
                helpShown = false
                inject()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                if (!url.startsWith(COMPAT_SCHEME)) return false
                when (url.removePrefix(COMPAT_SCHEME)) {
                    "load" -> {
                        forceWeb = true
                        Log.i(TAG, "owner chose to load the desktop page on Chromium $chromeMajor")
                        showSplashThenLoad()
                    }
                    "app" -> {
                        Log.i(TAG, "owner chose the installed 抖音 app")
                        if (!launchInstalledDouyin()) {
                            forceWeb = true
                            showSplashThenLoad()
                        }
                    }
                    else -> Log.w(TAG, "unknown compat action: $url")
                }
                return true
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.w(TAG, "page error $errorCode $description $failingUrl")
                if (pageReady) return
                // Pre-23 this fires for subresources too; the main frame is the one whose URL
                // equals the WebView's own.
                val current = view?.url
                if (current != null && failingUrl != null && failingUrl != current) return
                showErrorPage()
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null) return
                if (fullscreen != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val holder = FrameLayout(this@DouyinActivity)
                holder.setBackgroundColor(Color.BLACK)
                holder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                view.isFocusable = true
                fullscreen = holder
                fullCallback = callback
                hideBars()
                Log.i(TAG, "fullscreen video on")
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    private fun showSplashThenLoad() {
        pageReady = false
        val view = web ?: return
        val shown = runCatching {
            view.loadDataWithBaseURL(null, splashHtml(), "text/html", "utf-8", null)
        }.isSuccess
        if (!shown) Log.w(TAG, "the loading page could not be shown")
        handler.postDelayed({
            if (isFinishing) return@postDelayed
            Log.i(TAG, "opening $URL")
            runCatching { view.loadUrl(URL) }.onFailure {
                Log.w(TAG, "could not load $URL", it)
                showErrorPage()
            }
        }, if (shown) 400L else 0L)
    }

    // ---- injected UI ----

    /** Puts the CSS and the overlays into the page; no Android view takes part. */
    private fun inject() {
        val script = BRIDGE_JS
            .replace("@@TEXTS@@", textsJs())
            .replace("@@OVERLAY@@", jsQuote(overlayHtml()))
        callJs(script) { value ->
            if (value != null && value != "null") {
                Log.i(TAG, "overlays injected: CSS, top hint, key feedback, help layer (all HTML/CSS)")
            } else {
                Log.w(TAG, "overlay injection returned $value")
            }
        }
        if (chromeMajor in 1 until CHROME_MIN) {
            val note = getString(R.string.douyin_webview_old, chromeMajor.toString())
            handler.postDelayed({
                callJs("window.__aerial&&window.__aerial.compat(" + jsQuote(note) + ")")
                Log.i(TAG, "old-WebView notice shown (Chromium $chromeMajor)")
            }, 800L)
        }
    }

    /** [onResult] hears the script's completion value; only API 19+ has evaluateJavascript. */
    private fun callJs(script: String, onResult: ((String?) -> Unit)? = null) {
        val view = web ?: return
        try {
            if (Build.VERSION.SDK_INT >= 19) {
                view.evaluateJavascript(script) { value -> onResult?.invoke(value) }
            } else {
                // The 4.x fallback: javascript: URLs, percent-encoded so quotes and # survive.
                view.loadUrl("javascript:" + Uri.encode(script))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "javascript failed: ${t.javaClass.simpleName}", t)
        }
    }

    private fun textsJs(): String = jsObject(
        "scrollNext" to getString(R.string.douyin_scroll_next),
        "scrollPrev" to getString(R.string.douyin_scroll_prev),
        "next" to getString(R.string.douyin_next),
        "prev" to getString(R.string.douyin_prev),
        "seekNone" to getString(R.string.douyin_seek_none),
        "forward" to getString(R.string.douyin_forward),
        "backward" to getString(R.string.douyin_backward),
        "play" to getString(R.string.douyin_play),
        "pause" to getString(R.string.douyin_pause),
        "liked" to getString(R.string.douyin_liked),
        "likeNone" to getString(R.string.douyin_like_none),
        "noVideo" to getString(R.string.douyin_no_video),
        "login" to getString(R.string.douyin_open_login),
        "topHint" to getString(R.string.douyin_top_hint),
    )

    /** The whole overlay tree, styled to survive 抖音's own CSS: one fixed layer, nothing clickable. */
    private fun overlayHtml(): String {
        val rows = listOf(
            R.string.douyin_help_updown_key to R.string.douyin_help_updown,
            R.string.douyin_help_lr_key to R.string.douyin_help_lr,
            R.string.douyin_help_ok_key to R.string.douyin_help_ok,
            R.string.douyin_help_hold_key to R.string.douyin_help_hold,
            R.string.douyin_help_menu_key to R.string.douyin_help_menu,
            R.string.douyin_help_back_key to R.string.douyin_help_back,
        )
        val card = StringBuilder()
        card.append("<h1>").append(htmlEscape(getString(R.string.douyin_help_title))).append("</h1>")
        for ((key, what) in rows) {
            card.append("<div class=\"row\"><span class=\"k\">").append(htmlEscape(getString(key)))
                .append("</span>").append(htmlEscape(getString(what))).append("</div>")
        }
        card.append("<div class=\"foot\">").append(htmlEscape(getString(R.string.douyin_help_close))).append("</div>")
        return "<style id=\"__aerial_style\">" + OVERLAY_CSS + "</style>" +
            "<div id=\"__aerial_top\"></div>" +
            "<div id=\"__aerial_toast\"></div>" +
            "<div id=\"__aerial_help\"><div id=\"__aerial_card\">" + card + "</div></div>" +
            "<div id=\"__aerial_compat\"></div>"
    }

    /**
     * 4.4's WebView is Chromium 30 and 抖音's page needs a modern one. Instead of loading a page
     * that will render as a blank shell, offer the owner two honest choices — and both are links
     * inside the WebView, so no Android widget appears here either.
     */
    private fun showCompatPage() {
        val view = web ?: return
        Log.i(TAG, "showing the old-WebView compatibility page (Chromium $chromeMajor)")
        val title = htmlEscape(getString(R.string.douyin_compat_title))
        val body = htmlEscape(getString(R.string.douyin_compat_body, chromeMajor.toString()))
        val tryLabel = htmlEscape(getString(R.string.douyin_compat_try))
        val appLabel = htmlEscape(getString(R.string.douyin_compat_app))
        val noApp = htmlEscape(getString(R.string.douyin_compat_no_app))
        val appRow = if (douyinPackage() != null) {
            "<a class=\"btn alt\" href=\"${COMPAT_SCHEME}app\">$appLabel</a>"
        } else {
            "<p class=\"small\">$noApp</p>"
        }
        val html = """
<!doctype html>
<html><head><meta charset="utf-8"><style>
html,body{height:100%;margin:0;background:#050506;color:#fff;font-family:"PingFang SC","Microsoft YaHei",sans-serif}
.wrap{position:absolute;left:14%;right:14%;top:22%;text-align:center}
.mark{display:inline-block;width:92px;height:92px;line-height:92px;border-radius:26px;background:#fff;color:#0a0a0c;font-size:50px;font-weight:bold}
h1{font-size:34px;font-weight:600;margin:26px 0 14px}
p{font-size:21px;line-height:1.7;color:rgba(255,255,255,.6);margin:0 0 8px}
.small{font-size:17px;color:rgba(255,255,255,.38);margin-top:22px}
a.btn{display:block;margin:14px auto 0;width:62%;padding:16px 0;border-radius:14px;background:#fff;color:#0a0a0c;font-size:21px;font-weight:600;text-decoration:none}
a.btn.alt{background:rgba(255,255,255,.12);color:#fff}
</style></head><body><div class="wrap"><div class="mark">抖</div><h1>$title</h1><p>$body</p>
<a class="btn" href="${COMPAT_SCHEME}app">$appLabel</a>
<a class="btn alt" href="${COMPAT_SCHEME}load">$tryLabel</a>
$appRow
</div></body></html>
""".trimIndent()
        runCatching { view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
            .onFailure { Log.w(TAG, "compat page could not be shown", it) }
    }

    /** The installed 抖音 package, if this box has one; null makes the page hide that choice. */
    private fun douyinPackage(): String? {
        for (name in DOUYIN_PACKAGES) {
            val launch = runCatching { packageManager.getLaunchIntentForPackage(name) }.getOrNull()
            if (launch != null) return name
        }
        return null
    }

    /** Hands over to an installed 抖音 app; returns false when there is nothing to launch. */
    private fun launchInstalledDouyin(): Boolean {
        val name = douyinPackage() ?: return false
        val launch = runCatching { packageManager.getLaunchIntentForPackage(name) }.getOrNull() ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(launch); Log.i(TAG, "launched installed 抖音: $name"); true }
            .getOrElse { Log.w(TAG, "could not launch $name", it); false }
    }

    /** The loading state: a page of its own inside the WebView, so no Android progress bar exists. */
    private fun splashHtml(): String {
        val title = htmlEscape(getString(R.string.douyin_loading_title))
        val hint = htmlEscape(getString(R.string.douyin_loading_hint))
        return """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{height:100%;margin:0;background:#050506;color:#fff;font-family:"PingFang SC","Microsoft YaHei",sans-serif;overflow:hidden}
.wrap{position:absolute;left:0;top:0;right:0;bottom:0;text-align:center;padding-top:21vh}
.mark{display:inline-block;width:104px;height:104px;line-height:104px;border-radius:30px;background:#fff;color:#0a0a0c;font-size:56px;font-weight:bold;box-shadow:0 0 130px rgba(254,44,85,.5)}
h1{font-size:36px;font-weight:600;margin:32px 0 12px}
p{font-size:21px;color:rgba(255,255,255,.55);margin:0}
.bar{width:300px;height:3px;margin:40px auto 0;background:rgba(255,255,255,.12);border-radius:3px;overflow:hidden}
.bar i{display:block;width:42%;height:100%;background:#fff;border-radius:3px;-webkit-animation:go 1.1s ease-in-out infinite;animation:go 1.1s ease-in-out infinite}
@-webkit-keyframes go{0%{-webkit-transform:translateX(-110%)}100%{-webkit-transform:translateX(350%)}}
@keyframes go{0%{transform:translateX(-110%)}100%{transform:translateX(350%)}}
</style></head><body><div class="wrap"><div class="mark">抖</div><h1>$title</h1><p>$hint</p><div class="bar"><i></i></div></div></body></html>
""".trimIndent()
    }

    /** An in-page explanation for a network/WebView load failure; Back leaves it. */
    private fun showErrorPage() {
        val view = web ?: return
        Log.w(TAG, "showing the in-page error explanation")
        val title = htmlEscape(getString(R.string.douyin_error_title))
        val body = htmlEscape(getString(R.string.douyin_error_body))
        val hint = htmlEscape(getString(R.string.douyin_exit_hint))
        val html = """
<!doctype html>
<html><head><meta charset="utf-8"><style>
html,body{height:100%;margin:0;background:#050506;color:#fff;font-family:"PingFang SC","Microsoft YaHei",sans-serif}
.wrap{position:absolute;left:12%;right:12%;top:28%;text-align:center}
h1{font-size:38px;font-weight:600;margin:0 0 24px}
p{font-size:22px;line-height:1.7;color:rgba(255,255,255,.6);margin:0 0 12px}
.h{font-size:19px;color:rgba(255,255,255,.4);margin-top:36px}
</style></head><body><div class="wrap"><h1>$title</h1><p>$body</p><p class="h">$hint</p></div></body></html>
""".trimIndent()
        runCatching { view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
            .onFailure { Log.w(TAG, "error page could not be shown", it) }
    }

    /** No WebView at all: a plain text page (still no buttons) and Back leaves. */
    private fun showMissingPage() {
        Log.w(TAG, "no usable WebView: showing the native explanation page")
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF050506.toInt())
            setPadding(dp(64f), dp(40f), dp(64f), dp(40f))
        }
        val mark = TextView(this).apply {
            text = "抖"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF25F4EE.toInt(), 0xFFFE2C55.toInt()),
            ).apply { cornerRadius = dp(22f).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(88f), dp(88f))
        }
        val title = TextView(this).apply {
            text = getString(R.string.douyin_webview_missing_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            setPadding(0, dp(30f), 0, 0)
        }
        val body = TextView(this).apply {
            text = getString(R.string.douyin_webview_missing_body)
            setTextColor(0xFFB8B8C0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(8f).toFloat(), 1f)
            setPadding(dp(40f), dp(16f), dp(40f), 0)
        }
        val hint = TextView(this).apply {
            text = getString(R.string.douyin_exit_hint)
            setTextColor(0xFF8E8E96.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, dp(34f), 0, 0)
        }
        page.addView(mark)
        page.addView(title)
        page.addView(body)
        page.addView(hint)
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---- remote ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (up) leave()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (up) toggleHelp()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) scroll(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (down) scroll(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) seek(10)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) seek(-10)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (up) {
                    val held = event.eventTime - event.downTime
                    if (helpShown) hideHelp() else onOk(held)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (up) {
                    Log.i(TAG, "key ${event.keyCode}: play/pause")
                    callJs("window.__aerial&&window.__aerial.toggle()")
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun scroll(dir: Int) {
        if (helpShown) {
            hideHelp()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastScrollAt < SCROLL_GAP_MS) return
        lastScrollAt = now
        Log.i(TAG, "key ${if (dir > 0) "DOWN" else "UP"}: scroll ${if (dir > 0) "forward" else "back"}")
        callJs("window.__aerial&&window.__aerial.scroll($dir)")
    }

    private fun seek(delta: Int) {
        if (helpShown) {
            hideHelp()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekAt < SEEK_GAP_MS) return
        lastSeekAt = now
        Log.i(TAG, "key ${if (delta > 0) "RIGHT" else "LEFT"}: seek ${delta}s")
        callJs("window.__aerial&&window.__aerial.seek($delta)")
    }

    private fun onOk(heldMs: Long) {
        // QR login and other pages with no <video>: OK opens the page's login instead of nothing.
        if (heldMs >= LONG_PRESS_MS) {
            Log.i(TAG, "OK held ${heldMs}ms: like")
            callJs("window.__aerial&&window.__aerial.like()")
        } else {
            Log.i(TAG, "OK (${heldMs}ms): play/pause")
            callJs("window.__aerial&&window.__aerial.toggle()")
        }
    }

    private fun toggleHelp() {
        helpShown = !helpShown
        Log.i(TAG, "menu: help layer ${if (helpShown) "on" else "off"}")
        callJs("window.__aerial&&window.__aerial.help(" + helpShown + ")")
    }

    private fun hideHelp() {
        helpShown = false
        callJs("window.__aerial&&window.__aerial.help(false)")
    }

    /** Back: out of fullscreen video first, then out of the help layer, then out of 抖音. */
    private fun leave() {
        if (fullscreen != null) {
            Log.i(TAG, "back: leaving fullscreen video")
            exitFullscreen()
            return
        }
        if (helpShown) {
            Log.i(TAG, "back: closing the help layer")
            hideHelp()
            return
        }
        Log.i(TAG, "back: leaving 抖音")
        finish()
    }

    private fun exitFullscreen() {
        val holder = fullscreen ?: return
        fullscreen = null
        root.removeView(holder)
        val callback = fullCallback
        fullCallback = null
        runCatching { callback?.onCustomViewHidden() }
        hideBars()
        Log.i(TAG, "fullscreen video off")
    }

    // ---- window ----

    private fun hideBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val flags = if (Build.VERSION.SDK_INT >= 19) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else if (Build.VERSION.SDK_INT >= 16) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        } else {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        window.decorView.systemUiVisibility = flags
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        hideBars()
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        callJs("(function(){var v=document.getElementsByTagName('video');for(var i=0;i<v.length;i++){try{v[i].pause()}catch(e){}}})()")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val view = web
        web = null
        if (view != null) {
            runCatching { view.loadUrl("about:blank") }
            runCatching { view.stopLoading() }
            runCatching { (view.parent as? FrameLayout)?.removeView(view) }
            runCatching { view.destroy() }
        }
        super.onDestroy()
    }

    // ---- small helpers ----

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    companion object {
        private const val TAG = "AerialDouyin"
        private const val URL = "https://www.douyin.com/"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        /** 抖音's page needs a Chromium far newer than 4.4's WebView (Chrome 30); warn below this. */
        private const val CHROME_MIN = 60
        /** In-page links the compat page uses; intercepted by shouldOverrideUrlLoading. */
        private const val COMPAT_SCHEME = "xingmu-douyin://"
        private val DOUYIN_PACKAGES = arrayOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.live",
            "com.ss.android.ugc.aweme.hd",
        )
        private const val LONG_PRESS_MS = 700L
        private const val SCROLL_GAP_MS = 120L
        private const val SEEK_GAP_MS = 350L
        private val CHROME = Regex("Chrome/(\\d+)")

        private fun jsQuote(text: String): String {
            val out = StringBuilder(text.length + 16)
            out.append('"')
            for (c in text) {
                when (c) {
                    '\\' -> out.append("\\\\")
                    '"' -> out.append("\\\"")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\u2028' -> out.append("\\u2028")
                    '\u2029' -> out.append("\\u2029")
                    else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
            out.append('"')
            return out.toString()
        }

        private fun jsObject(vararg pairs: Pair<String, String>): String =
            pairs.joinToString(prefix = "{", postfix = "}") { (key, value) -> jsQuote(key) + ":" + jsQuote(value) }

        private fun htmlEscape(text: String): String {
            val out = StringBuilder(text.length + 8)
            for (c in text) {
                when (c) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '"' -> out.append("&quot;")
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        /** Everything is one fixed layer above the page; `!important` because 抖音's CSS is broad. */
        private const val OVERLAY_CSS = """
html,body{overscroll-behavior:none !important}
::-webkit-scrollbar{width:0 !important;height:0 !important;background:transparent !important}
::-webkit-scrollbar-thumb{background:transparent !important}
#__aerial_ui,#__aerial_ui *{box-sizing:border-box;font-family:"PingFang SC","Hiragino Sans GB","Microsoft YaHei","Noto Sans CJK SC",sans-serif}
#__aerial_ui{position:fixed !important;left:0 !important;top:0 !important;right:0 !important;bottom:0 !important;z-index:2147483647 !important;pointer-events:none !important;color:#fff}
#__aerial_top{position:absolute;left:50%;top:26px;max-width:86vw;padding:13px 26px;border-radius:999px;background:rgba(10,10,12,.82);border:1px solid rgba(255,255,255,.16);font-size:22px;line-height:1.35;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;opacity:0;-webkit-transition:opacity .35s;transition:opacity .35s;-webkit-transform:translateX(-50%);transform:translateX(-50%)}
#__aerial_top.show{opacity:1}
#__aerial_toast{position:absolute;left:50%;bottom:60px;padding:12px 26px;border-radius:999px;background:rgba(10,10,12,.86);border:1px solid rgba(255,255,255,.18);font-size:22px;white-space:nowrap;opacity:0;-webkit-transition:opacity .2s;transition:opacity .2s;-webkit-transform:translateX(-50%);transform:translateX(-50%)}
#__aerial_toast.show{opacity:1}
#__aerial_help{position:absolute;left:0;top:0;right:0;bottom:0;background:rgba(0,0,0,.8);display:none}
#__aerial_help.show{display:block}
#__aerial_card{position:absolute;left:50%;top:50%;width:640px;max-width:82vw;padding:34px 46px 30px;border-radius:26px;background:rgba(18,18,22,.95);border:1px solid rgba(255,255,255,.16);box-shadow:0 24px 80px rgba(0,0,0,.6);-webkit-transform:translate(-50%,-50%);transform:translate(-50%,-50%);text-align:left}
#__aerial_card h1{margin:0 0 18px;font-size:30px;font-weight:600;text-align:center;color:#fff}
#__aerial_card .row{display:block;margin:12px 0;font-size:23px;line-height:1.5;color:rgba(255,255,255,.92)}
#__aerial_card .k{display:inline-block;min-width:150px;color:#59e1f2;font-weight:600}
#__aerial_card .foot{margin-top:22px;font-size:19px;color:rgba(255,255,255,.5);text-align:center}
#__aerial_compat{position:absolute;left:50%;top:96px;max-width:78vw;padding:16px 26px;border-radius:16px;background:rgba(58,38,0,.94);border:1px solid rgba(255,190,80,.5);color:#ffe2ad;font-size:20px;line-height:1.5;display:none;-webkit-transform:translateX(-50%);transform:translateX(-50%)}
#__aerial_compat.show{display:block}
"""

        /**
         * The in-page half of the remote, written for old Chromium too (no arrow functions, no
         * template strings). It finds the page's own scroll container, falls back to the window,
         * then to 抖音's next/previous video; seeks and pauses the largest <video>; and draws
         * every note with the injected layer, so no Android widget is ever involved.
         */
        private const val BRIDGE_JS = """
(function(){
if (window.__aerial) { try { window.__aerial.refresh(); } catch (e) {} return 2; }
var T = @@TEXTS@@;
var OVERLAY = @@OVERLAY@@;
var doc = document;
var root = null, topBar = null, toastBar = null, helpLayer = null, compatBar = null;
var topTimer = 0, toastTimer = 0;

function byId(id){ return doc.getElementById(id); }
function show(el, on){ if (!el) return; if (on) el.classList.add('show'); else el.classList.remove('show'); }

function ensure(){
  if (root && doc.documentElement && doc.documentElement.contains(root)) return true;
  if (!doc.body) return false;
  root = byId('__aerial_ui');
  var fresh = false;
  if (!root) {
    root = doc.createElement('div');
    root.id = '__aerial_ui';
    try { root.innerHTML = OVERLAY; } catch (e) { return false; }
    doc.body.appendChild(root);
    fresh = true;
  }
  topBar = byId('__aerial_top');
  toastBar = byId('__aerial_toast');
  helpLayer = byId('__aerial_help');
  compatBar = byId('__aerial_compat');
  if (fresh) top(T.topHint, 7000);
  return true;
}

/* 抖音's download / open-in-app floats: hidden, never the login entry. */
function hidePromo(){
  var junk = ['[data-e2e*="download"]','[data-e2e*="open-app"]','[data-e2e*="openApp"]',
    '[class*="download-app"]','[class*="downloadApp"]','[class*="DownloadApp"]',
    '[class*="open-app"]','[class*="openApp"]','[class*="app-guide"]','[class*="AppGuide"]',
    '[id*="download-app"]','[id*="downloadApp"]','[class*="float-download"]',
    '[class*="floatDownload"]','[class*="download-guide"]'];
  for (var i = 0; i < junk.length; i++) {
    var list = doc.querySelectorAll(junk[i]);
    for (var j = 0; j < list.length; j++) {
      var n = list[j];
      if (!n || !n.style) continue;
      var r = n.getBoundingClientRect ? n.getBoundingClientRect() : null;
      if (r && r.width > 760) continue;
      n.style.setProperty('display', 'none', 'important');
    }
  }
}

function top(text, ms){
  if (!ensure()) return;
  if (!text) return;
  topBar.textContent = text;
  show(topBar, true);
  if (topTimer) clearTimeout(topTimer);
  topTimer = setTimeout(function(){ show(topBar, false); }, ms || 6000);
}
function toast(text){
  if (!ensure()) return;
  toastBar.textContent = text;
  show(toastBar, true);
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(function(){ show(toastBar, false); }, 1200);
}
function compat(text){
  if (!ensure()) return;
  compatBar.textContent = text;
  show(compatBar, true);
}

/* The page's own scroll containers, deepest loss first; 抖音's feed is one of these. */
function scrollables(){
  var out = [];
  var nodes = doc.body ? doc.body.getElementsByTagName('*') : [];
  for (var i = 0; i < nodes.length; i++) {
    var e = nodes[i];
    if (!e.clientHeight || e.clientHeight < 220 || e.clientWidth < 220) continue;
    if (e.scrollHeight - e.clientHeight < 120) continue;
    var s = window.getComputedStyle ? window.getComputedStyle(e) : null;
    if (!s) continue;
    var oy = s.overflowY;
    if (oy !== 'auto' && oy !== 'scroll' && oy !== 'overlay') continue;
    out.push(e);
  }
  out.sort(function(a, b){ return (b.scrollHeight - b.clientHeight) - (a.scrollHeight - a.clientHeight); });
  return out;
}

function feedKey(dir){
  var code = dir > 0 ? 40 : 38;
  var name = dir > 0 ? 'ArrowDown' : 'ArrowUp';
  var target = doc.body || doc;
  var made = null;
  try {
    made = new KeyboardEvent('keydown', {key: name, code: name, keyCode: code, which: code, bubbles: true, cancelable: true});
  } catch (e) {
    try {
      made = doc.createEvent('KeyboardEvent');
      made.initKeyboardEvent('keydown', true, true, window, name, 0, '', '', false, '');
    } catch (e2) { made = null; }
  }
  if (!made) return false;
  try { target.dispatchEvent(made); } catch (e3) {}
  var up = null;
  try {
    up = new KeyboardEvent('keyup', {key: name, code: name, keyCode: code, which: code, bubbles: true, cancelable: true});
  } catch (e4) { up = null; }
  if (up) { try { target.dispatchEvent(up); } catch (e5) {} }
  return true;
}

/* 抖音's own next/previous control, for the last card of a feed. */
function clickNext(dir){
  var words = dir > 0 ? ['下一个','下一条','下首','next','Next'] : ['上一个','上一条','上一首','prev','Prev'];
  var nodes = doc.querySelectorAll('button,[role="button"],a,div[class*="next"],div[class*="prev"],div[class*="Next"],div[aria-label],div[title]');
  var limit = Math.min(nodes.length, 1200);
  for (var i = 0; i < limit; i++) {
    var n = nodes[i];
    var label = '';
    try {
      label = (n.getAttribute('aria-label') || '') + ' ' + (n.getAttribute('title') || '') + ' ' + (n.getAttribute('data-e2e') || '') + ' ';
      if (n.className && n.className.baseVal) label += n.className.baseVal;
      else if (typeof n.className === 'string') label += n.className;
    } catch (e) { label = ''; }
    var hit = false;
    for (var w = 0; w < words.length; w++) { if (label.indexOf(words[w]) >= 0) { hit = true; break; } }
    if (!hit) continue;
    var r = n.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0 || r.width > 360 || r.height > 360) continue;
    try { n.click(); return true; } catch (e2) {}
  }
  return false;
}

function scroll(dir){
  ensure(); hidePromo();
  var list = scrollables();
  var target = null, i, e, max;
  for (i = 0; i < list.length; i++) {
    e = list[i];
    max = e.scrollHeight - e.clientHeight;
    if (dir > 0 ? e.scrollTop < max - 2 : e.scrollTop > 2) { target = e; break; }
  }
  if (target) {
    max = target.scrollHeight - target.clientHeight;
    var step = Math.max(240, Math.round(target.clientHeight * 0.85));
    var before = target.scrollTop;
    target.scrollTop = Math.max(0, Math.min(max, target.scrollTop + dir * step));
    if (Math.abs(target.scrollTop - before) > 4) { toast(dir > 0 ? T.scrollNext : T.scrollPrev); return true; }
  }
  var se = doc.scrollingElement || doc.documentElement;
  var bodyH = doc.body ? doc.body.scrollHeight : 0;
  var winMax = Math.max(se ? se.scrollHeight : 0, bodyH) - window.innerHeight;
  if (winMax > 80) {
    var wb = window.pageYOffset || (se ? se.scrollTop : 0) || 0;
    if (dir > 0 ? wb < winMax - 2 : wb > 2) {
      window.scrollTo(0, Math.max(0, Math.min(winMax, wb + dir * Math.round(window.innerHeight * 0.85))));
      var wa = window.pageYOffset || (se ? se.scrollTop : 0) || 0;
      if (Math.abs(wa - wb) > 4) {
        toast(dir > 0 ? T.scrollNext : T.scrollPrev);
        return true;
      }
    }
  }
  var clicked = clickNext(dir);
  var keyed = feedKey(dir);
  toast((clicked || keyed) ? (dir > 0 ? T.next : T.prev) : (dir > 0 ? T.scrollNext : T.scrollPrev));
  return clicked || keyed;
}

function pickVideo(){
  var list = doc.getElementsByTagName('video');
  var best = null, playing = null, area = 0, i, v, r, a;
  for (i = 0; i < list.length; i++) {
    v = list[i];
    r = v.getBoundingClientRect();
    a = r.width * r.height;
    if (!v.paused && a > 0) playing = v;
    if (a > area) { area = a; best = v; }
  }
  return playing || best;
}

function seek(delta){
  ensure(); hidePromo();
  var v = pickVideo();
  if (!v || !isFinite(v.duration) || v.duration <= 0) { toast(T.seekNone); return false; }
  var to = Math.max(0, Math.min(v.duration - 0.4, (v.currentTime || 0) + delta));
  try { v.currentTime = to; } catch (e) { toast(T.seekNone); return false; }
  toast((delta > 0 ? T.forward : T.backward) + ' ' + Math.abs(Math.round(delta)) + 's');
  return true;
}

/* OK on a page without a video means the login page: open its QR dialog. */
function openLogin(){
  var sels = ['[data-e2e*="login"]','[class*="login"] button','[class*="login-btn"]','button[class*="login"]','[aria-label*="登录"]'];
  for (var i = 0; i < sels.length; i++) {
    var list = doc.querySelectorAll(sels[i]);
    for (var j = 0; j < list.length && j < 20; j++) {
      var n = list[j], r = n.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0 || r.width > 380 || r.height > 140) continue;
      var txt = n.textContent || '';
      if (txt.indexOf('登录') < 0 && !n.getAttribute('data-e2e')) continue;
      try { n.click(); toast(T.login); return true; } catch (e) {}
    }
  }
  var all = doc.querySelectorAll('div,span,button,a');
  var limit = Math.min(all.length, 4000);
  for (var k = 0; k < limit; k++) {
    var m = all[k], t = (m.textContent || '').replace(/^\s+|\s+$/g, '');
    if (t !== '扫码登录' && t !== '登录') continue;
    var rr = m.getBoundingClientRect();
    if (rr.width <= 0 || rr.height <= 0) continue;
    try { m.click(); toast(T.login); return true; } catch (e2) {}
  }
  return false;
}

function toggle(){
  ensure(); hidePromo();
  var v = pickVideo();
  if (!v) {
    if (openLogin()) return true;
    toast(T.noVideo);
    return false;
  }
  if (v.paused) {
    var p = null;
    try { p = v.play(); } catch (e) {}
    if (p && p.catch) p.catch(function(){});
    toast(T.play);
    return true;
  }
  try { v.pause(); } catch (e2) {}
  toast(T.pause);
  return true;
}

function like(){
  ensure(); hidePromo();
  var sels = ['[data-e2e="video-player-digg"]','[data-e2e="like-button"]','[data-e2e*="digg"]','[aria-label*="点赞"]','[class*="like"] button','[class*="digg"]'];
  for (var i = 0; i < sels.length; i++) {
    var n = doc.querySelector(sels[i]);
    if (!n) continue;
    var r = n.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) continue;
    try { n.click(); toast(T.liked); return true; } catch (e) {}
  }
  toast(T.likeNone);
  return false;
}

function helpVisible(){
  return !!(helpLayer && helpLayer.classList && helpLayer.classList.contains('show'));
}
function help(on){
  ensure();
  show(helpLayer, !!on);
  return helpVisible();
}
function toggleHelp(){ return help(!helpVisible()); }

window.__aerial = {
  refresh: function(){ ensure(); hidePromo(); },
  top: top,
  toast: toast,
  compat: compat,
  scroll: scroll,
  seek: seek,
  toggle: toggle,
  like: like,
  help: help,
  toggleHelp: toggleHelp,
  helpVisible: helpVisible,
  hideHelp: function(){ show(helpLayer, false); }
};

ensure(); hidePromo();
/* The SPA may replace the body on login; put the layer back if that happens. */
setInterval(function(){
  if (!root || !doc.documentElement || !doc.documentElement.contains(root)) { ensure(); hidePromo(); }
}, 4000);
return 1;
})();
"""
    }
}
