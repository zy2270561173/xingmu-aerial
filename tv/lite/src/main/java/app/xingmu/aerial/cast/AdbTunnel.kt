package app.xingmu.aerial.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The outbound half of the adb reverse tunnel.
 *
 * A backend in a data centre cannot reach the TV's 192.168.x.x address, so the server's adb never
 * sees this box and screen mirroring / snapshots stay empty. Here the TV dials the backend's
 * `/ws/tunnel` and holds two idle WebSockets; when adb on the server connects to the local port the
 * backend opened, it sends `{"type":"attach"}` on one of them, and this object connects to
 * `127.0.0.1:<adbPort>` and shuttles bytes between that socket and the WebSocket's binary frames.
 *
 * Android 4.x notes: OkHttp 3 is the project's existing client, binary frames are okio
 * [ByteString] (java.util.Base64 would need API 26), and every socket read is a blocking thread so
 * an idle tunnel costs nothing but a parked thread.
 *
 * One WebSocket serves exactly one adb TCP connection. When the connection ends the backend closes
 * the socket and this class opens a fresh idle tunnel, which keeps the server's `adb connect`
 * working across reconnects.
 */
object AdbTunnel {
    private const val TAG = "AerialCastTunnel"
    /** Two spare tunnels: adb takes one for its transport, the other survives a reconnect. */
    private const val IDLE_TARGET = 2
    private const val MAX_TUNNELS = 4
    private const val RECONNECT_MS = 5_000L
    /** 4401/4403 means credentials or approval are not ready; do not hammer the backend. */
    private const val AUTH_RETRY_MS = 30_000L
    /** Backend has no /ws/tunnel yet (old deployment): retry once a minute, not every 5 seconds. */
    private const val MISSING_ENDPOINT_RETRY_MS = 60_000L
    private const val ADB_CONNECT_TIMEOUT_MS = 3_000
    private const val MAX_WS_QUEUE_BYTES = 1L * 1024 * 1024
    private const val READ_BUFFER_BYTES = 32 * 1024

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val tunnels = mutableListOf<Tunnel>()
    private var running = false
    private var generation = 0
    private var client: OkHttpClient? = null
    private var context: Context? = null

    private val reconnectTask = Runnable {
        synchronize()
    }

    fun start(context: Context) {
        synchronized(lock) {
            this.context = context.applicationContext
            if (running) {
                // Already online: this is a re-auth after a /ws/device reconnect; top up if needed.
                main.post(reconnectTask)
                return
            }
            running = true
            generation++
        }
        Log.i(TAG, "adb tunnel start")
        synchronize()
    }

    fun stop() {
        val closing: List<Tunnel>
        synchronized(lock) {
            if (!running) return
            running = false
            generation++
            closing = tunnels.toList()
            tunnels.clear()
        }
        main.removeCallbacksAndMessages(null)
        closing.forEach { it.shutdown("service stopped") }
        client?.let {
            it.dispatcher().cancelAll()
        }
        Log.i(TAG, "adb tunnel stopped")
    }

    /** The backend found no idle tunnel for an adb connection; open one (or two) more. */
    fun ensureTunnels() {
        synchronized(lock) { if (!running) return }
        synchronize()
    }

    /** Fill the idle pool. Called on start, on tunnel_open, after an attach and after a close. */
    private fun synchronize() {
        val generationNow = synchronized(lock) {
            if (!running) return
            generation
        }
        while (true) {
            val open = synchronized(lock) {
                if (!running || generation != generationNow) return
                val idle = tunnels.count { !it.attached }
                val wanted = (IDLE_TARGET - idle).coerceAtLeast(0)
                val capacity = (MAX_TUNNELS - tunnels.size).coerceAtLeast(0)
                minOf(wanted, capacity)
            }
            if (open <= 0) return
            repeat(open) { openTunnel(generationNow) }
            return
        }
    }

