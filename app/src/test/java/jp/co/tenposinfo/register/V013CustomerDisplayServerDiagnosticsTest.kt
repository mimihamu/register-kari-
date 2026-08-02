package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class V013CustomerDisplayServerDiagnosticsTest {
    @Test
    fun connectedClientCountChangesFromOneBackToZero() {
        val port = ServerSocket(0).use { it.localPort }
        val counts = LinkedBlockingQueue<Int>()
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
            latestPayload = { "{}" },
            onClientCountChanged = counts::offer,
        )
        server.start()

        try {
            val socket = connectWithRetry(port)
            try {
                val key = "dGhlIHNhbXBsZSBub25jZQ=="
                val request = buildString {
                    append("GET $CUSTOMER_DISPLAY_PATH?token=${config.token} HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }
                socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
                socket.getOutputStream().flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                val status = reader.readLine()
                assertTrue(status.contains("101 Switching Protocols"))
                while (reader.readLine().isNotEmpty()) {
                    Unit
                }

                assertEquals(1, counts.poll(2, TimeUnit.SECONDS) ?: -1)
            } finally {
                socket.close()
            }

            assertEquals(0, counts.poll(2, TimeUnit.SECONDS) ?: -1)
        } finally {
            server.stop()
        }
    }

    private fun connectWithRetry(port: Int): Socket {
        var lastError: Exception? = null
        repeat(40) {
            try {
                return Socket("127.0.0.1", port).apply { soTimeout = 2_000 }
            } catch (error: Exception) {
                lastError = error
                Thread.sleep(25L)
            }
        }
        throw AssertionError("WebSocket server did not start", lastError)
    }
}
