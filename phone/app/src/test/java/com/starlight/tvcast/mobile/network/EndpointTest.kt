package app.xingmu.remote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointTest {
    @Test
    fun normalizesApiUrlAndBuildsCredentialFreeWebSocket() {
        val ws = Endpoint.controlWebSocket("https://example.com/api/", "tv:living-room")
        assertEquals("wss://example.com/api/ws/control/tv:living-room", ws)
        assertFalse(ws.contains("token"))
    }

    @Test
    fun rejectsCredentialsAndQueryParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.normalize("https://user:password@example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.normalize("https://example.com?token=secret")
        }
    }

    @Test
    fun buildsPostClaimEndpointAndEscapesPairingCode() {
        assertEquals(
            "http://192.168.31.111:18000/api/pairing/code%2Fwith%20space/claim",
            Endpoint.pairingClaim("http://192.168.31.111:18000", "code/with space"),
        )
    }
}
