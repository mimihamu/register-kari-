package jp.co.tenposinfo.register

import java.io.EOFException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V137NegotiatedWebSocketIntegrationTest {
    @Test
    fun legacyAndFormalCustomersReceiveIndependentFormatsAndFullResync() {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "0123456789abcdef0123456789abcdef"
        val live = AtomicReference(
            CustomerDisplaySnapshotFactory.standby("テスト店").copy(
                sequence = 70L, sessionId = "session-70",
            ),
        )
        val config = CustomerDisplayServerConfig(
            enabled = true, port = port, path = CUSTOMER_DISPLAY_PATH,
            token = secret, storeName = "テスト店", completeSeconds = 3,
        )
        val server = CustomerDisplayWebSocketServer(
            config = config,
            latestPayload = { live.get().copy(messageType = CustomerDisplayMessageType.SNAPSHOT).toJson() },
        )
        server.start()
        try {
            connectWithRetry(port).use { old ->
                old.soTimeout = 5_000
                handshake(old, port, secret, canonical = false)
                val oldFirst = JSONObject(readTextFrame(old.getInputStream()))
                assertEquals(1, oldFirst.getInt("schemaVersion"))
                assertEquals("STANDBY", oldFirst.getString("mode"))

                connectWithRetry(port).use { modern ->
                    modern.soTimeout = 5_000
                    handshake(modern, port, secret, canonical = true)
                    val formalFirst = JSONObject(readTextFrame(modern.getInputStream()))
                    assertEquals("1.0", formalFirst.getString("schemaVersion"))
                    assertEquals("STANDBY", formalFirst.getString("mode"))

                    live.set(
                        CustomerDisplaySnapshotFactory.standby("更新済").copy(
                            sequence = 72L,
                            sessionId = "session-70",
                            mode = CustomerDisplayMode.ACCOUNTING,
                            paymentMethod = "現金",
                            receivedAmount = 1000L,
                            totalAmount = 1000L,
                        ),
                    )
                    requestSnapshot(old)
                    val oldUpdate = JSONObject(readTextFrame(old.getInputStream()))
                    assertEquals("ACCOUNTING", oldUpdate.getString("mode"))
                    assertEquals(1, oldUpdate.getInt("schemaVersion"))
                    assertEquals(72L, oldUpdate.getLong("sequence"))

                    requestSnapshot(modern)
                    val formalUpdate = JSONObject(readTextFrame(modern.getInputStream()))
                    assertEquals("CASH_RECEIVED", formalUpdate.getString("mode"))
                    assertEquals("1.0", formalUpdate.getString("schemaVersion"))
                    assertEquals(72L, formalUpdate.getLong("sequence"))
                    assertEquals("SNAPSHOT", formalUpdate.getString("messageType"))
                    assertEquals("session-70", formalUpdate.getString("sessionId"))
                }
            }
        } finally {
            server.stop()
        }
    }

    private fun connectWithRetry(port: Int): Socket {
        var failure: Exception? = null
        repeat(60) {
            try {
                return Socket("127.0.0.1", port)
            } catch (e: Exception) {
                failure = e
                Thread.sleep(25)
            }
        }
        throw AssertionError("WebSocket server startup failed", failure)
    }

    private fun handshake(socket: Socket, port: Int, token: String, canonical: Boolean) {
        val request = buildString {
            append("GET $CUSTOMER_DISPLAY_PATH?token=$token HTTP/1.1\r\n")
            append("Host: 127.0.0.1:$port\r\n")
            append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            if (canonical) append("X-Tsuguregi-CD-Wire: 1.0\r\n")
            append("\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()
        assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 101"))
    }

    private fun requestSnapshot(socket: Socket) {
        val payload = """{"messageType":"SNAPSHOT_REQUEST"}""".toByteArray(StandardCharsets.UTF_8)
        val output = socket.getOutputStream()
        output.write(0x81)
        output.write(0x80 or payload.size)
        val mask = byteArrayOf(1, 2, 3, 4)
        output.write(mask)
        payload.indices.forEach { index ->
            output.write(payload[index].toInt() xor mask[index % 4].toInt())
        }
        output.flush()
    }

    private fun readHeaders(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 16_384) {
            val v = input.read()
            if (v < 0) throw EOFException("handshake ended")
            bytes.add(v.toByte())
            if (bytes.size >= 4 && bytes.takeLast(4).toByteArray().contentEquals(byteArrayOf(13,10,13,10))) {
                return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            }
        }
        error("handshake too large")
    }

    private fun readTextFrame(input: InputStream): String {
        val opcode = input.read()
        assertEquals(0x81, opcode)
        val lengthByte = input.read()
        val length = when (lengthByte) {
            126 -> (input.read() shl 8) or input.read()
            127 -> error("test payload too large")
            else -> lengthByte
        }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            if (n < 0) throw EOFException("snapshot EOF")
            offset += n
        }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
