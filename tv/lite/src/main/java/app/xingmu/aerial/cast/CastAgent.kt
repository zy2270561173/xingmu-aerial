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
            "preview_start" -> startPreview(ws, bounded(json.optInt("fps", 1), 1, 3))
            "preview_stop" -> stopPreview()
            "error" -> Log.w(TAG, "backend error: " + json.optString("code"))
        }
    }

    /** One remote command. Everything the launcher cannot really do is reported as failed. */
    private fun executeCommand(type: String, payload: JSONObject): Pair<Boolean, String> = when (type) {
        "app" -> launchApp(payload)
        "key" -> sendKey(payload)
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
        if (code <= 0) code = keyCodeFor(name)
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

    private fun startHeartbeat(ws: WebSocket) {        heartbeat?.shutdownNow()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        heartbeat = scheduler
        scheduler.scheduleWithFixedDelay({
            if (!running.get()) return@scheduleWithFixedDelay
            runCatching {
                ws.send(
                    JSONObject().put("type", "heartbeat")
                        .put("device_id", CastConfig.deviceId)
                        .put("ts", nowSeconds())
                        .put("data", JSONObject()
                            .put("name", CastConfig.deviceName)
                            .put("model", android.os.Build.MODEL ?: "")
                            .put("app_version", BuildConfigName())
                            .put("lan_ip", localIpv4())
                            .put("udp_p2p_transport", "ready")
                            .put("metadata", JSONObject().put("udp_p2p_transport", "ready").put("nat_type", "lan")))
                        .toString()
                )
            }.onFailure { publish(State.Error, friendly(it)) }
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

    private fun BuildConfigName(): String = "0.1.0-lite-cast"

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
