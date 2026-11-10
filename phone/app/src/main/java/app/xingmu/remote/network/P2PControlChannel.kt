package app.xingmu.remote.network

import android.util.Base64
import app.xingmu.remote.data.P2PSession
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class P2PControlChannel(private val listener: Listener) {
    interface Listener {
        fun onState(connected: Boolean)
        fun onJpeg(bytes: ByteArray)
        fun onKeyResult(key: String, acknowledged: Boolean, success: Boolean, message: String)
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val retryWorker = Executors.newSingleThreadScheduledExecutor()
    private val nextControlId = AtomicLong(System.currentTimeMillis())
    @Volatile private var running = false
    @Volatile private var peer: InetSocketAddress? = null
    @Volatile private var generation = 0L
    @Volatile private var streaming = true
    @Volatile private var supportsKeyAck = false
    @Volatile private var connected = false
    private var socket: DatagramSocket? = null
    private lateinit var session: P2PSession
    @Volatile private var lastServerSeenAt = 0L
    @Volatile private var lastPeerSeenAt = 0L
    @Volatile private var rendezvousExpired = false
    private val frames = LinkedHashMap<Int, Frame>()
    private val pendingKeys = ConcurrentHashMap<Long, PendingKey>()

    fun start(value: P2PSession) {
        stop(notify = false)
        session = value
        rendezvousExpired = false
        lastPeerSeenAt = 0L
        lastServerSeenAt = System.currentTimeMillis()
        running = true
        val activeGeneration = ++generation
        worker.execute { loop(activeGeneration) }
    }

    fun close() {
        stop(notify = true)
        worker.shutdownNow()
        retryWorker.shutdownNow()
    }

    private fun stop(notify: Boolean) {
        if (running && ::session.isInitialized) sendDirect(JSONObject().put("type", "stream_stop"))
        generation++
        running = false
        peer = null
        supportsKeyAck = false
        connected = false
        rendezvousExpired = false
        lastPeerSeenAt = 0L
        socket?.close()
        socket = null
        frames.clear()
        pendingKeys.clear()
        if (notify) listener.onState(false)
    }

    fun sendKey(key: String): Boolean {
        if (!running || peer == null) return false
        if (!supportsKeyAck) {
            return sendDirect(JSONObject().put("type", "key").put("key", key))
        }
        val id = nextControlId.incrementAndGet()
        pendingKeys[id] = PendingKey(
            key = key,
            payload = JSONObject().put("type", "key").put("id", id).put("key", key),
        )
        attemptKey(id)
        return true
    }
    fun requestSnapshot(): Boolean = sendDirect(JSONObject().put("type", "snapshot_request"))
    fun setStreaming(active: Boolean): Boolean {
        streaming = active
        return sendDirect(JSONObject().put("type", if (active) "stream_start" else "stream_stop")
            .put("fps", 15).put("width", 1280).put("height", 720).put("quality", 55))
    }

    private fun loop(activeGeneration: Long) {
        var activeSocket: DatagramSocket? = null
        try {
            activeSocket = DatagramSocket().apply { soTimeout = 900 }
            if (!running || generation != activeGeneration) {
                activeSocket.close()
                return
            }
            socket = activeSocket
            val rendezvous = InetSocketAddress(session.rendezvousHost, session.rendezvousPort)
            var nextHello = 0L
            var nextStreamSignal = 0L
            var nextCapabilitySignal = 0L
            var nextServerKeepalive = 0L
            var nextPeerPing = 0L
            val buffer = ByteArray(4096)
            while (running && generation == activeGeneration) {
                val now = System.currentTimeMillis()
                if (peer == null) {
                    if (rendezvousExpired) break
                    if (now - lastServerSeenAt > SERVER_SILENCE_MS) break
                    if (now >= nextHello) {
                        send(JSONObject().put("type", "hello").put("session_id", session.sessionId)
                            .put("role", "control").put("token", session.token).toString().toByteArray(), rendezvous)
                        nextHello = now + HELLO_INTERVAL_MS
                    }
                } else if (now - lastPeerSeenAt > PEER_SILENCE_MS) {
                    // Path lost (NAT rebind / TV restart): fall back to knocking on the rendezvous.
                    peer = null
                    supportsKeyAck = false
                    nextHello = 0L
                    nextStreamSignal = 0L
                    nextCapabilitySignal = 0L
                } else {
                    if (now >= nextServerKeepalive) {
                        send(JSONObject().put("type", "keepalive").put("session_id", session.sessionId)
                            .put("role", "control").put("token", session.token).toString().toByteArray(), rendezvous)
                        nextServerKeepalive = now + SERVER_KEEPALIVE_MS
                    }
                    if (streaming && now >= nextStreamSignal) {
                        setStreaming(true)
                        nextStreamSignal = now + 1000
                    }
                    if (!supportsKeyAck && now >= nextCapabilitySignal) {
                        sendDirect(JSONObject().put("type", "ready").put("protocol", 2).put("control_ack", true))
                        nextCapabilitySignal = now + 500
                    }
                    if (now >= nextPeerPing) {
                        sendDirect(JSONObject().put("type", "ping"))
                        nextPeerPing = now + PEER_PING_MS
                    }
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)
                    handle(packet.data.copyOf(packet.length), InetSocketAddress(packet.address, packet.port))
                } catch (_: java.net.SocketTimeoutException) { }
            }
        } catch (_: Exception) {
        } finally {
            activeSocket?.close()
            if (generation == activeGeneration) {
                running = false
                peer = null
                if (socket === activeSocket) socket = null
                listener.onState(false)
            }
        }
    }

    private fun handle(data: ByteArray, source: InetSocketAddress) {
        runCatching {
            if (data.firstOrNull() == '{'.code.toByte()) {
                val json = JSONObject(data.toString(Charsets.UTF_8))
                val kind = json.optString("type")
                if (source == rendezvousAddress()) lastServerSeenAt = System.currentTimeMillis()
                if (kind == "alive") return
                if (kind == "expired") {
                    rendezvousExpired = true
                    return
                }
                if (kind == "peer") {
                    if (source != rendezvousAddress()) return
                    peer = InetSocketAddress(json.getString("host"), json.getInt("port"))
                    lastPeerSeenAt = System.currentTimeMillis()
                    sendDirect(JSONObject().put("type", "ready"))
                    if (streaming) setStreaming(true)
                    return
                }
                if (kind != "direct") return
                val payload = Base64.decode(json.getString("payload"), Base64.NO_WRAP)
                val mac = Base64.decode(json.getString("mac"), Base64.NO_WRAP)
                if (!mac.contentEquals(hmac(payload))) return
                if (source != peer) peer = source
                markConnected()
                lastPeerSeenAt = System.currentTimeMillis()
                val message = JSONObject(payload.toString(Charsets.UTF_8))
                if (message.optString("type") == "ping") {
                    sendDirect(JSONObject().put("type", "pong"))
                } else if (message.optString("type") == "ready") {
                    supportsKeyAck = message.optBoolean("control_ack", false)
                } else if (message.optString("type") == "key_received") {
                    pendingKeys[message.optLong("id")]?.acknowledged = true
                } else if (message.optString("type") == "key_ack") {
                    val pending = pendingKeys.remove(message.optLong("id")) ?: return
                    listener.onKeyResult(
                        pending.key,
                        true,
                        message.optBoolean("success"),
                        message.optString("message", if (message.optBoolean("success")) "电视已执行" else "电视执行失败"),
                    )
                }
                return
            }
            if (data.size < 44 || !data.copyOfRange(0, 4).contentEquals(byteArrayOf('S'.code.toByte(), 'T'.code.toByte(), 'J'.code.toByte(), '1'.code.toByte()))) return
            val unsigned = data.copyOfRange(0, data.size - 32)
            if (!data.copyOfRange(data.size - 32, data.size).contentEquals(hmac(unsigned))) return
            if (source != peer) peer = source
            markConnected()
            val header = ByteBuffer.wrap(unsigned)
            header.position(4)
            val id = header.int
            val index = header.short.toInt() and 0xffff
            val count = header.short.toInt() and 0xffff
            if (count !in 1..2048 || index >= count) return
            val chunk = ByteArray(unsigned.size - 12)
            header.get(chunk)
            val frame = frames.getOrPut(id) { Frame(count, arrayOfNulls(count), System.currentTimeMillis()) }
            if (frame.count != count || frame.size + chunk.size > 2 * 1024 * 1024) { frames.remove(id); return }
            if (frame.parts[index] == null) { frame.parts[index] = chunk; frame.size += chunk.size }
            frames.entries.removeAll { System.currentTimeMillis() - it.value.createdAt > 2500 || it.key < id - 3 }
            if (frame.parts.all { it != null }) {
                val jpeg = ByteArray(frame.size); var offset = 0
                frame.parts.forEach { part -> part!!.copyInto(jpeg, offset); offset += part.size }
                frames.clear()
                listener.onJpeg(jpeg)
            }
        }
    }

    private fun attemptKey(id: Long) {
        val pending = pendingKeys[id] ?: return
        if (!running || peer == null) {
            pendingKeys.remove(id)
            listener.onKeyResult(pending.key, pending.acknowledged, false, "P2P 直连已断开")
            return
        }
        if (pending.attempts >= KEY_ATTEMPTS) {
            pendingKeys.remove(id)
            listener.onKeyResult(
                pending.key,
                pending.acknowledged,
                false,
                if (pending.acknowledged) "电视已收到按键，但执行结果超时" else "P2P 遥控未收到电视确认",
            )
            return
        }
        pending.attempts++
        sendDirect(pending.payload)
        retryWorker.schedule({ attemptKey(id) }, KEY_RETRY_MS, TimeUnit.MILLISECONDS)
    }

    private fun markConnected() {
        if (!connected) {
            connected = true
            listener.onState(true)
        }
    }

    private fun sendDirect(payload: JSONObject): Boolean {
        val target = peer ?: return false
        return runCatching {
            val raw = payload.toString().toByteArray()
            val envelope = JSONObject().put("type", "direct")
                .put("payload", Base64.encodeToString(raw, Base64.NO_WRAP))
                .put("mac", Base64.encodeToString(hmac(raw), Base64.NO_WRAP))
            send(envelope.toString().toByteArray(), target)
            true
        }.getOrDefault(false)
    }

    private fun hmac(value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(session.channelKey.toByteArray(), "HmacSHA256")); doFinal(value)
    }

    private fun send(data: ByteArray, target: InetSocketAddress) {
        socket?.send(DatagramPacket(data, data.size, target))
    }

    private fun rendezvousAddress() = InetSocketAddress(session.rendezvousHost, session.rendezvousPort)

    private data class Frame(val count: Int, val parts: Array<ByteArray?>, val createdAt: Long, var size: Int = 0)
    private data class PendingKey(
        val key: String,
        val payload: JSONObject,
        @Volatile var attempts: Int = 0,
        @Volatile var acknowledged: Boolean = false,
    )

    private companion object {
        const val KEY_ATTEMPTS = 12
        const val KEY_RETRY_MS = 250L
        const val HELLO_INTERVAL_MS = 1_000L
        const val SERVER_KEEPALIVE_MS = 15_000L
        const val PEER_PING_MS = 10_000L
        const val PEER_SILENCE_MS = 45_000L
        const val SERVER_SILENCE_MS = 90_000L
    }
}
