package jp.co.tenposinfo.register

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object CustomerDisplayWebSocketHandshake {
    private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun accept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key.trim() + MAGIC).toByteArray(StandardCharsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }
}

internal object CustomerDisplayConnectionLivenessPolicy {
    const val HANDSHAKE_TIMEOUT_MS = 5_000
    const val LIVENESS_CHECK_INTERVAL_MS = 15_000
    const val STALE_AFTER_MS = 45_000L

    fun shouldClose(nowMillis: Long, lastTransportActivityAtMillis: Long): Boolean =
        nowMillis - lastTransportActivityAtMillis >= STALE_AFTER_MS
}

/**
 * Keeps socket I/O away from the cashier/cart polling thread.
 *
 * A stalled customer display may block its own writer thread, but it must never block another
 * display or the POS poller. While a writer is busy only the newest snapshot is retained, so a
 * dead/slow peer cannot create an unbounded backlog of obsolete SALES snapshots.
 */
internal class CustomerDisplayLatestSendDispatcher(
    private val executor: Executor,
    private val send: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val drainScheduled = AtomicBoolean(false)
    private val latestPayload = AtomicReference<String?>(null)

    fun offer(payload: String): Boolean {
        if (closed.get()) return false
        latestPayload.set(payload)
        if (closed.get()) {
            latestPayload.set(null)
            return false
        }
        scheduleDrain()
        return true
    }

    override fun close() {
        closed.set(true)
        latestPayload.set(null)
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        try {
            executor.execute(::drain)
        } catch (error: Throwable) {
            drainScheduled.set(false)
            fail(error)
        }
    }

    private fun drain() {
        try {
            while (!closed.get()) {
                val payload = latestPayload.getAndSet(null) ?: return
                send(payload)
            }
        } catch (error: Throwable) {
            fail(error)
        } finally {
            drainScheduled.set(false)
            if (!closed.get() && latestPayload.get() != null) scheduleDrain()
        }
    }

    private fun fail(error: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        latestPayload.set(null)
        runCatching { onFailure(error) }
    }
}

