package jp.co.tenposinfo.register

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object CustomerDisplayRuntime {
    private val lock = Any()
    private val sequence = AtomicLong(System.currentTimeMillis())

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
            latestSnapshot = latestSnapshot.copy(storeName = config.storeName)
            lastError = null
            connectedClients = 0
            if (!config.enabled) return

            val newServer = CustomerDisplayWebSocketServer(
                config = config,
                latestPayload = { latestSnapshot.toJson() },
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
        val normalized = snapshot.copy(sequence = sequence.incrementAndGet())
        latestSnapshot = normalized
        server?.broadcast(normalized.toJson())
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
                publish(CustomerDisplaySnapshotFactory.standby(config.storeName))
            } else {
                latestProductId = cart.lastOrNull()?.product?.id
                publish(CustomerDisplaySnapshotFactory.sales(cart, config.storeName, latestProductId))
            }
            return
        }

        val cartFingerprint = fingerprint(cart)
        if (cart.isNotEmpty()) {
            completeUntil = 0L
            val detectedLatest = detectLatestProduct(previousCart, cart)
            if (detectedLatest != null) latestProductId = detectedLatest
            if (cartFingerprint != lastCartFingerprint) {
                publish(CustomerDisplaySnapshotFactory.sales(cart, config.storeName, latestProductId))
            }
        } else {
            val saleId = latestSale?.id ?: 0L
            if (saleId > lastSeenSaleId && saleId != lastPublishedSaleId) {
                val detail = database.loadSaleDetail(saleId)
                if (detail != null) {
                    publish(CustomerDisplaySnapshotFactory.complete(detail, config.storeName))
                    lastPublishedSaleId = saleId
                    completeUntil = now + config.completeSeconds * 1_000L
                }
            } else if (completeUntil > 0L && now >= completeUntil) {
                publish(CustomerDisplaySnapshotFactory.standby(config.storeName))
                completeUntil = 0L
                latestProductId = null
            } else if (previousCart.isNotEmpty() && saleId <= lastSeenSaleId && completeUntil == 0L) {
                publish(CustomerDisplaySnapshotFactory.standby(config.storeName))
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
            old == null || old.quantity != current.quantity || old.unitPrice != current.unitPrice || old.discountAmount != current.discountAmount
        }?.product?.id
    }

    private fun fingerprint(items: List<CartItem>): String = items.joinToString("|") { item ->
        listOf(
            item.product.id,
            item.product.name,
            item.quantity,
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
