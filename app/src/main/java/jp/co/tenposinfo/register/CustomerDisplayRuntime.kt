package jp.co.tenposinfo.register

import android.content.Context
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object CustomerDisplayRuntime {
    private val lock = Any()
    private val sequence = AtomicLong(System.currentTimeMillis())
    private val serverInstanceId = UUID.randomUUID().toString()
    private var displaySessionId = UUID.randomUUID().toString()

    @Volatile
    private var currentConfig: CustomerDisplayServerConfig? = null
    @Volatile
    private var latestSnapshot: CustomerDisplaySnapshot = CustomerDisplaySnapshotFactory.standby(CustomerDisplaySettingsStore.DEFAULT_STORE_NAME)
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var connectedClients: Int = 0
    @Volatile
    private var lastClientCountChangedAt: Long = 0L
    private var server: CustomerDisplayWebSocketServer? = null
    private var poller: CustomerDisplayPoller? = null
    // A stopped poller may finish an in-flight database read after reconfigure.
    // Its final callback must never publish into the newly configured server.
    @Volatile private var runtimeGeneration = 0L

    fun ensureStarted(context: Context) {
        applySettings(context, CustomerDisplaySettingsStore(context).load())
    }

    fun applySettings(context: Context, config: CustomerDisplayServerConfig) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val existing = currentConfig
            if (existing == config && ((config.enabled && server != null) || (!config.enabled && server == null))) return
            stopLocked()
            val generation = runtimeGeneration
            currentConfig = config
            // Never reopen a newly configured socket with the prior cash/payment
            // screen before this process has polled the current register database.
            displaySessionId = UUID.randomUUID().toString()
            latestSnapshot = CustomerDisplaySnapshotFactory.standby(
                config.storeName,
                config.presentation,
            ).copy(
                sequence = sequence.incrementAndGet(),
                serverInstanceId = serverInstanceId,
                sentAtMillis = System.currentTimeMillis(),
                sessionId = displaySessionId,
                storeId = config.storeId,
                registerTerminalId = config.registerTerminalId,
                messageType = CustomerDisplayMessageType.SNAPSHOT,
            )
            lastError = null
            connectedClients = 0
            if (!config.enabled) return

            val newServer = CustomerDisplayWebSocketServer(
                config = config,
                latestPayload = {
                    latestSnapshot.copy(messageType = CustomerDisplayMessageType.SNAPSHOT).toJson()
                },
                onError = { message ->
                    synchronized(lock) {
                        if (runtimeGeneration == generation) lastError = message
                    }
                },
                onClientCountChanged = { count ->
                    synchronized(lock) {
                        if (runtimeGeneration == generation) {
                            connectedClients = count
                            lastClientCountChangedAt = System.currentTimeMillis()
                        }
                    }
                },
            )
            server = newServer
            newServer.start()
            poller = CustomerDisplayPoller(
                context = appContext,
                config = config,
                publish = { snapshot ->
                    synchronized(lock) {
                        if (runtimeGeneration == generation) publish(snapshot)
                    }
                },
                onError = { message ->
                    synchronized(lock) {
                        if (runtimeGeneration == generation) lastError = message
                    }
                },
            ).also { it.start() }
        }
    }

    fun publish(snapshot: CustomerDisplaySnapshot) {
        synchronized(lock) {
            // Calls arrive from both the cart poller and checkout UI.
            // Assign sequence, replace latest, and enqueue delivery in one order.
            val newSession = CustomerDisplaySessionBoundary.startsNewSession(
                latestSnapshot, snapshot,
            )
            if (newSession) displaySessionId = UUID.randomUUID().toString()
            val normalized = snapshot.copy(
                sequence = sequence.incrementAndGet(),
                serverInstanceId = serverInstanceId,
                sentAtMillis = System.currentTimeMillis(),
                sessionId = displaySessionId,
                storeId = currentConfig?.storeId ?: snapshot.storeId,
                registerTerminalId = currentConfig?.registerTerminalId ?: snapshot.registerTerminalId,
                messageType = if (newSession) CustomerDisplayMessageType.SNAPSHOT
                    else CustomerDisplayMessageType.STATE_UPDATE,
            )
            latestSnapshot = normalized
            server?.broadcast(normalized.toJson())
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
            currentConfig = null
        }
    }

    fun status(): CustomerDisplayRuntimeStatus = CustomerDisplayRuntimeStatus(
        enabled = currentConfig?.enabled == true,
        running = server != null,
        connectedClients = connectedClients,
        lastClientCountChangedAt = lastClientCountChangedAt,
        lastError = lastError,
        latestMode = latestSnapshot.mode,
        latestSequence = latestSnapshot.sequence,
        port = currentConfig?.port,
        serverInstanceId = serverInstanceId,
    )

    private fun stopLocked() {
        // Invalidate all old asynchronous callbacks *before* stopping sockets.
        runtimeGeneration++
        poller?.stop()
        poller = null
        server?.stop()
        server = null
        connectedClients = 0
        lastClientCountChangedAt = System.currentTimeMillis()
    }
}

/**
 * Session identity belongs to a transaction, not each visual phase.
 * In particular CHANGE -> COMPLETE for the same sale must keep sessionId.
 * A genuinely new sale must still start its own session.
 */
