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

    fun ensureStarted(context: Context) {
        applySettings(context, CustomerDisplaySettingsStore(context).load())
    }

    fun applySettings(context: Context, config: CustomerDisplayServerConfig) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val existing = currentConfig
            if (existing == config && ((config.enabled && server != null) || (!config.enabled && server == null))) return
            stopLocked()
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
                onError = { message -> lastError = message },
                onClientCountChanged = { count ->
                    connectedClients = count
                    lastClientCountChangedAt = System.currentTimeMillis()
                },
            )
            server = newServer
            newServer.start()
            poller = CustomerDisplayPoller(
                context = appContext,
                config = config,
                publish = ::publish,
                onError = { message -> lastError = message },
            ).also { it.start() }
        }
    }

    fun publish(snapshot: CustomerDisplaySnapshot) {
        synchronized(lock) {
            // Calls arrive from both the cart poller and checkout UI.
            // Assign sequence, replace latest, and enqueue delivery in one order.
            val previousMode = latestSnapshot.mode
            val newSession = when {
                snapshot.mode == CustomerDisplayMode.STANDBY &&
                    previousMode != CustomerDisplayMode.STANDBY -> true
                snapshot.mode != CustomerDisplayMode.STANDBY &&
                    previousMode in setOf(
                        CustomerDisplayMode.STANDBY,
                        CustomerDisplayMode.COMPLETE,
                        CustomerDisplayMode.DISCONNECTED,
                    ) -> true
                else -> false
            }
            if (newSession) displaySessionId = UUID.randomUUID().toString()
            val normalized = snapshot.copy(
                sequence = sequence.incrementAndGet(),
                serverInstanceId = serverInstanceId,
                sentAtMillis = System.currentTimeMillis(),
                sessionId = displaySessionId,
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
        poller?.stop()
        poller = null
        server?.stop()
        server = null
        connectedClients = 0
        lastClientCountChangedAt = System.currentTimeMillis()
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
    private var latestProductId: String? = null
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
        val now = System.currentTimeMillis()

        if (!initialized) {
            initialized = true
            previousCart = cart
            lastCartFingerprint = fingerprint(cart)
            lastSeenSaleId = latestSale?.id ?: 0L
            if (cart.isEmpty()) {
                publish(CustomerDisplaySnapshotFactory.standby(config.storeName, config.presentation))
            } else {
                latestProductId = cart.lastOrNull()?.product?.id
                publish(CustomerDisplaySnapshotFactory.sales(cart, config.storeName, latestProductId, config.presentation))
            }
            return
        }

        val cartFingerprint = fingerprint(cart)
        if (cart.isNotEmpty()) {
            completeUntil = 0L
            changeUntil = 0L
            changeSnapshot = null
            val detectedLatest = detectLatestProduct(previousCart, cart)
            if (detectedLatest != null) latestProductId = detectedLatest
            if (cartFingerprint != lastCartFingerprint) {
                publish(CustomerDisplaySnapshotFactory.sales(cart, config.storeName, latestProductId, config.presentation))
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
                latestProductId = null
            } else if (previousCart.isNotEmpty() && saleId <= lastSeenSaleId && completeUntil == 0L) {
                publish(CustomerDisplaySnapshotFactory.standby(config.storeName, config.presentation))
                latestProductId = null
            }
            lastSeenSaleId = maxOf(lastSeenSaleId, saleId)
        }

        previousCart = cart
        lastCartFingerprint = cartFingerprint
    }

    private fun detectLatestProduct(before: List<CartItem>, after: List<CartItem>): String? {
        val beforeById = before.associateBy { it.product.id }
        return after.lastOrNull { current ->
            val old = beforeById[current.product.id]
            old == null || old.quantityHundredths != current.quantityHundredths || old.unitPrice != current.unitPrice || old.discountAmount != current.discountAmount
        }?.product?.id
    }

    private fun fingerprint(items: List<CartItem>): String = items.joinToString("|") { item ->
        listOf(
            item.product.id,
            item.product.name,
            item.quantity,
            item.quantityHundredths,
            item.unitPrice,
            item.discountAmount,
            item.note,
            item.product.taxKey,
            item.product.taxRatePercent,
            item.product.taxIncluded,
        ).joinToString(":")
    }

    companion object {
        private const val POLL_INTERVAL_MS = 300L
    }
}
