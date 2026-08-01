package jp.co.tenposinfo.register.cd

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal object CustomerDisplayClientHandshake {
    private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun accept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key.trim() + MAGIC).toByteArray(StandardCharsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }
}

class CustomerDisplayWebSocketClient(
    private val settings: CustomerDisplayConnectionSettings,
    private val onConnected: () -> Unit,
    private val onSnapshot: (CustomerDisplaySnapshot) -> Unit,
    private val onDisconnected: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val random = SecureRandom()
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(
            start = true,
            isDaemon = true,
            name = "tsuguregi-cd-websocket",
        ) {
            reconnectLoop()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { socket?.close() }
        socket = null
        worker?.interrupt()
        worker = null
    }

    private fun reconnectLoop() {
        var attempt = 0
        while (running.get()) {
            val reason = runCatching {
                connectAndRead()
                "接続が終了しました"
            }.exceptionOrNull()?.message ?: "接続が終了しました"
            if (!running.get()) return
            onDisconnected(reason)
            val delayMillis = RETRY_DELAYS_MS[minOf(attempt, RETRY_DELAYS_MS.lastIndex)]
            attempt++
            try {
                Thread.sleep(delayMillis)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun connectAndRead() {
        require(settings.isConfigured) { "接続先IPとトークンを設定してください" }
        val client = Socket()
        socket = client
        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            client.connect(InetSocketAddress(settings.host, settings.port), CONNECT_TIMEOUT_MS)
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val keyBytes = ByteArray(16).also(random::nextBytes)
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val token = URLEncoder.encode(settings.token, StandardCharsets.UTF_8.name())
            val request = buildString {
                append("GET $CUSTOMER_DISPLAY_PATH?token=$token HTTP/1.1\r\n")
                append("Host: ${settings.host}:${settings.port}\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()

            val response = readHttpResponse(input)
            val statusLine = response.lines.firstOrNull().orEmpty()
            val accept = response.headers["sec-websocket-accept"]
            require(statusLine.contains(" 101 ")) { "接続が拒否されました（$statusLine）" }
            require(accept == CustomerDisplayClientHandshake.accept(key)) { "WebSocket応答を検証できません" }
            onConnected()
            readFrames(input, output)
        } finally {
            runCatching { client.close() }
            if (socket === client) socket = null
        }
    }

    private fun readFrames(input: InputStream, output: OutputStream) {
        while (running.get()) {
            val first = input.read()
            if (first < 0) throw EOFException("レジとの接続が切れました")
            val second = input.read()
            if (second < 0) throw EOFException("レジとの接続が切れました")
            val finalFrame = first and 0x80 != 0
            val opcode = first and 0x0F
            val masked = second and 0x80 != 0
            var length = (second and 0x7F).toLong()
            if (length == 126L) {
                length = ((readRequired(input) shl 8) or readRequired(input)).toLong()
            } else if (length == 127L) {
                length = 0L
                kotlin.repeat(8) { length = (length shl 8) or readRequired(input).toLong() }
            }
            require(length <= MAX_FRAME_BYTES) { "受信データが大きすぎます" }
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(input, it) }
            if (mask != null) {
                payload.indices.forEach { index -> payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
            }
            when (opcode) {
                0x1 -> {
                    require(finalFrame) { "分割WebSocketフレームには未対応です" }
                    val json = payload.toString(StandardCharsets.UTF_8)
                    onSnapshot(CustomerDisplaySnapshot.parse(json))
                }
                0x8 -> {
                    writeMaskedFrame(output, 0x8, payload.copyOf(minOf(payload.size, 125)))
                    return
                }
                0x9 -> writeMaskedFrame(output, 0xA, payload.copyOf(minOf(payload.size, 125)))
                0xA -> Unit
                else -> Unit
            }
        }
    }

    private data class HttpResponse(val lines: List<String>, val headers: Map<String, String>)

    private fun readHttpResponse(input: InputStream): HttpResponse {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < MAX_HTTP_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw EOFException("接続応答を受信できません")
            buffer.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        require(matched == 4) { "接続応答が不正です" }
        val lines = buffer.toString(StandardCharsets.ISO_8859_1.name())
            .split("\r\n")
            .filter { it.isNotEmpty() }
        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim().lowercase(Locale.ROOT) to line.substring(index + 1).trim()
        }.toMap()
        return HttpResponse(lines, headers)
    }

    private fun writeMaskedFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also(random::nextBytes)
        output.write(0x80 or (opcode and 0x0F))
        when {
            payload.size <= 125 -> output.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                output.write(0x80 or 126)
                output.write((payload.size ushr 8) and 0xFF)
                output.write(payload.size and 0xFF)
            }
            else -> {
                output.write(0x80 or 127)
                val size = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) output.write(((size ushr shift) and 0xFF).toInt())
            }
        }
        output.write(mask)
        payload.indices.forEach { index -> output.write(payload[index].toInt() xor mask[index % 4].toInt()) }
        output.flush()
    }

    private fun readRequired(input: InputStream): Int = input.read().also {
        if (it < 0) throw EOFException("受信途中で接続が切れました")
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("受信途中で接続が切れました")
            offset += count
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val MAX_FRAME_BYTES = 1_048_576L
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    }
}
