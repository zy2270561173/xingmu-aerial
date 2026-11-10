package app.xingmu.aerial.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.UUID

/**
 * A minimal UPnP MediaRenderer: enough for a phone's "投屏" button to find this TV and push a
 * video URL to it. SSDP does discovery, the description document names the services, and
 * AVTransport turns SetAVTransportURI + Play into "open it in the TV's own player".
 *
 * Deliberately small: no eventing (SUBSCRIBE gets a 200 with no events), no RenderingControl
 * beyond a stub, and no transcoding. A phone that insists on Genres/Seek will get a clean error
 * rather than a silent hang.
 */
class DlnaRenderer(private val context: Context) {
    private var server: NanoHTTPD? = null
    private var ssdp: Thread? = null
    private var socket: MulticastSocket? = null
    @Volatile private var running = false

    private var transportState = "STOPPED"
    private var currentUri = ""
    private var currentMetadata = ""

    private val udn: String = "uuid:" + UUID.nameUUIDFromBytes(
        ("aerial-lite-cast:" + CastConfig.deviceId).toByteArray()
    ).toString()

    private val port: Int get() = server?.listeningPort ?: 0

    fun start() {
        if (running) return
        running = true
        try {
            val http = object : NanoHTTPD(PORT) {
                override fun serve(session: IHTTPSession): Response = handle(session)
            }
            http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = http
            Log.i(TAG, "DLNA description on port " + http.listeningPort)
        } catch (error: Exception) {
            Log.w(TAG, "DLNA http failed: " + error.javaClass.simpleName)
            running = false
            return
        }
        ssdp = Thread({ ssdpLoop() }, "aerial-dlna-ssdp").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        runCatching { server?.stop() }
        server = null
        ssdp = null
        transportState = "STOPPED"
        currentUri = ""
    }

    private fun handle(session: IHTTPSession): Response {
        val path = session.uri.orEmpty()
        return when {
            path == "/description.xml" -> xml(description())
            path.startsWith("/scpd/") -> xml(scpdFor(path))
            path.startsWith("/control/") -> control(session)
            path.startsWith("/event/") -> NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "text/xml; charset=utf-8", EVENT_OK
            )
            else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }

