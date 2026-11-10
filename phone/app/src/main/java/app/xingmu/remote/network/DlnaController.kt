package app.xingmu.remote.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

data class DlnaDevice(
    val udn: String,
    val name: String,
    val host: String,
    val location: String,
    val avTransportUrl: String,
    val remoteControlUrl: String = "",
)

data class LanRemoteResult(
    val supported: Boolean,
    val success: Boolean,
    val message: String,
)

class DlnaController(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
    private val discoveryMutex = Mutex()
    @Volatile private var cachedDevices: List<DlnaDevice> = emptyList()
    @Volatile private var lastDiscoveryAt = 0L
    private val lastSeenAt = LinkedHashMap<String, Long>()

    suspend fun discover(force: Boolean = true): List<DlnaDevice> = discoveryMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastDiscoveryAt < CACHE_MS) return@withLock cachedDevices
        val locations = withContext(Dispatchers.IO) { findLocations() }
        val resolved = withContext(Dispatchers.IO) {
            locations.mapNotNull { location -> runCatching { readDescription(location) }.getOrNull() }
                .distinctBy { it.udn.ifBlank { it.location } }
        }
        val completedAt = SystemClock.elapsedRealtime()
        resolved.forEach { lastSeenAt[deviceKey(it)] = completedAt }

        // SSDP is UDP. A refresh can legitimately miss one response, so merge a
        // recent device back into the result instead of making it disappear.
        val merged = LinkedHashMap<String, DlnaDevice>()
        resolved.forEach { merged[deviceKey(it)] = it }
        cachedDevices.forEach { device ->
            val key = deviceKey(device)
            val seenAt = lastSeenAt[key] ?: 0L
            if (completedAt - seenAt <= DEVICE_GRACE_MS && key !in merged) {
                merged[key] = device
            }
        }
        lastSeenAt.entries.removeAll { completedAt - it.value > DEVICE_GRACE_MS }
        cachedDevices = merged.values.toList()
        lastDiscoveryAt = completedAt
        cachedDevices
    }

    suspend fun playUrl(host: String, url: String, mimeType: String): Boolean {
        val device = find(host) ?: return false
        val metadata = didl(url, mimeType)
        withContext(Dispatchers.IO) {
            soap(
                device,
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${xml(url)}</CurrentURI>" +
                    "<CurrentURIMetaData>${xml(metadata)}</CurrentURIMetaData>",
            )
            soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        }
        return true
    }

    suspend fun transport(host: String, action: String): Boolean {
        val device = find(host) ?: return false
        withContext(Dispatchers.IO) {
            when (action) {
                "play" -> soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                "pause" -> soap(device, "Pause", "<InstanceID>0</InstanceID>")
                "stop" -> soap(device, "Stop", "<InstanceID>0</InstanceID>")
                else -> return@withContext
            }
        }
        if (action !in setOf("play", "pause", "stop")) return false
        return true
    }

    suspend fun sendRemoteKey(host: String, key: String): LanRemoteResult {
        val device = find(host) ?: return LanRemoteResult(false, false, "局域网电视已离线，请重新搜索")
        if (device.remoteControlUrl.isBlank()) {
            return LanRemoteResult(false, false, "该设备只提供 DLNA 播放，不支持星投屏局域网方向键遥控")
        }
        return withContext(Dispatchers.IO) {
            val body = JSONObject().put("key", key).toString().toRequestBody(JSON)
            val request = Request.Builder().url(device.remoteControlUrl)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext LanRemoteResult(true, false, "电视拒绝局域网遥控（HTTP ${response.code}）")
                }
                val result = runCatching { JSONObject(text) }.getOrNull()
                val success = result?.optBoolean("success", false) == true
                LanRemoteResult(
                    supported = true,
                    success = success,
                    message = result?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: if (success) "电视已执行" else "电视未能执行按键",
                )
            }
        }
    }

    fun cachedHosts(): Set<String> = cachedDevices.map { it.host }.toSet()

    private suspend fun find(host: String): DlnaDevice? {
        cachedDevices.firstOrNull { it.host == host }?.let { return it }
        return discover(force = true).firstOrNull { it.host == host }
    }

    private fun findLocations(): Set<String> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("starlight-dlna-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                socket.broadcast = true
                val target = InetAddress.getByName("239.255.255.250")
                repeat(2) {
                    SEARCH_TARGETS.forEach { searchTarget ->
                        val request = (
                            "M-SEARCH * HTTP/1.1\r\n" +
                                "HOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ssdp:discover\"\r\n" +
                                "MX: 2\r\n" +
                                "ST: $searchTarget\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                        socket.send(DatagramPacket(request, request.size, target, 1900))
                    }
                }
                val found = linkedSetOf<String>()
                val deadline = SystemClock.elapsedRealtime() + DISCOVERY_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    val buffer = ByteArray(8 * 1024)
                    val response = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(response)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(response.data, response.offset, response.length, Charsets.ISO_8859_1)
                    val location = text.lineSequence()
                        .firstOrNull { it.substringBefore(':').trim().equals("location", true) }
                        ?.substringAfter(':')?.trim()
                    if (!location.isNullOrBlank() && location.startsWith("http", true)) found += location
                }
                return found
            }
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun readDescription(location: String): DlnaDevice {
        val response = client.newCall(Request.Builder().url(location).get().build()).execute()
        val document = response.use {
            if (!it.isSuccessful) error("DLNA description HTTP ${it.code}")
            it.body?.string() ?: error("DLNA description is empty")
        }
        val parser = Xml.newPullParser().apply { setInput(StringReader(document)) }
        var friendlyName = "局域网电视"
        var udn = ""
        var inService = false
        var tag = ""
        var serviceType = ""
        var controlUrl = ""
        var avTransport = ""
        var presentationUrl = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name.orEmpty()
                    if (tag == "service") {
                        inService = true
                        serviceType = ""
                        controlUrl = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    val value = parser.text.orEmpty().trim()
                    if (value.isNotEmpty()) {
                        when {
                            inService && tag == "serviceType" -> serviceType = value
                            inService && tag == "controlURL" -> controlUrl = value
                            !inService && tag == "friendlyName" && friendlyName == "局域网电视" -> friendlyName = value
                            !inService && tag == "UDN" && udn.isEmpty() -> udn = value
                            !inService && tag == "presentationURL" && presentationUrl.isEmpty() -> presentationUrl = value
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "service") {
                        if (serviceType.contains(":service:AVTransport:")) avTransport = controlUrl
                        inService = false
                    }
                    tag = ""
                }
            }
            parser.next()
        }
        require(avTransport.isNotBlank()) { "DLNA device has no AVTransport service" }
        val host = URL(location).host
        val endpoint = URL(URL(location), avTransport).toString()
        val remoteEndpoint = resolveStarlightRemoteControlUrl(location, presentationUrl)
        return DlnaDevice(udn, friendlyName, host, location, endpoint, remoteEndpoint)
    }

    private fun deviceKey(device: DlnaDevice): String =
        device.udn.ifBlank { device.location }.lowercase()

    private fun soap(device: DlnaDevice, action: String, arguments: String) {
        val body = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"$AV_TRANSPORT\">$arguments</u:$action></s:Body></s:Envelope>"
            ).toRequestBody(XML)
        val request = Request.Builder()
            .url(device.avTransportUrl)
            .header("SOAPACTION", "\"$AV_TRANSPORT#$action\"")
            .post(body)
            .build()
        client.newCall(request).execute().use {
            if (!it.isSuccessful) error("DLNA $action HTTP ${it.code}")
        }
    }

    private fun didl(url: String, mimeType: String): String {
        val mime = mimeType.takeIf { it.contains('/') } ?: "video/mp4"
        val mediaClass = if (mime.startsWith("image/")) "object.item.imageItem.photo" else "object.item.videoItem"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>星投屏</dc:title>" +
            "<upnp:class>$mediaClass</upnp:class><res protocolInfo=\"http-get:*:$mime:*\">${xml(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private companion object {
        const val DISCOVERY_MS = 3_200L
        const val CACHE_MS = 30_000L
        const val DEVICE_GRACE_MS = 120_000L
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "upnp:rootdevice",
            "ssdp:all",
        )
        val XML = "text/xml; charset=utf-8".toMediaType()
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun resolveStarlightRemoteControlUrl(location: String, presentationUrl: String): String = runCatching {
    val locationUrl = URL(location)
    val candidate = URL(locationUrl, presentationUrl)
    val port = if (candidate.port >= 0) candidate.port else candidate.defaultPort
    require(candidate.protocol.equals("http", true) && candidate.host == locationUrl.host && port == 8765)
    require(candidate.query.orEmpty().split('&').any { it.startsWith("token=") && it.length > 6 })
    URL(candidate.protocol, candidate.host, port, "/remote/key?${candidate.query}").toString()
}.getOrDefault("")
