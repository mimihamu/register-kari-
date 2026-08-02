package jp.co.tenposinfo.register.cd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

internal const val CUSTOMER_DISPLAY_PAIRING_SERVICE_TYPE = "_tsuguregi-cd._tcp."
internal const val CUSTOMER_DISPLAY_DISCOVERY_TIMEOUT_MS = 12_000L

internal data class DiscoveredRegister(
    val serviceName: String,
    val storeName: String,
    val host: String,
    val port: Int,
    val token: String,
    val path: String,
    val schemaVersion: Int,
) {
    val identity: String get() = "$serviceName|$host|$port"

    fun toConnectionSettings(): CustomerDisplayConnectionSettings = CustomerDisplayConnectionSettings(
        host = host,
        port = port,
        token = token,
        autoConnect = true,
    )
}

internal object CustomerDisplayPairingParser {
    private const val ATTRIBUTE_SCHEMA = "schema"
    private const val ATTRIBUTE_STORE = "store"
    private const val ATTRIBUTE_PATH = "path"
    private const val ATTRIBUTE_TOKEN = "token"

    fun parse(
        serviceName: String,
        host: String,
        port: Int,
        attributes: Map<String, ByteArray>,
    ): DiscoveredRegister? {
        if (serviceName.isBlank() || host.isBlank() || port !in 1024..65535) return null
        val schema = attributes.stringValue(ATTRIBUTE_SCHEMA)?.toIntOrNull() ?: return null
        if (schema != CUSTOMER_DISPLAY_SCHEMA_VERSION) return null
        val path = attributes.stringValue(ATTRIBUTE_PATH) ?: return null
        if (path != CUSTOMER_DISPLAY_PATH) return null
        val token = attributes.stringValue(ATTRIBUTE_TOKEN) ?: return null
        if (token.length < 16) return null
        val store = attributes.stringValue(ATTRIBUTE_STORE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: serviceName
        return DiscoveredRegister(
            serviceName = serviceName,
            storeName = store,
            host = host,
            port = port,
            token = token,
            path = path,
            schemaVersion = schema,
        )
    }

    private fun Map<String, ByteArray>.stringValue(key: String): String? =
        get(key)?.toString(StandardCharsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
}

internal enum class CustomerDisplayDiscoveryStatus {
    IDLE,
    SEARCHING,
    FINISHED,
}

internal data class CustomerDisplayDiscoveryState(
    val status: CustomerDisplayDiscoveryStatus = CustomerDisplayDiscoveryStatus.IDLE,
    val message: String? = null,
)

internal object CustomerDisplayNsdServiceType {
    fun matches(value: String?): Boolean =
        value?.trim()?.trimEnd('.') == CUSTOMER_DISPLAY_PAIRING_SERVICE_TYPE.trimEnd('.')
}

@Suppress("DEPRECATION")
internal class CustomerDisplayNsdDiscovery(
    context: Context,
    private val onStateChanged: (CustomerDisplayDiscoveryState) -> Unit,
    private val onFound: (DiscoveredRegister) -> Unit,
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val pendingServices = ArrayDeque<NsdServiceInfo>()
    private val queuedIdentities = mutableSetOf<String>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolving = false
    private var running = false
    private val timeoutRunnable = Runnable { stop("検索を終了しました") }

    fun start() {
        stop(null)
        running = true
        pendingServices.clear()
        queuedIdentities.clear()
        resolving = false
        multicastLock = runCatching {
            wifiManager.createMulticastLock("tsuguregi-cd-nsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                emit(CustomerDisplayDiscoveryState(CustomerDisplayDiscoveryStatus.SEARCHING, "同じWi-Fiのつぐレジを検索中です"))
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                handler.post {
                    if (!running || !CustomerDisplayNsdServiceType.matches(service.serviceType)) return@post
                    val identity = "${service.serviceName}|${service.serviceType}"
                    if (queuedIdentities.add(identity)) {
                        pendingServices.addLast(service)
                        resolveNext()
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                handler.post { running = false }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post { stop("レジを検索できませんでした（$errorCode）") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post { running = false }
            }
        }
        discoveryListener = listener
        emit(CustomerDisplayDiscoveryState(CustomerDisplayDiscoveryStatus.SEARCHING, "同じWi-Fiのつぐレジを検索しています"))
        runCatching {
            nsdManager.discoverServices(
                CUSTOMER_DISPLAY_PAIRING_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
            handler.postDelayed(timeoutRunnable, CUSTOMER_DISPLAY_DISCOVERY_TIMEOUT_MS)
        }.onFailure { error ->
            stop(error.message ?: "レジを検索できませんでした")
        }
    }

    fun stop(message: String? = null) {
        handler.removeCallbacks(timeoutRunnable)
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null && running) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        running = false
        pendingServices.clear()
        queuedIdentities.clear()
        resolving = false
        multicastLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        multicastLock = null
        if (message != null) {
            emit(CustomerDisplayDiscoveryState(CustomerDisplayDiscoveryStatus.FINISHED, message))
        }
    }

    private fun resolveNext() {
        if (!running || resolving || pendingServices.isEmpty()) return
        val service = pendingServices.removeFirst()
        resolving = true
        runCatching {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    handler.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handler.post {
                        val host = serviceInfo.host?.hostAddress.orEmpty()
                        CustomerDisplayPairingParser.parse(
                            serviceName = serviceInfo.serviceName.orEmpty(),
                            host = host,
                            port = serviceInfo.port,
                            attributes = serviceInfo.attributes,
                        )?.let(onFound)
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }.onFailure {
            resolving = false
            resolveNext()
        }
    }

    private fun emit(state: CustomerDisplayDiscoveryState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged(state)
        } else {
            handler.post { onStateChanged(state) }
        }
    }
}