    private fun openTunnel(generationNow: Int) {
        val base = CastConfig.backendUrl
        val token = CastConfig.deviceToken
        if (base.isEmpty() || token.isEmpty()) {
            Log.i(TAG, "skip tunnel: backend or token not ready")
            return
        }
        val tunnel = Tunnel()
        synchronized(lock) {
            if (!running || generation != generationNow || tunnels.size >= MAX_TUNNELS) return
            tunnels.add(tunnel)
        }
        val request = Request.Builder().url(tunnelUrl(base)).build()
        Log.i(TAG, "opening tunnel " + tunnelUrl(base))
        val socket = httpClient().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(tunnel, generationNow)) {
                    webSocket.close(1000, "stale tunnel")
                    return
                }
                tunnel.webSocket = webSocket
                val payload = JSONObject()
                    .put("type", "auth")
                    .put("device_id", CastConfig.deviceId)
                    .put("token", token)
                webSocket.send(payload.toString())
                Log.i(TAG, "tunnel open, auth sent")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(tunnel, generationNow)) return
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (message.optString("type")) {
                    "tunnel_ready" -> Log.i(
                        TAG,
                        "tunnel ready, backend port=" + message.optInt("port", 0)
                    )
                    "attach" -> attach(tunnel, message.optInt("id", 0), webSocket, generationNow)
                    else -> Unit
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrent(tunnel, generationNow)) return
                tunnel.deliver(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(tunnel, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                Log.w(TAG, "tunnel failed: " + error.javaClass.simpleName + " " + error.message)
                val httpCode = response?.code() ?: 0
                response?.close()
                // 403/404 means the backend predates the tunnel endpoint; /ws/device is unaffected.
                finish(tunnel, if (httpCode == 404 || httpCode == 403) 4404 else 0,
                    error.message ?: "")
            }
        })
        tunnel.webSocket = socket
    }

    /**
     * The backend attached an adb TCP connection to this tunnel. Connect to the TV's own adbd
     * synchronously: OkHttp delivers the following binary frames only after this callback returns,
     * so the socket is ready before the first adb CNXN byte arrives.
     */
    private fun attach(tunnel: Tunnel, streamId: Int, webSocket: WebSocket, generationNow: Int) {
        if (!isCurrent(tunnel, generationNow) || tunnel.attached) return
        tunnel.attached = true
        // This tunnel is now busy; keep the pool at two idle so the next adb connect is instant.
        main.post(reconnectTask)
        val port = CastConfig.adbPort
        Log.i(TAG, "attach stream=" + streamId + " -> 127.0.0.1:" + port)
        try {
            val adb = Socket()
            adb.tcpNoDelay = true
            adb.connect(InetSocketAddress("127.0.0.1", port), ADB_CONNECT_TIMEOUT_MS)
            tunnel.attachSocket(adb)
            webSocket.send(
                JSONObject()
                    .put("type", "attached")
                    .put("id", streamId)
                    .put("local_port", port)
                    .toString()
            )
            tunnel.startPumps(webSocket, streamId)
        } catch (error: Exception) {
            Log.w(TAG, "adb 127.0.0.1:" + port + " connect failed: " + error.message)
            runCatching {
                webSocket.send(
                    JSONObject()
                        .put("type", "attach_failed")
                        .put("id", streamId)
                        .put("reason", error.javaClass.simpleName)
                        .toString()
                )
            }
            webSocket.close(1011, "adb connect failed")
        }
    }

    private fun finish(tunnel: Tunnel, code: Int, reason: String) {
        val removed = synchronized(lock) { tunnels.remove(tunnel) }
        if (!removed && tunnel.finished) return
        tunnel.finished = true
        tunnel.shutdown("closed $code $reason")
        Log.i(TAG, "tunnel closed code=" + code + " reason=" + reason)
        if (code == 4401 || code == 4403) {
            // The token/approval is stale; /ws/device owns re-registration, so retry slowly.
            scheduleReconnect(AUTH_RETRY_MS)
        } else if (code == 4404) {
            scheduleReconnect(MISSING_ENDPOINT_RETRY_MS)
        } else {
            scheduleReconnect(RECONNECT_MS)
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        synchronized(lock) { if (!running) return }
        main.removeCallbacks(reconnectTask)
        main.postDelayed(reconnectTask, delayMs)
    }

    private fun isCurrent(tunnel: Tunnel, generationNow: Int): Boolean = synchronized(lock) {
        running && generation == generationNow && tunnels.contains(tunnel) && !tunnel.closed
    }

    private fun tunnelUrl(base: String): String {
        val scheme = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "ws://" + base
        }
        return scheme + "/ws/tunnel"
    }

    private fun httpClient(): OkHttpClient {
        synchronized(lock) { client?.let { return it } }
        val builder = OkHttpClient.Builder()
            // 30s, not CastAgent's 15s: on a 4.4 box a busy screencap loop can starve the reader
            // thread for 15+ seconds, and a false ping timeout would tear down a healthy adb link.
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        // Android 4.4 disables TLS 1.2 by default; same workaround as the /ws/device socket.
        if (CastConfig.backendUrl.startsWith("https://")) {
            runCatching { Tls12Setup.install(builder) }
                .onFailure { Log.w(TAG, "TLS 1.2 setup failed: " + it.javaClass.simpleName) }
        }
        val built = builder.build()
        synchronized(lock) {
            if (client == null) client = built
            return client!!
        }
    }

    /**
     * One WebSocket plus the adbd socket it is currently carrying. `deliver` is called on OkHttp's
     * reader thread; a bounded queue hands the bytes to a writer thread so the reader never blocks
     * on adbd and protocol pings keep flowing.
     */
    private class Tunnel {
        @Volatile var webSocket: WebSocket? = null
        @Volatile var attached = false
        @Volatile var closed = false
        @Volatile var finished = false
        @Volatile private var adb: Socket? = null
        @Volatile private var writerThread: Thread? = null
        @Volatile private var readerThread: Thread? = null
        private val inbound = ArrayBlockingQueue<ByteArray>(64)

        fun attachSocket(socket: Socket) {
            adb = socket
        }

        /** Called from the WebSocket reader thread for every binary frame from the backend. */
        fun deliver(bytes: ByteString) {
            if (closed) return
            val frame = bytes.toByteArray()
            if (frame.isEmpty()) return
            if (!inbound.offer(frame)) {
                // adbd is not reading fast enough; drop the transport instead of growing the heap.
                Log.w(TAG, "adb inbound queue full, closing stream")
                runCatching { adb?.close() }
            }
        }

        fun startPumps(socket: WebSocket, streamId: Int) {
            val adbSocket = adb ?: return
            val output = adbSocket.getOutputStream()
            val input = adbSocket.getInputStream()
            val writer = Thread({
                try {
                    while (!closed) {
                        val frame = inbound.take()
                        if (frame.isEmpty()) continue
                        output.write(frame)
                        output.flush()
                    }
                } catch (error: Exception) {
                    if (!closed) Log.w(TAG, "adb writer stopped: " + error.javaClass.simpleName)
                } finally {
                    closeSocket()
                }
            }, "aerial-adb-tunnel-w")
            val reader = Thread({
                val buffer = ByteArray(READ_BUFFER_BYTES)
                try {
                    while (!closed) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (socket.queueSize() > MAX_WS_QUEUE_BYTES) {
                            // Backend/adb is slower than the TV; let the queue drain a little.
                            try {
                                Thread.sleep(20)
                            } catch (ignored: InterruptedException) {
                                break
                            }
                            if (socket.queueSize() > MAX_WS_QUEUE_BYTES) break
                        }
                        if (!socket.send(ByteString.of(buffer, 0, read))) break
                    }
                } catch (error: Exception) {
                    if (!closed) Log.w(TAG, "adb reader stopped: " + error.javaClass.simpleName)
                } finally {
                    // Tell the backend this stream is over, then close the tunnel; the next one is
                    // opened by synchronize() and starts idle again.
                    runCatching {
                        socket.send(
                            JSONObject().put("type", "detach").put("id", streamId).toString()
                        )
                    }
                    runCatching { socket.close(1000, "adb eof") }
                }
            }, "aerial-adb-tunnel-r")
            writerThread = writer
            readerThread = reader
            writer.start()
            reader.start()
        }

        fun closeSocket() {
            val socket = adb ?: return
            adb = null
            runCatching { socket.close() }
        }

        fun shutdown(reason: String) {
            if (closed) return
            closed = true
            writerThread?.interrupt()
            writerThread = null
            readerThread?.interrupt()
            readerThread = null
            inbound.clear()
            closeSocket()
            val socket = webSocket
            webSocket = null
            runCatching { socket?.close(1000, reason.take(80)) }
        }
    }
}
