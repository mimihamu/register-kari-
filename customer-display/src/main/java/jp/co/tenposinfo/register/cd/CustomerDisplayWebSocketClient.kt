package jp.co.tenposinfo.register.cd

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal object CustomerDisplayClientHandshake {
    private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun accept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key.trim() + MAGIC).toByteArray(StandardCharsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }
}

internal object CustomerDisplayConnectionPolicy {
    const val SCREEN_TRANSITION_GRACE_MS = 4_000L
    const val CONNECT_TIMEOUT_MS = 5_000
    const val HEARTBEAT_INTERVAL_MS = 15_000
    const val MAX_MISSED_HEARTBEATS = 1
    private val retryDelaysMs = longArrayOf(500L, 1_000L, 2_000L, 5_000L, 10_000L)

    fun retryDelayMillis(failedAttempts: Int, hadEstablishedConnection: Boolean): Long {
        if (hadEstablishedConnection) return retryDelaysMs.first()
        return retryDelaysMs[minOf(failedAttempts.coerceAtLeast(0), retryDelaysMs.lastIndex)]
    }

    fun nextFailedAttemptCount(failedAttempts: Int, hadEstablishedConnection: Boolean): Int =
        if (hadEstablishedConnection) 0 else (failedAttempts + 1).coerceAtMost(retryDelaysMs.lastIndex)
}

class CustomerDisplayWebSocketClient(
    private val settings: CustomerDisplayConnectionSettings,
    private val onConnected: () -> Unit,
    private val onSnapshot: (CustomerDisplaySnapshot) -> Unit,
    private val onDisconnected: (String) -> Unit,
) {
    private val started = AtomicBoolean(false)
    @Volatile
    private var registration: CustomerDisplayConnectionRegistration? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        registration = CustomerDisplayConnectionHub.acquire(
            settings = settings,
            listener = CustomerDisplayConnectionListener(
                onConnected = onConnected,
                onSnapshot = onSnapshot,
                onDisconnected = onDisconnected,
            ),
        )
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        registration?.close()
        registration = null
    }
}

private data class CustomerDisplayConnectionListener(
    val onConnected: () -> Unit,
    val onSnapshot: (CustomerDisplaySnapshot) -> Unit,
    val onDisconnected: (String) -> Unit,
)

private class CustomerDisplayConnectionRegistration(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

private object CustomerDisplayConnectionHub {
    private val lock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tsuguregi-cd-connection-retention").apply { isDaemon = true }
    }
    private var session: SharedCustomerDisplaySession? = null

    fun acquire(
        settings: CustomerDisplayConnectionSettings,
        listener: CustomerDisplayConnectionListener,
    ): CustomerDisplayConnectionRegistration {
        while (true) {
            val selected = synchronized(lock) {
                val current = session
                if (current != null && current.settings == settings) {
                    current
                } else {
                    current?.stopNow()
                    SharedCustomerDisplaySession(
                        settings = settings,
                        scheduler = scheduler,
                        onStopped = ::removeIfCurrent,
                    ).also { session = it }
                }
            }
            val listenerId = selected.tryAddListener(listener)
            if (listenerId != null) {
                return CustomerDisplayConnectionRegistration {
                    selected.removeListener(listenerId)
                }
            }
            synchronized(lock) {
                if (session === selected) session = null
            }
        }
    }

    private fun removeIfCurrent(stopped: SharedCustomerDisplaySession) {
        synchronized(lock) {
            if (session === stopped) session = null
        }
    }
}

