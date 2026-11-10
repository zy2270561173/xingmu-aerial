package app.xingmu.aerial.cast

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The P2P side of the phone remote: a UDP socket to the rendezvous, hole punched to the phone,
 * then keys travel phone → TV directly instead of through the backend.
 *
 * Speaks the same envelope as the full tv-app client: JSON `direct` messages with an HMAC-SHA256
 * over the payload, so the backend never has to see key presses.
 */
class P2pChannel(private val context: Context) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null
    private var peer: InetSocketAddress? = null

    private var sessionId = ""
    private var token = ""
    private var key = ByteArray(0)
    private var rendezvous: InetSocketAddress? = null
    private var expiresAt = 0L

    fun start(sessionId: String, token: String, channelKey: String, host: String, port: Int, expiresAt: Long) {
        stop()
        if (host.isEmpty() || port <= 0 || channelKey.isEmpty()) return
        this.sessionId = sessionId
        this.token = token
        this.key = channelKey.toByteArray(Charset.forName("UTF-8"))
        this.rendezvous = InetSocketAddress(host, port)
        this.expiresAt = if (expiresAt > 0) expiresAt else System.currentTimeMillis() / 1000L + 120L
        running.set(true)
        Log.i(TAG, "start host=" + host + " port=" + port + " sid=" + sessionId + " expires=" + expiresAt)
        val worker = Thread({ loop() }, "aerial-cast-p2p")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    fun stop() {
        running.set(false)
        val worker = thread
        thread = null
        runCatching { socket?.close() }
        worker?.interrupt()
        // Wait for the loop to unwind before a restart reuses the field: the old loop's finally
        // would otherwise clear the socket the new loop just installed.
        runCatching { worker?.join(400) }
        socket = null
        peer = null
    }

    private fun loop() {
        var active: DatagramSocket? = null
        try {
            active = DatagramSocket()
            socket = active
            active.soTimeout = 900
            val buffer = ByteArray(4096)
            var nextHello = 0L
            var nextkeepalive = 0L
            var nextPing = 0L
            var lastPeer = 0L
            while (running.get() && System.currentTimeMillis() / 1000L < expiresAt + 30) {
                val now = System.currentTimeMillis()
                val current = peer
                if (current == null) {
                    if (now >= nextHello) {
                        val hello = JSONObject().put("type", "hello").put("session_id", sessionId)
                            .put("role", "device").put("token", token)
                        runCatching {
                            val bytes = hello.toString().toByteArray()
                            active.send(DatagramPacket(bytes, bytes.size, rendezvous?.address, rendezvous?.port ?: 0))
                        }.onSuccess { Log.i(TAG, "hello sent") }
                            .onFailure { Log.w(TAG, "hello failed: " + it.javaClass.simpleName + " / " + it.message + " addr=" + rendezvous?.address) }
                        nextHello = now + 1000
                    }
                } else {
                    if (now - lastPeer > PEER_SILENCE_MS) {
                        peer = null
                        nextHello = 0
                    } else {
                        if (now >= nextkeepalive) {
                            val keepalive = JSONObject().put("type", "keepalive").put("session_id", sessionId)
                                .put("role", "device").put("token", token)
                            runCatching {
                                active.send(DatagramPacket(keepalive.toString().toByteArray(),
                                    keepalive.toString().toByteArray().size, rendezvous?.address, rendezvous?.port ?: 0))
                            }
                            nextkeepalive = now + KEEPALIVE_MS
                        }
                        if (now >= nextPing) {
                            runCatching { sendDirect(JSONObject().put("type", "ping")) }
                            nextPing = now + PING_MS
                        }
                    }
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    active.receive(packet)
                    val from = InetSocketAddress(packet.address, packet.port)
                    val text = String(packet.data, 0, packet.length, Charset.forName("UTF-8"))
                    if (!text.startsWith("{")) continue
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "peer" -> {
                            if (from != rendezvous) continue
                            peer = InetSocketAddress(json.getString("host"), json.getInt("port"))
                            lastPeer = System.currentTimeMillis()
                            sendDirect(JSONObject().put("type", "ready"))
                            Log.i(TAG, "P2P peer established")
                        }
                        "direct" -> {
                            val payload = Base64.decode(json.getString("payload"), Base64.NO_WRAP)
                            val mac = Base64.decode(json.getString("mac"), Base64.NO_WRAP)
                            if (!constantTime(mac, hmac(payload))) continue
                            if (from != peer) peer = from
                            lastPeer = System.currentTimeMillis()
                            handleDirect(payload)
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "P2P loop: " + error.javaClass.simpleName + " / " + error.message)
        } finally {
            runCatching { active?.close() }
            // Only clear the field if it still points at this loop's socket: a restart starts the
            // new loop before the old one unwinds, and nulling unconditionally killed the new socket
            // (the 4.4 box then logged "P2P loop: SocketException" and never joined the rendezvous).
            if (socket === active) socket = null
        }
    }

    private fun handleDirect(payload: ByteArray) {
        val message = JSONObject(String(payload, Charset.forName("UTF-8")))
        when (message.optString("type")) {
            "ping" -> sendDirect(JSONObject().put("type", "pong"))
            "key" -> {
                val id = message.optLong("id", 0L)
                val name = message.optString("key", "").trim().lowercase()
                val code = KeyExecutor.keyCodeFor(name)
                val (ok, detail) = if (code > 0) {
                    KeyExecutor.press(context, name, code, name)
                } else {
                    false to "未知按键：$name"
                }
                sendDirect(
                    JSONObject().put("type", "key_ack").put("id", id).put("key", name)
                        .put("success", ok).put("message", detail)
                )
            }
        }
    }

    private fun sendDirect(payload: JSONObject) {
        val target = peer ?: return
        val raw = payload.toString().toByteArray()
        val envelope = JSONObject().put("type", "direct")
            .put("payload", Base64.encodeToString(raw, Base64.NO_WRAP))
            .put("mac", Base64.encodeToString(hmac(raw), Base64.NO_WRAP))
        val bytes = envelope.toString().toByteArray()
        runCatching {
            socket?.send(DatagramPacket(bytes, bytes.size, target.address, target.port))
        }
    }

    private fun hmac(value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

    private fun constantTime(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var diff = 0
        for (index in left.indices) diff = diff or (left[index].toInt() xor right[index].toInt())
        return diff == 0
    }

    companion object {
        private const val TAG = "AerialCastP2P"
        private const val KEEPALIVE_MS = 15_000L
        private const val PING_MS = 10_000L
        private const val PEER_SILENCE_MS = 45_000L
    }
}