    private fun xml(body: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", body)

    // ---- SSDP ----

    private fun ssdpLoop() {
        try {
            val group = InetAddress.getByName(SSDP_ADDR)
            val ms = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                joinGroup(group)
            }
            socket = ms
            val buffer = ByteArray(2048)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    ms.receive(packet)
                } catch (_: Exception) {
                    if (!running) break else continue
                }
                val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!message.startsWith("M-SEARCH", ignoreCase = true)) continue
                val st = Regex("(?im)^ST:\\s*(.+)$").find(message)?.groupValues?.get(1)?.trim().orEmpty()
                val wanted = st.contains("MediaRenderer") || st == "ssdp:all" || st == "upnp:rootdevice"
                if (!wanted) continue
                val reply = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("CACHE-CONTROL: max-age=1800\r\n")
                    append("EXT:\r\n")
                    append("LOCATION: ").append(descriptionUrl()).append("\r\n")
                    append("SERVER: AerialLite/1.26 UPnP/1.0 Cast/1.0\r\n")
                    append("ST: ").append(if (st.isEmpty() || st == "ssdp:all") MEDIA_RENDERER else st).append("\r\n")
                    append("USN: ").append(udn).append("::").append(if (st.isEmpty()) MEDIA_RENDERER else st).append("\r\n")
                    append("BOOTID.UPNP.ORG: 1\r\n\r\n")
                }.toByteArray(Charsets.UTF_8)
                runCatching { ms.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "SSDP stopped: " + error.javaClass.simpleName)
        } finally {
            runCatching { socket?.close() }
            socket = null
        }
    }

    // ---- description ----

    private fun lanIp(): String = CastNetwork.localIpv4().ifEmpty { "127.0.0.1" }

    private fun descriptionUrl(): String = "http://" + lanIp() + ":" + port + "/description.xml"

    private fun description(): String = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>$MEDIA_RENDERER</deviceType>
            <friendlyName>${xmlEscape(CastConfig.deviceName)}</friendlyName>
            <manufacturer>星投屏</manufacturer>
            <modelDescription>Aerial Lite 桌面内置接收端</modelDescription>
            <modelName>Aerial Lite</modelName>
            <UDN>$udn</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <SCPDURL>/scpd/avt.xml</SCPDURL>
                <controlURL>/control/avt</controlURL>
                <eventSubURL>/event/avt</eventSubURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <SCPDURL>/scpd/cm.xml</SCPDURL>
                <controlURL>/control/cm</controlURL>
                <eventSubURL>/event/cm</eventSubURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private fun scpdFor(path: String): String = if (path.contains("cm")) SCPD_CM else SCPD_AVT

    // ---- SOAP control ----

    private fun control(session: IHTTPSession): Response {
        val body = readBody(session)
        val action = (session.headers["soapaction"] ?: "").trim('"').substringAfterLast('#')
            .ifEmpty { Regex("<u:([A-Za-z]+)").find(body)?.groupValues?.get(1).orEmpty() }
        val service = if (session.uri.orEmpty().contains("cm")) "cm" else "avt"
        if (service == "cm") {
            val result = when (action) {
                "GetProtocolInfo" -> "<Source></Source><Sink>http-get:*:video/*,*/*:*</Sink>"
                "GetCurrentConnectionIDs" -> "<ConnectionIDs>0</ConnectionIDs>"
                else -> ""
            }
            return soap(action, result)
        }
        return when (action) {
            "SetAVTransportURI" -> {
                currentUri = Regex("<CurrentURI>(.*?)</CurrentURI>", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1)?.let(::xmlUnescape).orEmpty()
                currentMetadata = Regex("<CurrentURIMetaData>(.*?)</CurrentURIMetaData>", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1).orEmpty()
                transportState = "STOPPED"
                Log.i(TAG, "DLNA SetAVTransportURI: " + currentUri)
                soap(action, "")
            }
            "Play" -> {
                val launched = open(currentUri)
                transportState = if (launched) "PLAYING" else "STOPPED"
                soap(action, "")
            }
            "Pause" -> {
                transportState = "PAUSED_PLAYBACK"
                sendKey(KEYCODE_MEDIA_PAUSE)
                soap(action, "")
            }
            "Stop" -> {
                transportState = "STOPPED"
                sendKey(KEYCODE_MEDIA_STOP)
                soap(action, "")
            }
            "GetTransportInfo" -> soap(
                action,
                "<CurrentTransportState>$transportState</CurrentTransportState>" +
                    "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>",
            )
            "GetMediaInfo" -> soap(
                action,
                "<NrTracks>1</NrTracks><MediaDuration>00:00:00</MediaDuration>" +
                    "<CurrentURI>" + xmlEscape(currentUri) + "</CurrentURI>" +
                    "<CurrentURIMetaData>" + xmlEscape(currentMetadata) + "</CurrentURIMetaData>",
            )
            "GetPositionInfo" -> soap(
                action,
                "<Track>1</Track><TrackDuration>00:00:00</TrackDuration>" +
                    "<TrackURI>" + xmlEscape(currentUri) + "</TrackURI>" +
                    "<RelTime>00:00:00</RelTime><AbsTime>00:00:00</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>",
            )
            else -> NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/xml; charset=utf-8", fault(action)
            )
        }
    }

    /** Hands the URL to the TV's own player, exactly like a cast target would. */
    private fun open(uri: String): Boolean {
        if (uri.isEmpty()) return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), guessType(uri))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "no player for " + uri + ": " + it.javaClass.simpleName)
            false
        }
    }

    private fun sendKey(keyCode: Int) {
        runCatching { LegacyAdbClient.sendKey(context, CastConfig.adbPort, keyCode) }
    }

    private fun guessType(uri: String): String {
        val path = uri.lowercase().substringBefore('?')
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".jpg") || path.endsWith(".png") -> "image/*"
            else -> "video/*"
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        return runCatching {
            session.parseBody(map)
            map["postData"].orEmpty()
        }.getOrDefault("")
    }

    private fun soap(action: String, result: String): Response {
        val body = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:${action}Response xmlns:u="$AVT">$result</u:${action}Response></s:Body></s:Envelope>"""
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", body)
    }

    private fun fault(action: String): String = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>401</errorCode>
<errorDescription>Action $action not supported by Aerial Lite</errorDescription></UPnPError></detail>
</s:Fault></s:Body></s:Envelope>"""

    companion object {
        private const val TAG = "AerialDlna"
        private const val PORT = 8766
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val KEYCODE_MEDIA_PAUSE = 127
        private const val KEYCODE_MEDIA_STOP = 86

        private val EVENT_OK = """<?xml version="1.0"?><e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"/>"""

        private val SCPD_AVT = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>
<action><name>SetAVTransportURI</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
</argumentList></action>
<action><name>Play</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
</argumentList></action>
<action><name>Pause</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
<action><name>Stop</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
<action><name>GetTransportInfo</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
<argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
<argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetMediaInfo</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>
<argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
<argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
</argumentList></action>
</actionList>
<serviceStateTable>
<stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType>
<allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue></allowedValueList></stateVariable>
<stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
</serviceStateTable></scpd>"""

        private val SCPD_CM = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>
<action><name>GetProtocolInfo</name><argumentList>
<argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
<argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetCurrentConnectionIDs</name><argumentList>
<argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
</argumentList></action>
</actionList>
<serviceStateTable>
<stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
</serviceStateTable></scpd>"""

        private fun xmlEscape(value: String): String = value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun xmlUnescape(value: String): String = value
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

        /** Used by the agent's status line: whether the renderer is up. */
        fun describe(state: String): JSONObject = JSONObject().put("state", state)
    }
}