private class SharedCustomerDisplaySession(
    val settings: CustomerDisplayConnectionSettings,
    private val scheduler: java.util.concurrent.ScheduledExecutorService,
    private val onStopped: (SharedCustomerDisplaySession) -> Unit,
) {
    private val listenerLock = Any()
    private val running = AtomicBoolean(false)
    private val random = SecureRandom()
    private val listenerSequence = AtomicLong(0L)
    private val listeners = linkedMapOf<Long, CustomerDisplayConnectionListener>()
    private val visibility = CustomerDisplayConnectionVisibilityState()

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var everEstablishedConnection = false
    @Volatile
    private var latestSnapshot: CustomerDisplaySnapshot? = null
    private var delayedStop: ScheduledFuture<*>? = null
    private var pendingVisibleDisconnect: ScheduledFuture<*>? = null
    private var terminal = false
    private var lastLoggedMode: CustomerDisplayMode? = null

    fun tryAddListener(listener: CustomerDisplayConnectionListener): Long? {
        val id = listenerSequence.incrementAndGet()
        val replayAsConnected: Boolean
        val replaySnapshot: CustomerDisplaySnapshot?
        val replayReason: String?
        synchronized(listenerLock) {
            if (terminal) return null
            delayedStop?.cancel(false)
            delayedStop = null
            listeners[id] = listener
            replaySnapshot = latestSnapshot
            replayAsConnected = visibility.shouldPresentAsConnected()
            replayReason = if (visibility.visibleDisconnected) visibility.latestDisconnectReason else null
        }
        startWorker()
        if (replayAsConnected) {
            safeCall(listener.onConnected)
            if (replaySnapshot != null) safeCall { listener.onSnapshot(replaySnapshot) }
        } else if (replayReason != null) {
            safeCall { listener.onDisconnected(replayReason) }
        }
        return id
    }

    fun removeListener(id: Long) {
        synchronized(listenerLock) {
            listeners.remove(id)
            if (terminal || listeners.isNotEmpty() || delayedStop != null) return
            delayedStop = scheduler.schedule(
                ::stopIfUnused,
                CustomerDisplayConnectionPolicy.SCREEN_TRANSITION_GRACE_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun stopNow() {
        val shouldStop = synchronized(listenerLock) {
            if (terminal) {
                false
            } else {
                terminal = true
                delayedStop?.cancel(false)
                delayedStop = null
                pendingVisibleDisconnect?.cancel(false)
                pendingVisibleDisconnect = null
                true
            }
        }
        if (shouldStop) finishStop()
    }

    private fun stopIfUnused() {
        val shouldStop = synchronized(listenerLock) {
            delayedStop = null
            if (terminal || listeners.isNotEmpty()) {
                false
            } else {
                terminal = true
                pendingVisibleDisconnect?.cancel(false)
                pendingVisibleDisconnect = null
                true
            }
        }
        if (shouldStop) finishStop()
    }

    private fun finishStop() {
        if (running.getAndSet(false)) {
            runCatching { socket?.close() }
            socket = null
            worker?.interrupt()
            worker = null
        }
        CustomerDisplayConnectionEventLog.record(
            CustomerDisplayConnectionEventType.STOPPED,
            "画面利用終了により接続セッションを停止",
        )
        onStopped(this)
    }

    private fun startWorker() {
        if (!running.compareAndSet(false, true)) return
        CustomerDisplayConnectionEventLog.record(
            CustomerDisplayConnectionEventType.STARTING,
            "${settings.host}:${settings.port} へ接続開始",
        )
        worker = thread(
            start = true,
            isDaemon = true,
            name = "tsuguregi-cd-websocket",
        ) {
            reconnectLoop()
        }
    }

    private fun reconnectLoop() {
        var failedAttempts = 0
        while (running.get()) {
            val reason = runCatching {
                connectAndRead {
                    notifyConnected()
                }
                "接続が終了しました"
            }.exceptionOrNull()?.message ?: "接続が終了しました"
            if (!running.get()) return

            scheduleDisconnectedPresentation(reason)
            val delayMillis = CustomerDisplayConnectionPolicy.retryDelayMillis(
                failedAttempts = failedAttempts,
                hadEstablishedConnection = everEstablishedConnection,
            )
            failedAttempts = CustomerDisplayConnectionPolicy.nextFailedAttemptCount(
                failedAttempts = failedAttempts,
                hadEstablishedConnection = everEstablishedConnection,
            )
            try {
                Thread.sleep(delayMillis)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun connectAndRead(onHandshakeComplete: () -> Unit) {
        require(settings.isConfigured) { "接続先IPとトークンを設定してください" }
        val client = Socket()
        socket = client
        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = CustomerDisplayConnectionPolicy.CONNECT_TIMEOUT_MS
            client.connect(
                InetSocketAddress(settings.host, settings.port),
                CustomerDisplayConnectionPolicy.CONNECT_TIMEOUT_MS,
            )
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

            client.soTimeout = CustomerDisplayConnectionPolicy.HEARTBEAT_INTERVAL_MS
            onHandshakeComplete()
            readFrames(input, output)
        } finally {
            runCatching { client.close() }
            if (socket === client) socket = null
        }
    }

    private fun readFrames(input: InputStream, output: OutputStream) {
        var missedHeartbeats = 0
        while (running.get()) {
            val first = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                if (missedHeartbeats >= CustomerDisplayConnectionPolicy.MAX_MISSED_HEARTBEATS) {
                    throw EOFException("レジから一定時間応答がありません")
                }
                writeMaskedFrame(output, 0x9, HEARTBEAT_PAYLOAD)
                missedHeartbeats++
                continue
            }
            if (first < 0) throw EOFException("レジとの接続が切れました")
            val second = input.read()
            if (second < 0) throw EOFException("レジとの接続が切れました")
            missedHeartbeats = 0

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
                payload.indices.forEach { index ->
                    payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                }
            }
            when (opcode) {
                0x1 -> {
                    require(finalFrame) { "分割WebSocketフレームには未対応です" }
                    val json = payload.toString(StandardCharsets.UTF_8)
                    notifySnapshot(CustomerDisplaySnapshot.parse(json))
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

    private fun notifyConnected() {
        val currentListeners = synchronized(listenerLock) {
            visibility.onConnected()
            everEstablishedConnection = true
            pendingVisibleDisconnect?.cancel(false)
            pendingVisibleDisconnect = null
            listeners.values.toList()
        }
        CustomerDisplayConnectionEventLog.record(
            CustomerDisplayConnectionEventType.CONNECTED,
            "${settings.host}:${settings.port} へ接続",
        )
        currentListeners.forEach { listener -> safeCall(listener.onConnected) }
    }

    private fun notifySnapshot(snapshot: CustomerDisplaySnapshot) {
        val result = synchronized(listenerLock) {
            latestSnapshot = snapshot
            visibility.onSnapshot()
            everEstablishedConnection = true
            pendingVisibleDisconnect?.cancel(false)
            pendingVisibleDisconnect = null
            val changed = lastLoggedMode != snapshot.mode
            lastLoggedMode = snapshot.mode
            changed to listeners.values.toList()
        }
        if (result.first) {
            CustomerDisplayConnectionEventLog.record(
                CustomerDisplayConnectionEventType.MODE_CHANGED,
                "表示=${snapshot.mode.name} sequence=${snapshot.sequence}",
            )
        }
        result.second.forEach { listener -> safeCall { listener.onSnapshot(snapshot) } }
    }

    private fun scheduleDisconnectedPresentation(reason: String) {
        val result = synchronized(listenerLock) {
            pendingVisibleDisconnect?.cancel(false)
            pendingVisibleDisconnect = null
            val decision = visibility.onTransportLost(reason)
            val immediateListeners = if (decision.notifyImmediately) listeners.values.toList() else emptyList()
            if (!decision.notifyImmediately) {
                pendingVisibleDisconnect = scheduler.schedule(
                    { revealDisconnectedIfStillCurrent(decision.generation, reason) },
                    decision.delayMillis,
                    TimeUnit.MILLISECONDS,
                )
            }
            decision to immediateListeners
        }
        val decision = result.first
        if (decision.delayMillis > 0L) {
            CustomerDisplayConnectionEventLog.record(
                CustomerDisplayConnectionEventType.TRANSIENT_LOSS,
                "${decision.delayMillis}msは直前画面を維持: ${reason.take(120)}",
            )
        } else {
            CustomerDisplayConnectionEventLog.record(
                CustomerDisplayConnectionEventType.DISCONNECTED,
                reason,
            )
            result.second.forEach { listener -> safeCall { listener.onDisconnected(reason) } }
        }
    }

    private fun revealDisconnectedIfStillCurrent(generation: Long, reason: String) {
        val currentListeners = synchronized(listenerLock) {
            if (terminal || !visibility.revealDisconnectedIfCurrent(generation)) return
            pendingVisibleDisconnect = null
            listeners.values.toList()
        }
        CustomerDisplayConnectionEventLog.record(
            CustomerDisplayConnectionEventType.DISCONNECTED,
            "猶予後も復旧せず表示切替: ${reason.take(160)}",
        )
        currentListeners.forEach { listener -> safeCall { listener.onDisconnected(reason) } }
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
            if (index <= 0) {
                null
            } else {
                line.substring(0, index).trim().lowercase(Locale.ROOT) to line.substring(index + 1).trim()
            }
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
        payload.indices.forEach { index ->
            output.write(payload[index].toInt() xor mask[index % 4].toInt())
        }
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

    private fun safeCall(block: () -> Unit) {
        runCatching(block)
    }

    companion object {
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val MAX_FRAME_BYTES = 1_048_576L
        private val HEARTBEAT_PAYLOAD = "tsuguregi".toByteArray(StandardCharsets.UTF_8)
    }
}
