package app.xingmu.aerial.cast

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The 星投屏 agent, rebuilt inside Aerial Lite: one HTTP register call plus a WebSocket that
 * carries the heartbeat and receives remote commands. OkHttp 3 is used because these TVs run
 * Android 4.x, where the platform has no WebSocket at all.
 *
 * Runs on its own thread; [state] is updated on the main thread so the home screen can read it
 * without touching this class from the UI.
 */
object CastAgent {
    private const val TAG = "AerialCast"
    private const val HEARTBEAT_SECONDS = 5L
    private const val RECONNECT_MS = 5_000L

    enum class State { Idle, Registering, Pending, Online, Error }

    /** What the UI shows; written on the main thread. */
    @Volatile var state: State = State.Idle
        private set
    @Volatile var message: String = ""
        private set
    @Volatile var deviceId: String = ""

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    private var socket: WebSocket? = null
    private var heartbeat: ScheduledExecutorService? = null
    private val captureWorker = Executors.newSingleThreadExecutor()
    private var previewScheduler: ScheduledExecutorService? = null
    private var p2p: P2pChannel? = null
    /** Refreshed by a cheap adbd probe; drives control_mode / capture_mode reporting. */
    @Volatile private var adbReady = false
    @Volatile private var foregroundPackage = ""
    /** Player page URL pushed by the phone; the DLNA path prefers it for HLS streams. */
    @Volatile var lastPlayerUrl: String = ""

    /** Cached /api/vip/device/status payload, and the pairing code when the TV is still pending. */
    @Volatile var accountStatus: JSONObject? = null
    @Volatile var pairingCode: String = ""
    @Volatile var pairingUri: String = ""
    @Volatile var accountBusy = false
    @Volatile var accountError: String = ""
    /** VIP plans for the account page; empty when sales are off or the device has no owner. */
    @Volatile var plans: JSONArray? = null
    /** Current payment order payload and the last error, for the pay page. */
    @Volatile var payment: JSONObject? = null
    @Volatile var paymentError: String = ""
    @Volatile var paymentBusy = false
    /** Set by the open account page so a server-side binding change repaints it by itself. */
    @Volatile var accountListener: (() -> Unit)? = null
    private var appContext: Context? = null
    private var client: OkHttpClient? = null
    private var listeners = mutableListOf<(State, String) -> Unit>()

