package app.xingmu.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.xingmu.remote.network.Endpoint
import app.xingmu.remote.security.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全屏横屏的屏幕镜像页。
 *
 * 画面不是用原生解码器渲染的：后端提供的是一条 `multipart/x-mixed-replace` 的 MJPEG 长连接，
 * 手机端最省事也最稳的做法就是把它交给系统 WebView（Chrome 内核原生支持在 `<img>` 里逐帧刷新），
 * 这里只是本地拼一个极简 HTML，用 `loadDataWithBaseURL` 挂到后端地址下，让 `<img>` 与流同源。
 *
 * 页面本身只负责画面、加载提示和断流提示；顶部的半透明条是原生 View，所以帧率切换、刷新、
 * 关闭都不依赖 JS bridge，也不会被页面里的异常卡住。
 */
class ScreenMirrorActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var fpsRow: LinearLayout
    private lateinit var gestureLayer: GestureLayer

    private var deviceId: String = ""
    private var deviceName: String = ""
    private var token: String = ""
    private var baseUrl: String = ""
    private var fps: Int = DEFAULT_FPS
    private var stoppedInBackground = false

    /**
     * 控制指令与画面走同一条后端，但按键不依赖 WebView：HTTP 命令的返回不阻塞画面，
     * 串行发送可以避免快速连点时 adb 注入乱序。
     */
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controlMutex = Mutex()
    private val controlBackend by lazy {
        (application as StarlightMobileApplication).backendClient
    }
    private val restoreStatus = Runnable {
        if (::statusView.isInitialized && !isFinishing) {
            statusView.text = "MJPEG 长连接 · $fps fps"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 看着电视画面时不该因为息屏时间到了就黑屏。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        enterImmersiveMode()

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty().trim()
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty().trim()
        val store = runCatching { TokenStore(this) }.getOrNull()
        token = store?.readToken().orEmpty()
        baseUrl = store?.baseUrl.orEmpty()

        setContentView(buildLayout())
        render()
    }

    override fun onStart() {
        super.onStart()
        if (stoppedInBackground) {
            stoppedInBackground = false
            // 回到前台时重开流：后台期间 WebView 已经被换成了空白页。
            if (::webView.isInitialized) {
                webView.onResume()
                render()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing || !::webView.isInitialized) return
        stoppedInBackground = true
        // 直接把页面换掉，浏览器就会关掉 MJPEG 连接，不在后台继续吃流量。
        webView.loadUrl("about:blank")
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        controlScope.cancel()
        if (::statusView.isInitialized) statusView.removeCallbacks(restoreStatus)
        if (::webView.isInitialized) {
            runCatching {
                webView.loadUrl("about:blank")
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(10), dp(8))
            // 半透明深色玻璃条：和 App 里其他 Aerial 面板同一套观感。
            background = GradientDrawable().apply { setColor(0xCC090B0A.toInt()) }
        }

        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            setTextColor(0xFFEAF3F2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusView = TextView(this).apply {
            setTextColor(0xFF8C9B98.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            setPadding(0, dp(1), 0, 0)
        }
        titleColumn.addView(titleView)
        titleColumn.addView(statusView)

        val fpsLabel = TextView(this).apply {
            text = "帧率"
            setTextColor(0xFF8C9B98.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(10), 0, dp(6), 0)
        }
        fpsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        FPS_OPTIONS.forEach { value ->
            val chip = TextView(this).apply {
                text = value.toString()
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (fps != value) {
                        fps = value
                        render()
                    }
                }
            }
            fpsRow.addView(
                chip,
                LinearLayout.LayoutParams(dp(36), dp(28)).apply { marginEnd = dp(5) },
            )
        }

        val refresh = actionButton("刷新") { render() }
        val close = actionButton("关闭") { finish() }

        bar.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(fpsLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(fpsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(
            refresh,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) },
        )
        bar.addView(
            close,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(6) },
        )

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(
            View(this).apply { setBackgroundColor(0x1FFFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
        )

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                // <img> 就是 MJPEG 的渲染器，必须允许加载网络图片。
                blockNetworkImage = false
                // 开发期后端可能是 http，页面本身也可能以 https 地址为 base，放开混合内容。
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            }
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        val stage = FrameLayout(this)
        stage.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        gestureLayer = GestureLayer(this)
        stage.addView(
            gestureLayer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            View(this).apply { setBackgroundColor(0x1FFFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
        )
        root.addView(
            buildControlBar(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return root
    }

    /**
     * 画面下方的实体遥控条。镜像画面在电视上多半是 D-pad UI，绝对触屏不一定有焦点语义，
     * 所以画面手势和这里都走电视遥控同一条按键通道（轻点=确定，滑动=方向）。
     */
    private fun buildControlBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply { setColor(0xF2090B0A.toInt()) }
        }
        val hint = TextView(this).apply {
            text = "轻点 = 确定 · 滑动 = 方向"
            setTextColor(0xFF8C9B98.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
        }
        bar.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        CONTROL_KEYS.forEach { (label, key) ->
            bar.addView(
                controlButton(label) { sendKey(key) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(7) },
            )
        }
        return bar
    }

    private fun controlButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(0xFFEAF3F2.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(11), dp(5), dp(11), dp(5))
        background = pill(0x14FFFFFF, 0x26FFFFFF)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun sendKey(key: String) {
        if (deviceId.isBlank() || token.isBlank()) return
        controlScope.launch {
            controlMutex.withLock {
                runCatching {
                    controlBackend.sendCommand(
                        baseUrl,
                        token,
                        deviceId,
                        "key",
                        JSONObject().put("key", key),
                    )
                }.onFailure { error ->
                    runOnUiThread {
                        if (!isFinishing && ::statusView.isInitialized) {
                            statusView.removeCallbacks(restoreStatus)
                            statusView.text = "按键发送失败：${error.message ?: "网络错误"}"
                            statusView.postDelayed(restoreStatus, 2200)
                        }
                    }
                }
            }
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(0xFFEAF3F2.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(13), dp(6), dp(13), dp(6))
        background = pill(0x14FFFFFF, 0x26FFFFFF)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * 盖在 WebView 上的一层透明手势区：电视 UI 是 D-pad 焦点模型，轻点发确定、四向滑动发方向，
     * 比把手指坐标硬映射到电视像素更可靠（M08 的 UI 对 adb 注入的触摸点击并不响应）。
     */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class GestureLayer(context: Context) : View(context) {
        private var downX = 0f
        private var downY = 0f

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val threshold = dp(44).toFloat()
                    if (abs(dx) > threshold || abs(dy) > threshold) {
                        if (abs(dx) >= abs(dy)) {
                            sendKey(if (dx > 0) "DPAD_RIGHT" else "DPAD_LEFT")
                        } else {
                            sendKey(if (dy > 0) "DPAD_DOWN" else "DPAD_UP")
                        }
                    } else {
                        performClick()
                        sendKey("DPAD_CENTER")
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        updateFpsChips()
        if (::titleView.isInitialized) {
            titleView.text = deviceName.ifBlank { "屏幕镜像" }
        }
        when {
            deviceId.isBlank() -> {
                statusView.text = "未选择电视"
                loadErrorPage(
                    "还没有选择电视",
                    "请返回设备页，选中一台电视后再打开屏幕镜像。",
                )
            }
            token.isBlank() -> {
                statusView.text = "登录状态无效"
                loadErrorPage(
                    "登录已失效",
                    "没有可用的登录令牌，请返回并重新登录后再试。",
                )
            }
            else -> {
                val normalized = runCatching { Endpoint.normalize(baseUrl) }.getOrNull()
                if (normalized.isNullOrBlank()) {
                    statusView.text = "后端地址无效"
                    loadErrorPage(
                        "后端地址无效",
                        "请检查 App 的后端服务器地址后重试。",
                    )
                } else {
                    statusView.text = "MJPEG 长连接 · $fps fps"
                    webView.loadDataWithBaseURL(
                        normalized,
                        buildMirrorHtml(normalized, deviceId, fps, token),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
            }
        }
    }

    private fun updateFpsChips() {
        if (!::fpsRow.isInitialized) return
        for (index in 0 until fpsRow.childCount) {
            val chip = fpsRow.getChildAt(index) as? TextView ?: continue
            val selected = chip.text.toString().toIntOrNull() == fps
            chip.setTextColor(if (selected) 0xFF7FE7E1.toInt() else 0xFFB8C4C1.toInt())
            chip.background = pill(
                if (selected) 0x263FD0C9 else 0x12FFFFFF,
                if (selected) 0x803FD0C9.toInt() else 0x24FFFFFF,
            )
        }
    }

    private fun loadErrorPage(title: String, detail: String) {
        webView.loadDataWithBaseURL(
            null,
            buildErrorHtml(title, detail),
            "text/html",
            "UTF-8",
            null,
        )
    }

    // ---------------------------------------------------------------- html

    private fun buildMirrorHtml(base: String, deviceId: String, fps: Int, token: String): String {
        // 末尾的 _ts 只是为了让每次刷新都拿到新 URL，避免 WebView 复用上一张失败的图。
        val streamUrl = buildString {
            append(base)
            append("/api/device/")
            append(Uri.encode(deviceId))
            append("/screen.mjpeg?fps=")
            append(fps)
            append("&token=")
            append(Uri.encode(token))
            append("&_ts=")
            append(System.currentTimeMillis())
        }
        val streamUrlJs = JSONObject.quote(streamUrl)
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
            <meta name="color-scheme" content="dark">
            <title>屏幕镜像</title>
            <style>
              html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
              body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; color: #eaf3f2; }
              #stage { position: fixed; top: 0; right: 0; bottom: 0; left: 0; background: #000; display: flex; align-items: center; justify-content: center; }
              #screen { display: block; width: 100%; height: 100%; object-fit: contain; background: #000; visibility: hidden; }
              #hint { position: fixed; left: 50%; top: 50%; transform: translate(-50%, -50%); width: 78%; max-width: 420px;
                box-sizing: border-box; padding: 18px 20px; border-radius: 16px; text-align: center;
                background: rgba(9, 13, 12, .88); border: 1px solid rgba(255, 255, 255, .14);
                opacity: 0; pointer-events: none; transition: opacity .2s ease; }
              #hint.show { opacity: 1; }
              #hint.error { border-color: rgba(255, 90, 80, .55); color: #ffb8b2; }
              #hint.error .spinner { display: none; }
              .spinner { width: 22px; height: 22px; margin: 0 auto 10px auto; border-radius: 50%;
                border: 2px solid rgba(127, 231, 225, .25); border-top-color: #7fe7e1;
                animation: spin .9s linear infinite; }
              #hint-text { font-size: 14px; line-height: 1.5; }
              @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
            </style>
            </head>
            <body>
            <div id="stage"><img id="screen" alt="电视画面"></div>
            <div id="hint" class="show"><div class="spinner"></div><div id="hint-text">正在连接电视画面…</div></div>
            <script>
            (function () {
              var streamUrl = $streamUrlJs;
              var img = document.getElementById('screen');
              var hint = document.getElementById('hint');
              var hintText = document.getElementById('hint-text');
              var connected = false;
              var failed = false;
              function show(text, isError) {
                hintText.textContent = text;
                hint.className = isError ? 'show error' : 'show';
              }
              function hide() { hint.className = ''; }
              img.onload = function () {
                img.style.visibility = 'visible';
                if (img.naturalWidth > 0) { connected = true; hide(); }
              };
              img.onerror = function () {
                failed = true;
                img.style.visibility = 'hidden';
                show('画面中断：点刷新重试；若一直无画面，多为服务器无法访问电视 adb（后端与电视不在同一网络）', true);
              };
              img.src = streamUrl;
              // Chrome 对 MJPEG 只在第一帧触发一次 load，这里再轮询一次 naturalWidth 兜底。
              var poll = setInterval(function () {
                if (img.naturalWidth > 0 && img.complete) {
                  img.style.visibility = 'visible';
                  connected = true; hide(); clearInterval(poll);
                }
              }, 400);
              // 连接被后端/网络直接掐断时，Chrome 的 <img> 不一定会触发 onerror，
              // 但会把 naturalWidth 归零；守住这个变化，断流时才能真的弹出重试提示。
              setInterval(function () {
                if (connected && img.naturalWidth === 0) {
                  connected = false;
                  img.style.visibility = 'hidden';
                  show('画面中断：点刷新重试；若一直无画面，多为服务器无法访问电视 adb（后端与电视不在同一网络）', true);
                }
              }, 1000);
              setTimeout(function () {
                if (!connected && !failed) show('正在连接电视画面…', false);
              }, 1500);
              setTimeout(function () {
                if (!connected && !failed) show('画面中断：点刷新重试；若一直无画面，多为服务器无法访问电视 adb（后端与电视不在同一网络）', true);
              }, 15000);
              window.addEventListener('beforeunload', function () { img.src = ''; });
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildErrorHtml(title: String, detail: String): String {
        val safeTitle = TextUtils.htmlEncode(title)
        val safeDetail = TextUtils.htmlEncode(detail)
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
            <meta name="color-scheme" content="dark">
            <title>屏幕镜像</title>
            <style>
              html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
              body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; color: #eaf3f2;
                display: flex; align-items: center; justify-content: center; }
              .card { width: 78%; max-width: 460px; box-sizing: border-box; padding: 24px 22px; border-radius: 18px;
                text-align: center; background: #0e1110; border: 1px solid rgba(255, 255, 255, .12); }
              .mark { width: 42px; height: 42px; line-height: 40px; margin: 0 auto 14px auto; border-radius: 50%;
                border: 1px solid rgba(255, 69, 58, .55); color: #ff8d84; font-size: 22px; font-weight: 700; }
              h1 { margin: 0 0 8px 0; font-size: 18px; font-weight: 600; }
              p { margin: 0; font-size: 13px; line-height: 1.65; color: #9fb0ac; }
              .tip { margin-top: 14px; color: #66756f; font-size: 12px; }
            </style>
            </head>
            <body>
            <div class="card">
              <div class="mark">!</div>
              <h1>$safeTitle</h1>
              <p>$safeDetail</p>
              <p class="tip">也可以点顶部「刷新」重试，或返回后重新选择电视。</p>
            </div>
            </body>
            </html>
        """.trimIndent()
    }

    // ---------------------------------------------------------------- misc

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun pill(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_DEVICE_ID = "app.xingmu.remote.extra.DEVICE_ID"
        const val EXTRA_DEVICE_NAME = "app.xingmu.remote.extra.DEVICE_NAME"

        private const val DEFAULT_FPS = 15
        private val FPS_OPTIONS = intArrayOf(4, 8, 12, 15)
        private val CONTROL_KEYS = listOf(
            "确定" to "DPAD_CENTER",
            "返回" to "BACK",
            "主页" to "HOME",
            "菜单" to "MENU",
            "音量-" to "VOLUME_DOWN",
            "音量+" to "VOLUME_UP",
        )

        fun intent(context: Context, deviceId: String, deviceName: String?): Intent =
            Intent(context, ScreenMirrorActivity::class.java)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_DEVICE_NAME, deviceName.orEmpty())
    }
}
