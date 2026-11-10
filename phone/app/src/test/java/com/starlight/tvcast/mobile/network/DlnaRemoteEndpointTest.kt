package app.xingmu.remote.network

import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaRemoteEndpointTest {
    @Test
    fun resolvesRelativePresentationUrlToSameTv() {
        assertEquals(
            "http://192.168.31.163:8765/remote/key?token=local-secret",
            resolveStarlightRemoteControlUrl(
                "http://192.168.31.163:49152/device.xml",
                "http://192.168.31.163:8765/?token=local-secret",
            ),
        )
    }

    @Test
    fun rejectsForeignHostOrMissingToken() {
        assertEquals(
            "",
            resolveStarlightRemoteControlUrl(
                "http://192.168.31.163:49152/device.xml",
                "http://192.168.31.99:8765/?token=local-secret",
            ),
        )
        assertEquals(
            "",
            resolveStarlightRemoteControlUrl(
                "http://192.168.31.163:49152/device.xml",
                "http://192.168.31.163:8765/",
            ),
        )
    }
}