internal class CustomerDisplayWebSocketServer(
    private val config: CustomerDisplayServerConfig,
    private val latestPayload: () -> String,
    private val onError: (String) -> Unit = {},
    private val onClientCountChanged: (Int) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<ClientConnection>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "tsuguregi-customer-display").apply { isDaemon = true }
    }
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(config.port))
                }
                serverSocket = socket
                while (running.get()) {
                    val client = socket.accept().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        soTimeout = CustomerDisplayConnectionLivenessPolicy.HANDSHAKE_TIMEOUT_MS
                    }
                    executor.execute { handleClient(client) }
                }
            } catch (error: Exception) {
                if (running.get()) onError(error.message ?: error.javaClass.simpleName)
            } finally {
                running.set(false)
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.toList().forEach { it.close() }
        clients.clear()
        notifyClientCount()
        executor.shutdownNow()
    }

    fun broadcast(payload: String) {
        clients.toList().forEach { connection ->
            if (!connection.sendText(payload)) {
                if (clients.remove(connection)) notifyClientCount()
                connection.close()
            }
        }
    }

    fun clientCount(): Int = clients.size

    private fun handleClient(socket: Socket) {
        var connection: ClientConnection? = null
        try {
            val request = readHttpRequest(socket.getInputStream())
            val requestLine = request.lines.firstOrNull().orEmpty().split(' ')
            val target = requestLine.getOrNull(1).orEmpty()
            val path = target.substringBefore('?')
            val token = queryParameter(target, "token")
            val key = request.headers["sec-websocket-key"]
            val upgrade = request.headers["upgrade"]?.lowercase(Locale.ROOT)
            if (path != config.path || token != config.token || key.isNullOrBlank() || upgrade != "websocket") {
                writeHttpError(socket.getOutputStream(), 401, "Unauthorized")
                return
            }

            val output = socket.getOutputStream()
            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: ${CustomerDisplayWebSocketHandshake.accept(key)}\r\n")
                append("\r\n")
            }
            output.write(response.toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()

            socket.soTimeout = CustomerDisplayConnectionLivenessPolicy.LIVENESS_CHECK_INTERVAL_MS
            connection = ClientConnection(socket, executor)
            clients += connection
            notifyClientCount()
            connection.sendText(latestPayload())
            readClientFrames(connection)
        } catch (_: EOFException) {
            Unit
        } catch (_: SocketTimeoutException) {
            Unit
        } catch (error: Exception) {
            if (running.get()) onError(error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.let {
                if (clients.remove(it)) notifyClientCount()
            }
            connection?.close() ?: runCatching { socket.close() }
        }
    }

    private fun notifyClientCount() {
        runCatching { onClientCountChanged(clients.size) }
    }

    private fun readClientFrames(connection: ClientConnection) {
        val input = connection.socket.getInputStream()
        while (running.get() && !connection.socket.isClosed) {
            val first = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                if (connection.isTransportStale(System.currentTimeMillis())) {
                    throw EOFException("customer display transport inactive")
                }
                continue
            }
            if (first < 0) return
            val second = input.read()
            if (second < 0) return
            val opcode = first and 0x0F
            val masked = second and 0x80 != 0
            var length = (second and 0x7F).toLong()
            if (length == 126L) {
                length = ((readRequired(input) shl 8) or readRequired(input)).toLong()
            } else if (length == 127L) {
                length = 0L
                kotlin.repeat(8) { length = (length shl 8) or readRequired(input).toLong() }
            }
            if (length > MAX_CLIENT_FRAME_BYTES) throw IllegalArgumentException("client frame too large")
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(input, it) }
            if (mask != null) {
                payload.indices.forEach { index ->
                    payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                }
            }
            connection.markInboundActivity()
            when (opcode) {
                0x8 -> {
                    connection.sendControl(0x8, payload)
                    return
                }
                0x9 -> connection.sendControl(0xA, payload)
                else -> Unit
            }
        }
    }

    private data class HttpRequest(val lines: List<String>, val headers: Map<String, String>)

    private fun readHttpRequest(input: InputStream): HttpRequest {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < MAX_HTTP_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw EOFException("connection closed during handshake")
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
        if (matched != 4) throw IllegalArgumentException("invalid HTTP handshake")
        val lines = buffer.toString(StandardCharsets.ISO_8859_1.name())
            .split("\r\n")
            .filter { it.isNotEmpty() }
        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim().lowercase(Locale.ROOT) to line.substring(index + 1).trim()
        }.toMap()
        return HttpRequest(lines, headers)
    }

    private fun queryParameter(target: String, name: String): String? {
        val query = target.substringAfter('?', "")
        return query.split('&').firstNotNullOfOrNull { pair ->
            val key = pair.substringBefore('=')
            if (key != name) null else URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8.name())
        }
    }

    private fun writeHttpError(output: OutputStream, status: Int, reason: String) {
        val body = "$status $reason"
        val response = "HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
        output.write(response.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun readRequired(input: InputStream): Int = input.read().also {
        if (it < 0) throw EOFException("unexpected end of frame")
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("unexpected end of frame")
            offset += count
        }
    }

    private class ClientConnection(
        val socket: Socket,
        executor: Executor,
    ) {
        private val writeLock = Any()
        private val lastTransportActivityAtMillis = AtomicLong(System.currentTimeMillis())
        private val textDispatcher = CustomerDisplayLatestSendDispatcher(
            executor = executor,
            send = { payload ->
                synchronized(writeLock) {
                    writeFrame(socket.getOutputStream(), 0x1, payload.toByteArray(StandardCharsets.UTF_8))
                    markTransportActivity()
                }
            },
            onFailure = { closeTransport() },
        )

        fun sendText(payload: String): Boolean = textDispatcher.offer(payload)

        fun sendControl(opcode: Int, payload: ByteArray): Boolean = runCatching {
            synchronized(writeLock) {
                writeFrame(socket.getOutputStream(), opcode, payload.copyOf(minOf(payload.size, 125)))
                markTransportActivity()
            }
        }.isSuccess

        fun markInboundActivity() {
            markTransportActivity()
        }

        fun isTransportStale(nowMillis: Long): Boolean =
            CustomerDisplayConnectionLivenessPolicy.shouldClose(
                nowMillis = nowMillis,
                lastTransportActivityAtMillis = lastTransportActivityAtMillis.get(),
            )

        override fun toString(): String = "CustomerDisplayClient(${socket.remoteSocketAddress})"

        fun close() {
            textDispatcher.close()
            closeTransport()
        }

        private fun markTransportActivity() {
            lastTransportActivityAtMillis.set(System.currentTimeMillis())
        }

        private fun closeTransport() {
            runCatching { socket.close() }
        }

        companion object {
            private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
                output.write(0x80 or (opcode and 0x0F))
                when {
                    payload.size <= 125 -> output.write(payload.size)
                    payload.size <= 0xFFFF -> {
                        output.write(126)
                        output.write((payload.size ushr 8) and 0xFF)
                        output.write(payload.size and 0xFF)
                    }
                    else -> {
                        output.write(127)
                        val length = payload.size.toLong()
                        for (shift in 56 downTo 0 step 8) output.write(((length ushr shift) and 0xFF).toInt())
                    }
                }
                output.write(payload)
                output.flush()
            }
        }
    }

    companion object {
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val MAX_CLIENT_FRAME_BYTES = 64 * 1024L
    }
}
