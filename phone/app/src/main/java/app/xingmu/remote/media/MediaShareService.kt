package app.xingmu.remote.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.xingmu.remote.R
import app.xingmu.remote.data.SharedMedia
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MediaShareService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        val television = intent?.getStringExtra(EXTRA_TV_NAME).orEmpty().ifBlank { "电视" }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("正在向 $television 投屏")
            .setContentText("手机正在提供 $count 个本地文件")
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: RuntimeException) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MediaShareManager.stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "本地投屏", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "local_media_share"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_TV_NAME = "tv_name"

        fun show(context: Context, count: Int, tvName: String) {
            val intent = Intent(context, MediaShareService::class.java)
                .putExtra(EXTRA_COUNT, count)
                .putExtra(EXTRA_TV_NAME, tvName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaShareService::class.java))
        }
    }
}

object MediaShareManager {
    private var server: LocalMediaServer? = null

    @Synchronized
    fun share(
        context: Context,
        uris: List<Uri>,
        allowedTvIps: Set<String>,
        tvLabel: String,
    ): List<SharedMedia> {
        require(uris.isNotEmpty()) { "请先选择文件" }
        val validIps = allowedTvIps.mapNotNull {
            runCatching { InetAddress.getByName(it.trim()) }.getOrNull() as? Inet4Address
        }.filter { it.isSiteLocalAddress }.toSet()
        require(validIps.isNotEmpty()) { "电视没有上报局域网地址，无法读取手机本地文件" }
        val localIp = findLocalAddress(validIps)
            ?: throw IOException("手机和电视不在可互访的同一局域网")
        val entries = uris.map { describe(context.contentResolver, it) }
        server?.close()
        val replacement = LocalMediaServer(context.contentResolver, entries, validIps)
        try {
            server = replacement
            replacement.start()
            val urls = entries.mapIndexed { index, media ->
                media.copy(url = "http://${localIp.hostAddress}:${replacement.port}${replacement.path(index)}")
            }
            MediaShareService.show(context, urls.size, tvLabel)
            return urls
        } catch (error: Throwable) {
            replacement.close()
            if (server === replacement) server = null
            throw error
        }
    }

    @Synchronized
    fun stop(context: Context) {
        stopServer()
        MediaShareService.stop(context)
    }

    @Synchronized
    internal fun stopServer() {
        server?.close()
        server = null
    }

    private fun describe(resolver: ContentResolver, uri: Uri): SharedMedia {
        var name = uri.lastPathSegment ?: "media"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    size = if (cursor.isNull(1)) -1 else cursor.getLong(1)
                }
            }
        if (size < 0) {
            resolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        }
        require(size >= 0) { "无法读取文件大小：$name" }
        val reportedMime = resolver.getType(uri).orEmpty()
        val mime = reportedMime.takeIf { it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) }
            ?: guessMime(name)
        return SharedMedia(uri, name, mime, size)
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    private fun findLocalAddress(targets: Set<Inet4Address>): Inet4Address? {
        val candidates = mutableListOf<Inet4Address>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (network in Collections.list(interfaces)) {
            if (!runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)) continue
            for (address in Collections.list(network.inetAddresses)) {
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    candidates += address
                }
            }
        }
        return candidates.firstOrNull { local -> targets.any { sameSubnet24(local, it) } }
            ?: candidates.firstOrNull()
    }

    private fun sameSubnet24(left: Inet4Address, right: Inet4Address): Boolean =
        left.address.take(3) == right.address.take(3)
}

private class LocalMediaServer(
    private val resolver: ContentResolver,
    private val media: List<SharedMedia>,
    allowedIps: Set<Inet4Address>,
) : Closeable {
    private val sessionId = UUID.randomUUID().toString().replace("-", "")
    private val allowedAddresses = allowedIps.map { it.hostAddress }.toSet()
    private val serverSocket = ServerSocket(0)
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val requestExecutor = Executors.newFixedThreadPool(4)
    val port: Int get() = serverSocket.localPort

    fun path(index: Int) = "/media/$sessionId/$index"

    fun start() {
        running.set(true)
        acceptExecutor.execute {
            while (running.get()) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching {
                    requestExecutor.execute { runCatching { handle(socket) } }
                }.onFailure { runCatching { socket.close() } }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val remote = client.inetAddress.hostAddress.orEmpty()
            if (remote !in allowedAddresses) return
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val header = readHeaders(input) ?: return
            val lines = header.split("\r\n")
            val request = lines.firstOrNull()?.split(' ') ?: return
            val method = request.getOrNull(0).orEmpty().uppercase(Locale.US)
            val path = runCatching { URI(request.getOrNull(1).orEmpty()).path }.getOrNull() ?: return
            if (method != "GET" && method != "HEAD") {
                writeStatus(output, 405, "Method Not Allowed", "text/plain", 0)
                return
            }
            val index = path.removePrefix("/media/$sessionId/").takeIf { path.startsWith("/media/$sessionId/") }
                ?.toIntOrNull()
            val selected = index?.let { media.getOrNull(it) }
            if (selected == null) {
                writeStatus(output, 404, "Not Found", "text/plain", 0)
                return
            }
            val rangeValue = lines.drop(1).firstOrNull { it.startsWith("Range:", true) }
                ?.substringAfter(':')?.trim()
            val range = try {
                RangeHeader.parse(rangeValue, selected.size)
            } catch (_: IllegalArgumentException) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${selected.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            val start = range?.start ?: 0
            val length = range?.length ?: selected.size
            output.write(
                buildString {
                    append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: ${selected.mimeType}\r\n")
                    append("Content-Length: $length\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Cache-Control: no-store\r\n")
                    if (range != null) append("Content-Range: bytes ${range.start}-${range.endInclusive}/${selected.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(),
            )
            if (method == "GET") {
                resolver.openInputStream(selected.uri)?.use { stream ->
                    skipFully(stream, start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            output.flush()
        }
    }

    private fun readHeaders(input: BufferedInputStream): String? {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < 16 * 1024) {
            val value = input.read()
            if (value < 0) return null
            output.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return output.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    private fun writeStatus(output: BufferedOutputStream, code: Int, text: String, type: String, length: Long) {
        output.write("HTTP/1.1 $code $text\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    private fun skipFully(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining--
            } else {
                throw IOException("文件读取提前结束")
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        acceptExecutor.shutdownNow()
        requestExecutor.shutdownNow()
    }
}
