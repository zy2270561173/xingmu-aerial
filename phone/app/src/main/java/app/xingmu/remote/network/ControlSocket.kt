package app.xingmu.remote.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class ControlSocket(
    client: OkHttpClient,
    url: String,
    private val token: String,
    private val listener: Listener,
) : WebSocketListener() {
    interface Listener {
        fun onState(connected: Boolean, message: String)
        fun onMessage(message: JSONObject)
        fun onJpeg(bytes: ByteArray)
        fun onUnauthorized()
    }

    private val authenticated = AtomicBoolean(false)
    private val socket = client.newWebSocket(Request.Builder().url(url).build(), this)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") == "authenticated") {
            authenticated.set(true)
            listener.onState(true, "实时控制已连接")
        }
        listener.onMessage(message)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val value = bytes.toByteArray()
        if (value.size >= 4 && value[0] == 0xff.toByte() && value[1] == 0xd8.toByte()) {
            listener.onJpeg(value)
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        authenticated.set(false)
        if (code == 4401) listener.onUnauthorized()
        listener.onState(false, "实时控制已断开")
    }

    override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
        authenticated.set(false)
        if (response?.code == 401) listener.onUnauthorized()
        listener.onState(false, "实时控制连接失败")
    }

    fun requestSnapshot(): Boolean = authenticated.get() && socket.send(
        JSONObject().put("type", "snapshot_request").toString(),
    )

    fun startPreview(fps: Int = 10): Boolean = authenticated.get() && socket.send(
        JSONObject().put("type", "preview_start").put("fps", fps.coerceIn(1, 15)).toString(),
    )

    fun stopPreview(): Boolean = authenticated.get() && socket.send(
        JSONObject().put("type", "preview_stop").toString(),
    )

    fun ping(): Boolean = authenticated.get() && socket.send(JSONObject().put("type", "ping").toString())

    fun close() {
        socket.close(1000, "screen changed")
    }
}
