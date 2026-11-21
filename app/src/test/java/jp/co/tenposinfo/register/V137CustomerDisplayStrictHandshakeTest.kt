package jp.co.tenposinfo.register

import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CustomerDisplayStrictHandshakeTest {
    private val request = "GET $CUSTOMER_DISPLAY_PATH?token=secret HTTP/1.1"
    private val key = "dGhlIHNhbXBsZSBub25jZQ=="

    private fun valid(
        line: String = request,
        upgrade: String? = "websocket",
        connection: String? = "keep-alive, Upgrade",
        version: String? = "13",
        nonce: String? = key,
    ) = CustomerDisplayUpgradeRequestPolicy.valid(line, upgrade, connection, version, nonce)

    @Test
    fun exactRfc6455KeyAndVersionWithLegacyPeerSucceed() {
        assertTrue(valid())
        assertTrue(valid(connection = "Upgrade"))
        assertTrue(valid(upgrade = "WebSocket", connection = "Keep-Alive, upgrade"))
    }

    @Test
    fun invalidMethodVersionConnectionOrKeyAreRejected() {
        assertFalse(valid(line = "POST $CUSTOMER_DISPLAY_PATH HTTP/1.1"))
        assertFalse(valid(line = "GET $CUSTOMER_DISPLAY_PATH HTTP/1.0"))
        assertFalse(valid(line = "GET  $CUSTOMER_DISPLAY_PATH HTTP/1.1"))
        assertFalse(valid(upgrade = "h2c"))
        assertFalse(valid(connection = "keep-alive"))
        assertFalse(valid(version = "12"))
        assertFalse(valid(nonce = "not-base64"))
        assertFalse(valid(nonce = "aGVsbG8="))
        assertFalse(valid(nonce = key.dropLast(1)))
    }

    @Test
    fun tokenComparisonRejectsMissingOrDifferentToken() {
        assertTrue(CustomerDisplayUpgradeRequestPolicy.tokenMatches("secret", "secret"))
        assertFalse(CustomerDisplayUpgradeRequestPolicy.tokenMatches(null, "secret"))
        assertFalse(CustomerDisplayUpgradeRequestPolicy.tokenMatches("Secret", "secret"))
        assertFalse(CustomerDisplayUpgradeRequestPolicy.tokenMatches("secret ", "secret"))
    }

    @Test
    fun onlyCanonicalUuidHeadersBecomeDisplayedIdentity() {
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            CustomerDisplayDeviceIdentityPolicy.parseHeader("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
        for (header in listOf("", "-".repeat(36),
            "aaaa----bbbb----cccc----dddd-----eeee",
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\r\nHost: bad",
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeez", "foo")) {
            assertNull(CustomerDisplayDeviceIdentityPolicy.parseHeader(header))
        }
        assertNull(CustomerDisplayDeviceIdentityPolicy.parseHeader(null))
    }

    @Test
    fun invalidUpgradeIsRejectedOnRealLocalSocketBeforeAnySnapshot() {
        val port = ServerSocket(0).use { it.localPort }
        val config = CustomerDisplayServerConfig(
            enabled = true, port = port, path = CUSTOMER_DISPLAY_PATH,
            token = "0123456789abcdef0123456789abcdef", storeName = "店",
            completeSeconds = 3,
        )
        val server = CustomerDisplayWebSocketServer(config, latestPayload = {
            CustomerDisplaySnapshotFactory.standby("店").toJson()
        })
        server.start()
        try {
            var socket: Socket? = null
            kotlin.repeat(50) {
                if (socket == null) {
                    socket = runCatching { Socket("127.0.0.1", port) }.getOrNull()
                    if (socket == null) Thread.sleep(25L)
                }
            }
            val connected = socket ?: error("local socket not available")
            connected.use {
                it.soTimeout = 4_000
                it.getOutputStream().write(buildString {
                    append("GET $CUSTOMER_DISPLAY_PATH?token=${config.token} HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: keep-alive\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n\r\n")
                }.toByteArray(StandardCharsets.ISO_8859_1))
                val response = it.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1).readLine()
                assertTrue(response.startsWith("HTTP/1.1 400"))
                assertEquals(0, server.clientCount())
            }
        } finally {
            server.stop()
        }
    }
}