    fun addListener(listener: (State, String) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    fun removeListener(listener: (State, String) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    fun start(context: Context) {
        CastConfig.init(context)
        appContext = context.applicationContext
        if (!CastConfig.configured) {
            publish(State.Idle, "还没有配置后端地址")
            return
        }
        if (!running.compareAndSet(false, true)) return
        deviceId = CastConfig.deviceId
        val worker = Thread({ loop() }, "aerial-cast-agent")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    fun stop() {
        running.set(false)
        stopPreview()
        p2p?.stop()
        p2p = null
        socket?.close(1000, "stopped")
        socket = null
        heartbeat?.shutdownNow()
        heartbeat = null
        thread?.interrupt()
        thread = null
        publish(State.Idle, "已停止")
    }

    val runningNow: Boolean get() = running.get()

    private fun loop() {
        while (running.get()) {
            try {
                val token = ensureRegistered() ?: run {
                    sleep(RECONNECT_MS); continue
                }
                connect(token)
            } catch (error: Exception) {
                publish(State.Error, friendly(error))
                Log.w(TAG, "agent loop: " + error.javaClass.simpleName)
            }
            if (running.get()) {
                // A TV waiting for approval should not re-open the socket every five seconds.
                sleep(if (state == State.Pending) 15_000L else RECONNECT_MS)
            }
        }
    }

    /** POST /api/device/register; returns the device token to authenticate the socket with. */
    private fun ensureRegistered(): String? {
        CastConfig.init(appContext!!)
        if (CastConfig.deviceToken.isNotEmpty()) return CastConfig.deviceToken
        publish(State.Registering, "正在向后端登记设备")
        val body = JSONObject()
            .put("device_id", CastConfig.deviceId)
            .put("name", CastConfig.deviceName)
            .put("model", android.os.Build.MODEL ?: "Android TV")
            .put("app_version", BuildConfigName())
            .put("lan_ip", localIpv4())
        val request = Request.Builder()
            .url(CastConfig.backendUrl + "/api/device/register")
            .post(RequestBody.create(JSON, body.toString()))
            .build()
        http().newCall(request).execute().use { response: Response ->
            val text = response.body()?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code() == 409) {
                    // The backend already knows this device id but we hold no token (app data
                    // cleared, or the row outlived the token). Take a new identity so this TV can
                    // still be added, instead of retrying a register that can never succeed.
                    CastConfig.rotateIdentity()
                    publish(State.Pending, "设备身份已更换，等待管理员审核")
                    return null
                }
                publish(State.Error, "登记失败 HTTP " + response.code())
                return null
            }
            val json = JSONObject(text)
            val token = json.optString("device_token", "")
            if (token.isEmpty()) return null
            CastConfig.storeToken(token)
            val status = json.optString("status", "pending")
            publish(
                if (status == "approved") State.Registering else State.Pending,
                if (status == "approved") "已登记，正在连接" else "等待管理员审核"
            )
            return token
        }
    }

    /** One WebSocket connection; returns when the socket closes. */
    private fun connect(token: String) {
        val closed = CountDownLatch(1)
        val url = wsBase() + "/ws/device"
        val request = Request.Builder().url(url).build()
        publish(State.Registering, "正在连接控制中心")
        val ws = http().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "socket open")
                try {
                    val payload = JSONObject().put("type", "auth")
                        .put("device_id", CastConfig.deviceId)
                        .put("token", token)
                    val queued = webSocket.send(payload.toString())
                    Log.i(TAG, "auth queued=" + queued + " id=" + CastConfig.deviceId + " tokenLen=" + token.length)
                } catch (error: Exception) {
                    Log.w(TAG, "auth build failed: " + error)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handle(text, webSocket) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failed: " + t.javaClass.simpleName + " / " + t.message)
                publish(State.Error, friendly(t))
                closed.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "socket closed: " + code + " " + reason)
                if (code == 4401) {
                    // Token rejected: register again next round instead of retrying forever.
                    CastConfig.rotateIdentity()
                }
                publish(if (code == 4403) State.Pending else State.Error,
                    if (code == 4403) "等待管理员审核" else "连接已断开（" + code + "）")
                closed.countDown()
            }
        })
        socket = ws
        closed.await()
        socket = null
        heartbeat?.shutdownNow()
        heartbeat = null
    }

    private fun handle(text: String, ws: WebSocket) {
        val json = JSONObject(text)
        Log.i(TAG, "recv " + json.optString("type"))
        when (json.optString("type")) {
            "authenticated" -> {
                Log.i(TAG, "authenticated by backend")
                // A binding may have changed while the socket was down; repaint from the server.
                refreshAccount(accountListener ?: {})
                publish(State.Online, "已连接")
                reportAppInventory(ws)
                startHeartbeat(ws)
            }
            "command" -> {
                val command = json.optJSONObject("command") ?: return
                val id = command.optInt("id", 0)
                val type = command.optString("type", "")
                if (type == "snapshot") {
                    // Capture off the socket thread: adb screencap takes a few hundred milliseconds.
                    captureWorker.execute {
                        val jpeg = captureJpeg()
                        if (jpeg != null) runCatching { ws.send(okio.ByteString.of(*jpeg)) }
                        sendResult(ws, id, jpeg != null, if (jpeg != null) "已回传电视画面" else "截图失败（需要 ADB TCP 或 Root）")
                    }
                    return
                }
                val payload = command.optJSONObject("payload") ?: JSONObject()
                val (ok, detail) = executeCommand(type, payload)
                sendResult(ws, id, ok, detail)
            }
            "p2p_start" -> {
                val ctx = appContext ?: return
                val channel = P2pChannel(ctx)
                p2p?.stop()
                p2p = channel
                channel.start(
                    json.optString("session_id"),
                    json.optString("token"),
                    json.optString("channel_key"),
                    json.optString("rendezvous_host"),
                    json.optInt("rendezvous_port", 0),
                    json.optLong("expires_at", 0L),
                )
            }
            // The phone just claimed the pairing code: pull the account again so the page updates
            // without the user pressing 刷新.
            "binding_updated" -> refreshAccount(accountListener ?: {})
            // Admin approval from the console lands as approval_result; same effect on the page.
            "approval_result" -> refreshAccount(accountListener ?: {})
            "preview_start" -> startPreview(ws, bounded(json.optInt("fps", 1), 1, 3))
            "preview_stop" -> stopPreview()
            "error" -> Log.w(TAG, "backend error: " + json.optString("code"))
        }
    }

    /** Downloads the APK the backend already stored, then installs it through the TV's own adbd. */
    private fun installFromUrl(payload: JSONObject): Pair<Boolean, String> {
        val url = payload.optString("url")
        if (url.isEmpty()) return false to "缺少 APK 地址"
        val ctx = appContext ?: return false to "服务未就绪"
        return runCatching {
            val request = Request.Builder().url(url).build()
            http().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false to ("下载失败：" + response.code())
                val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
                val safe = payload.optString("filename").ifEmpty { "update.apk" }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(dir, safe)
                response.body()?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                val result = LegacyAdbClient.installPackage(ctx, CastConfig.adbPort, file)
                file.delete()
                if (result != null && result.success) true to "已安装"
                else false to ("安装失败：" + (result?.message ?: "adb 不可用"))
            }
        }.getOrElse { false to ("安装异常：" + (it.message ?: "unknown")) }
    }
    /** media: play/pause/stop/seek against whatever player is currently bound. */
    private fun mediaCommand(payload: JSONObject): Pair<Boolean, String> {
        val action = payload.optString("action").ifEmpty { payload.optString("command") }.lowercase()
        return when (action) {
            "play", "resume" -> { CastPlayer.resume(); true to "已继续播放" }
            "pause" -> { CastPlayer.pause(); true to "已暂停" }
            "stop" -> { CastPlayer.stop(); true to "已停止" }
            "seek" -> {
                val ms = payload.optLong("position_ms", payload.optLong("position", 0L))
                CastPlayer.seek(ms)
                true to "已跳转到 $ms ms"
            }
            "status" -> true to (CastPlayer.state.name.lowercase() + " " + CastPlayer.positionMs + "/" + CastPlayer.durationMs)
            else -> false to "不支持的播放动作：$action"
        }
    }

    /** power: sleep/wake/toggle, pressed through the TV's own adbd key injector. */
    private fun powerCommand(payload: JSONObject): Pair<Boolean, String> {
        val ctx = appContext ?: return false to "服务未就绪"
        val action = payload.optString("action").ifEmpty { "toggle" }.lowercase()
        val code = when (action) {
            "sleep", "off", "standby" -> 223
            "wake", "on" -> 224
            else -> 26
        }
        val ok = LegacyAdbClient.sendKey(ctx, CastConfig.adbPort, code)
        return if (ok) true to "已发送电源指令：$action" else false to "电源指令失败（adb 不可用）"
    }

    /** settings: the ones a TV agent can really change without system permissions. */
    private fun settingsCommand(payload: JSONObject): Pair<Boolean, String> {
        val ctx = appContext ?: return false to "服务未就绪"
        val name = payload.optString("name").ifEmpty { payload.optString("key") }.lowercase()
        if (name == "volume" || name == "音量") {
            val value = payload.optInt("value", -1)
            if (value !in 0..100) return false to "音量取值 0-100"
            val audio = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, value * max / 100, 0)
            return true to "音量已设为 $value"
        }
        return false to "不支持的设置项：$name"
    }
    /** One remote command. Everything the launcher cannot really do is reported as failed. */
    private fun executeCommand(type: String, payload: JSONObject): Pair<Boolean, String> = when (type) {
        "webplayer" -> openWebPlayer(payload)
        "app" -> if (payload.optString("action") == "foreground") bringSelfToFront() else launchApp(payload)
        "key" -> sendKey(payload)
        "install" -> installFromUrl(payload)
        "media" -> mediaCommand(payload)
        "power" -> powerCommand(payload)
        "settings" -> settingsCommand(payload)
        "switch" -> false to "本机不支持输入源切换"
        "snapshot" -> false to "快照由服务端抓取，本机不重复上报"
        "p2p" -> false to "P2P 由服务端会话驱动，不需要指令"
        else -> false to "Aerial Lite 暂不支持该指令：$type"
    }

    /**
     * Keys: the four global actions go through the accessibility service when it is on; everything
     * else (D-pad, OK, volume) needs the TV's own adbd on 127.0.0.1, which is what the phone remote
     * uses on boxes that expose "ADB 调试". Nothing here pretends a key was sent when it was not.
     */
    private fun sendKey(payload: JSONObject): Pair<Boolean, String> {
        val name = payload.optString("key", "").trim().lowercase()
        val labelled = name.ifEmpty { payload.optInt("keycode", 0).toString() }
        var code = payload.optInt("keycode", 0)
        if (code <= 0) {
            // 网页/手机可能发 DPAD_RIGHT、KEYCODE_DPAD_RIGHT、dpad_right、22 等写法
            var normalised = name.trim().lowercase().replace(" ", "_")
            for (prefix in listOf("keycode_", "dpad_", "remote_")) {
                if (normalised.startsWith(prefix)) normalised = normalised.removePrefix(prefix)
            }
            code = if (normalised.toIntOrNull() != null) normalised.toInt() else keyCodeFor(normalised)
        }
        if (code <= 0) return false to "未知按键：$labelled"
        val global = when (code) {
            4 -> AccessibilityService.GLOBAL_ACTION_BACK
            3 -> AccessibilityService.GLOBAL_ACTION_HOME
            187 -> AccessibilityService.GLOBAL_ACTION_RECENTS
            83 -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> 0
        }
        // performGlobalAction is API 16; Aerial Lite still ships to Android 4.0/4.1, where the
        // only path is adbd (below).
        if (global != 0 && android.os.Build.VERSION.SDK_INT >= 16 && CastA11yService.connected &&
            CastA11yService.perform(global)
        ) {
            return true to "已发送按键：$labelled"
        }
        val context = appContext
        if (context != null) {
            val sent = runCatching { LegacyAdbClient.sendKey(context, CastConfig.adbPort, code) }
                .getOrDefault(false)
            if (sent) return true to "已通过 ADB 发送按键：$labelled"
        }
        return false to "按键发送失败：请开启无障碍（返回/主页/最近任务），或在电视上启用 ADB TCP（端口 " +
            CastConfig.adbPort + "）"
    }

    private fun keyCodeFor(name: String): Int = when (name) {
        "up" -> 19
        "down" -> 20
        "left" -> 21
        "right" -> 22
        "ok", "center", "enter", "select" -> 23
        "back" -> 4
        "home" -> 3
        "menu" -> 82
        "recents" -> 187
        "volume_up" -> 24
        "volume_down" -> 25
        "power" -> 26
        else -> 0
    }

    /**
     * Opens the backend's hosted player page. The phone creates the player session (only a user can)
     * and sends the resulting URL here, so the TV never needs to know the ticket flow.
     */
    /**
     * Asks the backend for a hosted player page for [stream]. The TV authenticates with its device
     * credentials, so no phone has to be in the loop, and returns "" when anything fails.
     */
    /** Pulls account + membership for the Control Center page; off the socket thread. */
    fun refreshAccount(onDone: () -> Unit = {}) {
        if (accountBusy) return
        accountBusy = true
        captureWorker.execute {
            runCatching {
                val status = deviceCall("/api/vip/device/status", "GET")
                accountStatus = status
                plans = runCatching { deviceCallArray("/api/vip/device/plans") }.getOrNull()
                if (status == null || status.isNull("owner")) {
                    val pairing = deviceCall("/api/pairing/device", "POST")
                    // The response nests the code under app_pairing; there is no top-level "code".
                    val app = pairing?.optJSONObject("app_pairing")
                    pairingCode = app?.optString("code").orEmpty()
                    pairingUri = app?.optString("uri").orEmpty()
                } else {
                    pairingCode = ""
                    pairingUri = ""
                }
                accountError = if (status == null && pairingCode.isEmpty()) "无法获取账号信息（详见日志）" else ""
            }
            accountBusy = false
            Handler(Looper.getMainLooper()).post(onDone)
        }
    }

    /** Starts a VIP payment for [planId]; the response carries the code to show as a QR. */
    fun createPayment(planId: Int, method: String): JSONObject? = deviceCall(
        "/api/vip/device/payments", "POST",
        JSONObject().put("plan_id", planId).put("payment_method", method).toString(),
    )

    fun paymentStatus(orderNo: String): JSONObject? = deviceCall("/api/vip/device/payments/" + orderNo, "GET")
    /** Creates the order and reports back on the main thread; the pay page renders from [payment]. */
    fun startPayment(planId: Int, method: String, onDone: () -> Unit) {
        if (paymentBusy) return
        paymentBusy = true
        paymentError = ""
        captureWorker.execute {
            val result = createPayment(planId, method)
            payment = result
            paymentError = if (result == null) "下单失败（支付可能未配置）" else ""
            paymentBusy = false
            Handler(Looper.getMainLooper()).post(onDone)
        }
    }

    /** Re-reads the order so the QR page can show "已支付" without leaving the screen. */
    fun refreshPayment(onDone: () -> Unit) {
        val orderNo = payment?.optString("order_no").orEmpty()
        if (orderNo.isEmpty()) return
        if (paymentBusy) return
        paymentBusy = true
        captureWorker.execute {
            val latest = paymentStatus(orderNo)
            if (latest != null) payment = latest
            paymentBusy = false
            Handler(Looper.getMainLooper()).post(onDone)
        }
    }
    /** Device-authenticated GET that expects a JSON array (the plans list). */
    private fun deviceCallArray(path: String): JSONArray? {
        if (CastConfig.backendUrl.isEmpty()) return null
        return runCatching {
            val request = Request.Builder()
                .url(CastConfig.backendUrl.trimEnd('/') + path)
                .addHeader("X-Device-ID", CastConfig.deviceId)
                .addHeader("X-Device-Token", CastConfig.deviceToken)
                .build()
            http().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONArray(response.body()?.string().orEmpty())
            }
        }.getOrNull()
    }
    /** Device-authenticated call: returns the JSON body, or null on any failure. */
    private fun deviceCall(path: String, method: String, body: String = "{}"): JSONObject? {
        if (CastConfig.backendUrl.isEmpty()) return null
        return runCatching {
            val url = CastConfig.backendUrl.trimEnd('/') + path
            val builder = Request.Builder()
                .url(url)
                .addHeader("X-Device-ID", CastConfig.deviceId)
                .addHeader("X-Device-Token", CastConfig.deviceToken)
            if (method == "POST") {
                // Pairing endpoints expect the credentials in the body; payment endpoints take their
                // own JSON, so only fill in credentials when the caller passed nothing.
                val payload = if (body == "{}") {
                    JSONObject().put("device_id", CastConfig.deviceId).put("device_token", CastConfig.deviceToken).toString()
                } else {
                    body
                }
                builder.post(RequestBody.create(MediaType.parse("application/json"), payload))
            }
            http().newCall(builder.build()).execute().use { response ->
                Log.i(TAG, "deviceCall " + path + " -> " + response.code())
                if (!response.isSuccessful) return null
                JSONObject(response.body()?.string().orEmpty())
            }
        }.getOrNull()
    }
    fun hostedPlayerPage(stream: String): String {
        if (stream.isEmpty() || CastConfig.backendUrl.isEmpty()) return ""
        return runCatching {
            val body = JSONObject()
                .put("url", stream)
                .put("media_type", if (stream.contains("m3u8", true)) "hls" else "video")
                .put("title", CastConfig.deviceName)
            val request = Request.Builder()
                .url(CastConfig.backendUrl.trimEnd('/') + "/api/device/player/sessions")
                .addHeader("X-Device-ID", CastConfig.deviceId)
                .addHeader("X-Device-Token", CastConfig.deviceToken)
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString()))
                .build()
            http().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ""
                val json = JSONObject(response.body()?.string().orEmpty())
                json.optString("player_url")
            }
        }.getOrDefault("")
    }
    private fun openWebPlayer(payload: JSONObject): Pair<Boolean, String> {
        val context = appContext ?: return false to "应用上下文缺失"
        val page = payload.optString("player_url").ifEmpty { payload.optString("url") }
        if (page.isEmpty()) return false to "缺少播放页地址"
        lastPlayerUrl = page
        // The page's /media endpoint is a plain HTTP relay of the origin stream, which is exactly
        // what ijkplayer wants: the TV cannot reach the CDN itself, and its WebView renders video
        // black. WebView stays as the fallback.
        val media = if (page.endsWith("/media")) page else page.trimEnd('/') + "/media"
        val ijk = Intent(context, IjkCastPlayerActivity::class.java).apply {
            putExtra(IjkCastPlayerActivity.EXTRA_URL, media)
            putExtra(IjkCastPlayerActivity.EXTRA_TITLE, payload.optString("title"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ijkStarted = runCatching { context.startActivity(ijk); true }.getOrDefault(false)
        if (ijkStarted) return true to "已用 ijkplayer 播放"
        val intent = Intent(context, WebCastPlayerActivity::class.java).apply {
            putExtra(WebCastPlayerActivity.EXTRA_URL, page)
            putExtra(WebCastPlayerActivity.EXTRA_STREAM, payload.optString("stream_url"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        return if (started) true to "已打开播放页" else false to "打开播放页失败"
    }
    /** The phone's 唤醒 button: bring 星幕 itself back to the foreground, whatever is playing. */
    private fun bringSelfToFront(): Pair<Boolean, String> {
        val context = appContext ?: return false to "应用上下文缺失"
        val activity = runCatching { Class.forName("app.xingmu.aerial.HomeActivity") }.getOrNull()
            ?: return false to "找不到桌面 Activity"
        val started = runCatching {
            context.startActivity(
                Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            true
        }.getOrDefault(false)
        if (started) return true to "已在电视上唤起星幕"
        // Background startActivity is dropped silently on newer Android; adbd always works.
        val component = context.packageName + "/app.xingmu.aerial.HomeActivity"
        val viaAdb = runCatching { LegacyAdbClient.amStart(context, CastConfig.adbPort, component) }
            .getOrDefault(false)
        return if (viaAdb) true to "已在电视上唤起星幕（ADB）" else false to "唤起失败"
    }
    private fun launchApp(payload: JSONObject): Pair<Boolean, String> {
        val action = payload.optString("action", "launch")
        if (action != "launch") return false to "暂不支持的 app 操作：$action"
        val packageName = payload.optString("package_name", "").trim()
        if (packageName.isEmpty()) return false to "缺少应用包名"
        val context = appContext ?: return false to "应用上下文缺失"
        // ADB first. A background startActivity from the TV's home is silently dropped on Android
        // 10+ (no exception), which made this look successful while nothing appeared.
        if (runCatching { LegacyAdbClient.launchPackage(context, CastConfig.adbPort, packageName) }
                .getOrDefault(false)
        ) {
            return true to "已在电视上打开 $packageName"
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false to "该应用没有可启动入口（ADB 也不可用）"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true to "已在电视上打开 $packageName"
        } catch (error: Exception) {
            // Android 10+ blocks background activity starts; the TV shows Home most of the time,
            // which is exactly when this succeeds, but a vendor ROM may still refuse.
            false to "系统拒绝打开：" + error.javaClass.simpleName
        }
    }

    private fun sendResult(ws: WebSocket, id: Int, ok: Boolean, detail: String) {
        runCatching {
            ws.send(
                JSONObject().put("type", "command_result").put("device_id", CastConfig.deviceId)
                    .put("ts", nowSeconds())
                    .put("data", JSONObject().put("command_id", id)
                        .put("status", if (ok) "completed" else "failed")
                        .put("success", ok)
                        .put("message", detail))
                    .toString()
            )
        }
    }

    /** One JPEG of the TV. Only possible with the TV's own adbd or root; else null and we say so. */
    private fun captureJpeg(): ByteArray? {
        val context = appContext ?: return null
        val bitmap = runCatching { LegacyAdbClient.captureScreen(context, CastConfig.adbPort) }
            .getOrNull() ?: return null
        return runCatching {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            bitmap.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    private fun startPreview(ws: WebSocket, fps: Int) {
        stopPreview()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        previewScheduler = scheduler
        val period = (1000 / fps).toLong().coerceAtLeast(200L)
        scheduler.scheduleWithFixedDelay({
            if (!running.get()) return@scheduleWithFixedDelay
            val jpeg = captureJpeg() ?: return@scheduleWithFixedDelay
            runCatching { ws.send(okio.ByteString.of(*jpeg)) }
        }, 0L, period, TimeUnit.MILLISECONDS)
    }

    private fun stopPreview() {
        previewScheduler?.shutdownNow()
        previewScheduler = null
    }

    private fun bounded(value: Int, minimum: Int, maximum: Int): Int =
        value.coerceIn(minimum, maximum)

    private fun startHeartbeat(ws: WebSocket) {
        heartbeat?.shutdownNow()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        heartbeat = scheduler
        scheduler.scheduleWithFixedDelay({
            if (!running.get()) return@scheduleWithFixedDelay
            runCatching {
                val metadata = JSONObject()
                    .put("udp_p2p_transport", "ready")
                    .put("nat_type", "lan")
                    .put("network_environment", "lan")
                    // The phone shows 遥控/采集 readiness from these fields.
                    .put("control_mode", when {
                        CastA11yService.connected -> "accessibility"
                        adbReady -> "adb"
                        else -> ""
                    })
                    .put("capture_mode", if (adbReady) "adb" else "")
                    // Kept in metadata (not only in the tuple) so the phone can show the OS level and
                    // whether the accessibility service is actually on, instead of "未上报".
                    .put("android_api", android.os.Build.VERSION.SDK_INT)
                    .put("android_release", android.os.Build.VERSION.RELEASE ?: "")
                    .put("accessibility_active", CastA11yService.connected)
                val data = JSONObject()
                    .put("name", CastConfig.deviceName)
                    .put("model", android.os.Build.MODEL ?: "")
                    .put("app_version", BuildConfigName())
                    .put("lan_ip", localIpv4())
                    .put("udp_p2p_transport", "ready")
                    .put("metadata", metadata)
                    .put("current_activity", CastA11yService.foreground.ifEmpty { foregroundPackage })
                val message = JSONObject()
                    .put("type", "heartbeat")
                    .put("device_id", CastConfig.deviceId)
                    .put("ts", nowSeconds())
                    .put("data", data)
                ws.send(message.toString())
            }.onFailure { publish(State.Error, friendly(it)) }
            // Probe adbd twice a minute: it is the fallback control and capture path on TVs without
            // accessibility, so the reported modes have to follow the real state.
            if (nowSeconds() % 30L < HEARTBEAT_SECONDS) {
                captureWorker.execute {
                    val context = appContext ?: return@execute
                    adbReady = runCatching { LegacyAdbClient.probe(context, CastConfig.adbPort) }.getOrDefault(false)
                    foregroundPackage = if (adbReady) {
                        runCatching { LegacyAdbClient.currentPackage(context, CastConfig.adbPort) }.getOrDefault("")
                    } else {
                        ""
                    }
                }
            }
        }, 0L, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
    }

    private fun publish(next: State, detail: String) {
        state = next
        message = detail
        main.post {
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach { runCatching { it(next, detail) } }
        }
    }

    private fun http(): OkHttpClient {
        val existing = client
        if (existing != null) return existing
        val builder = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        // Android 4.4 disables TLS 1.2 by default; without this an https backend is unreachable.
        if (CastConfig.backendUrl.startsWith("https://")) {
            runCatching { Tls12Setup.install(builder) }
                .onFailure { Log.w(TAG, "TLS 1.2 setup failed: " + it.javaClass.simpleName) }
        }
        val built = builder.build()
        client = built
        return built
    }

    private fun wsBase(): String {
        val base = CastConfig.backendUrl
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "ws://" + base
        }
    }

    private fun localIpv4(): String = CastNetwork.localIpv4()

    /** Real versionName from the manifest; the old hardcoded string made the backend show 0.1.0. */
    private fun BuildConfigName(): String = runCatching {
        val ctx = appContext ?: return@runCatching ""
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    /**
     * The phone's "电视应用" page reads `device_apps`, which only this message fills. Sent after every
     * successful auth so a reinstall or a cleared database repairs itself on the next connect.
     */
    private fun reportAppInventory(ws: WebSocket) {
        captureWorker.execute {
            val apps = collectApps()
            val message = JSONObject()
                .put("type", "app_inventory")
                .put("device_id", CastConfig.deviceId)
                .put("ts", nowSeconds())
                .put("data", JSONObject().put("total", apps.length()).put("apps", apps))
            runCatching { ws.send(message.toString()) }
                .onFailure { Log.w(TAG, "app_inventory send failed: " + it.javaClass.simpleName) }
        }
    }

    private fun collectApps(): JSONArray {
        val result = JSONArray()
        val context = appContext ?: return result
        val pm = context.packageManager
        val seen = HashSet<String>()
        val categories = listOf(Intent.CATEGORY_LAUNCHER, "android.intent.category.LEANBACK_LAUNCHER")
        for (category in categories) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())) {
                val packageName = info.activityInfo.packageName
                if (!seen.add(packageName)) continue
                val application = info.activityInfo.applicationInfo
                val entry = JSONObject()
                    .put("package_name", packageName)
                    .put("label", runCatching { pm.getApplicationLabel(application).toString() }.getOrDefault(packageName))
                    .put("system", (application.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                    .put("enabled", application.enabled)
                    .put("launchable", true)
                    .put("first_install_time", 0)
                    .put("last_update_time", 0)
                runCatching {
                    val packageInfo = pm.getPackageInfo(packageName, 0)
                    entry.put("version_name", packageInfo.versionName.orEmpty())
                    entry.put(
                        "version_code",
                        if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
                        else packageInfo.versionCode.toLong(),
                    )
                }
                runCatching {
                    val icon = pm.getApplicationIcon(packageName)
                    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    icon.setBounds(0, 0, 96, 96)
                    icon.draw(canvas)
                    val bytes = java.io.ByteArrayOutputStream().use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                        stream.toByteArray()
                    }
                    // The backend drops icons over 48 KB; a 96px PNG is well under that.
                    if (bytes.isNotEmpty() && bytes.size <= 48 * 1024) {
                        entry.put("icon_mime", "image/png")
                        entry.put("icon_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }
                }
                result.put(entry)
            }
        }
        return result
    }
    private fun friendly(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "找不到后端服务器"
        is java.net.ConnectException -> "连不上后端服务器"
        is java.net.SocketTimeoutException -> "连接超时"
        else -> "网络错误：" + error.javaClass.simpleName
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    private val JSON: MediaType? = MediaType.parse("application/json; charset=utf-8")
}
