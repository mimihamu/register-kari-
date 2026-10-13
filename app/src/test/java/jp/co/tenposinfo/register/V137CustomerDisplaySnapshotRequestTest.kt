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

class V137CustomerDisplaySnapshotRequestTest {
    @Test
    fun canonicalSnapshotCarriesSessionAndMessageTypeWithLegacyFields() {
        val snapshot = CustomerDisplaySnapshotFactory.standby("つぐレジ").copy(
            sessionId = "session-one",
            sequence = 7L,
            messageType = CustomerDisplayMessageType.SNAPSHOT,
        )
        val envelope = JSONObject(snapshot.toJson())
        assertEquals("session-one", envelope.getString("sessionId"))
        assertEquals("SNAPSHOT", envelope.getString("messageType"))
        assertEquals(7L, envelope.getLong("sequence"))
        assertTrue(envelope.has("orderItems"))
        assertTrue(envelope.has("items"))
    }

    @Test
    fun serverRespondsToMaskedSnapshotRequestWithCurrentFullState() {
        val port = ServerSocket(0).use { it.localPort }
        val current = AtomicReference(
            CustomerDisplaySnapshotFactory.standby("旧状態").copy(
                sequence = 10L, sessionId = "session-a",
            ),
        )
        val config = CustomerDisplayServerConfig(
            enabled = true,
            port = port,
            path = CUSTOMER_DISPLAY_PATH,
            token = "0123456789abcdef0123456789abcdef",
            storeName = "テスト店",
            completeSeconds = 5,
        )
        val server = CustomerDisplayWebSocketServer(
            config = config,
            latestPayload = {
                current.get().copy(messageType = CustomerDisplayMessageType.SNAPSHOT).toJson()
            },
        )
        server.start()
        try {
            connectWithRetry(port).use { socket ->
                socket.soTimeout = 4_000
                val key = "dGhlIHNhbXBsZSBub25jZQ=="
                val request = buildString {
                    append("GET $CUSTOMER_DISPLAY_PATH?token=${config.token} HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n\r\n")
                }
                val output = socket.getOutputStream()
                output.write(request.toByteArray(StandardCharsets.ISO_8859_1))
                output.flush()
                val input = socket.getInputStream()
                val header = readHeaders(input)
                assertTrue(header.startsWith("HTTP/1.1 101"))
                val first = JSONObject(readTextFrame(input))
                assertEquals(10L, first.getLong("sequence"))
                assertEquals("SNAPSHOT", first.getString("messageType"))

                // Update the register state without sending an intermediate event.
                current.set(current.get().copy(sequence = 42L, storeName = "最新状態"))
                val command = """{"messageType":"SNAPSHOT_REQUEST"}""".toByteArray(StandardCharsets.UTF_8)
                output.write(byteArrayOf(0x81.toByte(), (0x80 or command.size).toByte()))
                val mask = byteArrayOf(11, 22, 33, 44)
                output.write(mask)
                command.indices.forEach { index ->
                    output.write(command[index].toInt() xor mask[index % 4].toInt())
                }
                output.flush()
                val latest = JSONObject(readTextFrame(input))
                assertEquals(42L, latest.getLong("sequence"))
                assertEquals("最新状態", latest.getString("storeName"))
                assertEquals("SNAPSHOT", latest.getString("messageType"))
            }
        } finally {
            server.stop()
        }
    }

    private fun readHeaders(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 16_384) {
            val value = input.read()
            if (value < 0) throw EOFException("handshake EOF")
            bytes.add(value.toByte())
            if (bytes.size >= 4 &&
                bytes.takeLast(4).toByteArray().contentEquals(byteArrayOf(13, 10, 13, 10))) {
                return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            }
        }
        error("HTTP header too large")
    }

    private fun readTextFrame(input: InputStream): String {
        val opcode = input.read()
        require(opcode == 0x81) { "expected final websocket text frame, got $opcode" }
        val lengthCode = input.read()
        require(lengthCode in 0..127)
        val length = when (lengthCode) {
            126 -> (input.read() shl 8) or input.read()
            127 -> error("test frame too large")
            else -> lengthCode
        }
        val payload = ByteArray(length)
        var offset = 0
        while (offset < payload.size) {
            val count = input.read(payload, offset, payload.size - offset)
            if (count < 0) throw EOFException("frame EOF")
            offset += count
        }
        return String(payload, StandardCharsets.UTF_8)
    }

    private fun connectWithRetry(port: Int): Socket {
        var lastError: Exception? = null
        for (attempt in 0 until 50) {
            try {
                return Socket("127.0.0.1", port)
            } catch (error: Exception) {
                lastError = error
                Thread.sleep(25L)
            }
        }
        throw AssertionError("WebSocket server did not start", lastError)
    }
}