internal object CustomerDisplaySessionBoundary {
    fun startsNewSession(
        previous: CustomerDisplaySnapshot,
        next: CustomerDisplaySnapshot,
    ): Boolean = when {
        next.mode == CustomerDisplayMode.STANDBY ->
            previous.mode != CustomerDisplayMode.STANDBY
        previous.mode == CustomerDisplayMode.STANDBY ||
            previous.mode == CustomerDisplayMode.DISCONNECTED -> true
        previous.mode == CustomerDisplayMode.COMPLETE ->
            next.mode != CustomerDisplayMode.COMPLETE ||
                previous.transactionId != next.transactionId
        else -> false
    }
}

data class CustomerDisplayRuntimeStatus(
    val enabled: Boolean,
    val running: Boolean,
    val connectedClients: Int,
    val lastClientCountChangedAt: Long,
    val lastError: String?,
    val latestMode: CustomerDisplayMode,
    val latestSequence: Long,
    val port: Int?,
    val serverInstanceId: String,
)

internal class CustomerDisplayPoller(
    context: Context,
    private val config: CustomerDisplayServerConfig,
    private val publish: (CustomerDisplaySnapshot) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val database = RegisterDatabase(context.applicationContext)
    private var executor: ScheduledExecutorService? = null
    private var previousCart: List<CartItem> = emptyList()
    private var lastCartFingerprint: String? = null
    private var lastSeenSaleId: Long = 0L
    private var lastPublishedSaleId: Long = 0L
    private var completeUntil: Long = 0L
    private var changeUntil: Long = 0L
    private var changeSnapshot: CustomerDisplaySnapshot? = null
    private var latestLineId: String? = null
    private var initialized = false

    fun start() {
        if (executor != null) return
        val scheduled = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "tsuguregi-customer-display-poller").apply { isDaemon = true }
        }
        executor = scheduled
        scheduled.scheduleWithFixedDelay(::pollSafely, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
        runCatching { database.close() }
    }

    private fun pollSafely() {
        runCatching { poll() }.onFailure { error ->
            onError(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun poll() {
        val cart = database.loadCart()
        val latestSale = database.listSales(limit = 1).firstOrNull()
        // Payment display durations must survive wall-clock/NTP corrections.
        val now = android.os.SystemClock.elapsedRealtime()

        if (!initialized) {
            initialized = true
            previousCart = cart
            lastCartFingerprint = CustomerDisplayCartChangePolicy.fingerprint(cart)
            lastSeenSaleId = latestSale?.id ?: 0L
            if (cart.isEmpty()) {
                publish(CustomerDisplaySnapshotFactory.standby(config.storeName, config.presentation))
            } else {
                latestLineId = cart.lastOrNull()?.lineId?.takeIf { it.isNotBlank() }
                    ?: "legacy-line-${cart.size}"
                publish(CustomerDisplaySnapshotFactory.sales(
                    items = cart, storeName = config.storeName,
                    presentation = config.presentation, latestLineId = latestLineId,
                ))
            }
            return
        }

        val cartFingerprint = CustomerDisplayCartChangePolicy.fingerprint(cart)
        if (cart.isNotEmpty()) {
            completeUntil = 0L
            changeUntil = 0L
            changeSnapshot = null
            if (cartFingerprint != lastCartFingerprint) {
                latestLineId = CustomerDisplayCartChangePolicy.latestChangedLineId(previousCart, cart)
                publish(CustomerDisplaySnapshotFactory.sales(
                    items = cart, storeName = config.storeName,
                    presentation = config.presentation, latestLineId = latestLineId,
                ))
            }
        } else {
            val saleId = latestSale?.id ?: 0L
            if (saleId > lastSeenSaleId && saleId != lastPublishedSaleId) {
                val detail = database.loadSaleDetail(saleId)
                if (detail != null) {
                    publish(CustomerDisplaySnapshotFactory.complete(detail, config.storeName, config.presentation))
                    lastPublishedSaleId = saleId
                    if (detail.summary.changeAmount > 0L) {
                        changeSnapshot = CustomerDisplaySnapshotFactory.complete(detail, config.storeName, config.presentation)
                        changeUntil = now + config.changeSeconds * 1_000L
                        completeUntil = 0L
                    } else {
                        changeUntil = 0L
                        changeSnapshot = null
                        completeUntil = now + config.completeSeconds * 1_000L
                    }
                }
            } else if (changeUntil > 0L && now >= changeUntil) {
                // The money is now settled; switch to a thank-you view rather than
                // continuing to show change indefinitely during the COMPLETE phase.
                val completed = changeSnapshot?.copy(
                    changeAmount = 0L,
                    message = "ありがとうございました",
                )
                if (completed != null) publish(completed)
                changeSnapshot = null
                changeUntil = 0L
                completeUntil = now + config.completeSeconds * 1_000L
            } else if (completeUntil > 0L && now >= completeUntil) {
                publish(CustomerDisplaySnapshotFactory.standby(config.storeName, config.presentation))
                completeUntil = 0L
                latestLineId = null
            } else if (previousCart.isNotEmpty() && saleId <= lastSeenSaleId && completeUntil == 0L) {
                publish(CustomerDisplaySnapshotFactory.standby(config.storeName, config.presentation))
                latestLineId = null
            }
            lastSeenSaleId = maxOf(lastSeenSaleId, saleId)
        }

        previousCart = cart
        lastCartFingerprint = cartFingerprint
    }

    companion object {
        private const val POLL_INTERVAL_MS = 300L
    }
}
