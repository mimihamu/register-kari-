package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CdWebSocketInboundValidationTest {
    private val key = "dGhlIHNhbXBsZSBub25jZQ=="
    private fun headers() = mapOf(
        "upgrade" to "websocket",
        "connection" to "keep-alive, Upgrade",
        "sec-websocket-accept" to CustomerDisplayClientHandshake.accept(key),
    )
    private fun permitted(first: Int, second: Int, length: Long) =
        runCatching { CustomerDisplayInboundFramePolicy.verify(first, second, length) }.isSuccess

    @Test fun rfc6455ResponseMustHaveAllUpgradeHeaders() {
        val response = CustomerDisplayClientUpgradeResponsePolicy
        assertTrue(response.valid("HTTP/1.1 101 Switching Protocols", headers(), key))
        assertFalse(response.valid("HTTP/1.1 200 OK", headers(), key))
        assertFalse(response.valid("HTTP/1.0 101 Switching Protocols", headers(), key))
        assertFalse(response.valid("HTTP/1.1 1010 Switching Protocols", headers(), key))
        assertFalse(response.valid("HTTP/1.1 101 Switching Protocols",
            headers() - "upgrade", key))
        assertFalse(response.valid("HTTP/1.1 101 Switching Protocols",
            headers() + ("connection" to "keep-alive"), key))
        assertFalse(response.valid("HTTP/1.1 101 Switching Protocols",
            headers() + ("sec-websocket-accept" to "invalid"), key))
    }

    @Test fun unmaskedFinalFramesRemainCompatibleWithCurrentPosServer() {
        assertTrue(permitted(0x81, 0x01, 1L))  // text
        assertTrue(permitted(0x81, 126, 4096L)) // large text
        assertTrue(permitted(0x88, 0x00, 0L)) // close
        assertTrue(permitted(0x89, 0x04, 4L)) // ping
        assertTrue(permitted(0x8A, 0x04, 4L)) // pong
    }

    @Test fun malformedFramesNeverReachJsonOrAmountRendering() {
        assertFalse(permitted(0x81, 0x81, 1L)) // masked server frame
        assertFalse(permitted(0xC1, 0x01, 1L)) // RSV1 without extension
        assertFalse(permitted(0x01, 0x01, 1L)) // fragmented text
        assertFalse(permitted(0x80, 0x01, 1L)) // continuation
        assertFalse(permitted(0x82, 0x01, 1L)) // binary unsupported
        assertFalse(permitted(0x89, 126, 126L)) // oversized ping
        assertFalse(permitted(0x88, 0, 1_048_577L))
        assertFalse(permitted(0x81, 127, -1L)) // MSB-set 64-bit length
    }
}
