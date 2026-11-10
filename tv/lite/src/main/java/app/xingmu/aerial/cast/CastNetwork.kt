package app.xingmu.aerial.cast

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** The LAN address this TV advertises to the backend and to DLNA controllers. */
object CastNetwork {
    fun localIpv4(): String = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress ?: ""
    }.getOrDefault("")
}
