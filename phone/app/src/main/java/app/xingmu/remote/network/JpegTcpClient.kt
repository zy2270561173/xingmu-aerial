package app.xingmu.remote.network

import app.xingmu.remote.data.P2PSession
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class JpegTcpClient(private val listener: Listener) {
    interface Listener {
        fun onState(connected: Boolean)
        fun onJpeg(bytes: ByteArray)
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null

    fun start(session: P2PSession) {
        if (session.lanStreamHost.isBlank() || session.lanStreamToken.isBlank()) return
        closeSocket()
        running.set(true)
        worker.execute { receive(session) }
    }

    private fun receive(session: P2PSession) {
        var announced = false
        while (running.get() && System.currentTimeMillis() / 1000 < session.expiresAt) {
            try {
                val active = Socket()
                socket = active
                active.tcpNoDelay = true
                active.keepAlive = true
                active.connect(InetSocketAddress(session.lanStreamHost, session.lanStreamPort), 1200)
                val output = BufferedOutputStream(active.getOutputStream())
                output.write("STARJPEG/1 ${session.lanStreamToken}\n".toByteArray(Charsets.UTF_8))
                output.flush()
                val input = DataInputStream(BufferedInputStream(active.getInputStream(), 256 * 1024))
                while (running.get()) {
                    if (input.readInt() != MAGIC) throw IllegalStateException("invalid stream")
                    val size = input.readInt()
                    if (size !in 4..MAX_JPEG_BYTES) throw IllegalStateException("invalid frame")
                    val jpeg = ByteArray(size)
                    input.readFully(jpeg)
                    if (!announced) {
                        announced = true
                        listener.onState(true)
                    }
                    if (jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte()) listener.onJpeg(jpeg)
                }
            } catch (_: Exception) {
            } finally {
                closeSocket()
            }
            if (running.get()) {
                try {
                    Thread.sleep(350)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        if (announced) listener.onState(false)
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    fun close() {
        running.set(false)
        closeSocket()
        worker.shutdownNow()
    }

    private companion object {
        const val MAGIC = 0x4A504547
        const val MAX_JPEG_BYTES = 2 * 1024 * 1024
    }
}
