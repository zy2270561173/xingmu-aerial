package app.xingmu.aerial.cast

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Android 4.4 ships with TLS 1.2 available but disabled by default, so an HTTPS backend fails the
 * handshake on an old TV with "SSL handshake aborted". This factory forces TLS 1.2 on every socket
 * OkHttp creates; it does not relax certificate checking.
 */
class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    private fun enable(socket: Socket): Socket {
        if (socket is SSLSocket) {
            socket.enabledProtocols = arrayOf("TLSv1.2")
        }
        return socket
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        enable(delegate.createSocket(socket, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket = enable(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        enable(delegate.createSocket(host, port, localHost, localPort))

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress, port: Int): Socket = enable(delegate.createSocket(host, port))

    @Throws(IOException::class)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        enable(delegate.createSocket(address, port, localAddress, localPort))
}
