package jp.co.tenposinfo.register

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.util.Locale

internal const val CUSTOMER_DISPLAY_PAIRING_SERVICE_TYPE = "_tsuguregi-cd._tcp."
internal const val CUSTOMER_DISPLAY_PAIRING_WINDOW_MS = 120_000L

internal enum class CustomerDisplayPairingStatus {
    IDLE,
    STARTING,
    ACTIVE,
}

internal data class CustomerDisplayPairingState(
    val status: CustomerDisplayPairingStatus = CustomerDisplayPairingStatus.IDLE,
    val serviceName: String? = null,
    val endsAtMillis: Long = 0L,
    val message: String? = null,
)

internal object CustomerDisplayPairingCodec {
    const val ATTRIBUTE_SCHEMA = "schema"
    const val ATTRIBUTE_STORE = "store"
    const val ATTRIBUTE_PATH = "path"
    const val ATTRIBUTE_TOKEN = "token"

    fun attributes(config: CustomerDisplayServerConfig): Map<String, String> = linkedMapOf(
        ATTRIBUTE_SCHEMA to CUSTOMER_DISPLAY_SCHEMA_VERSION.toString(),
        ATTRIBUTE_STORE to config.storeName.take(60),
        ATTRIBUTE_PATH to config.path,
        ATTRIBUTE_TOKEN to config.token,
    )

    fun serviceName(storeName: String): String {
        val normalized = storeName
            .trim()
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(36)
            .ifEmpty { CustomerDisplaySettingsStore.DEFAULT_STORE_NAME }
        return "つぐレジ-$normalized"
    }
}

internal class CustomerDisplayPairingAdvertiser(
    context: Context,
    private val onStateChanged: (CustomerDisplayPairingState) -> Unit,
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.RegistrationListener? = null
    private var registered = false
    private val stopRunnable = Runnable { stop("ペアリング受付を終了しました") }

    fun start(config: CustomerDisplayServerConfig) {
        stop(null)
        val endsAt = System.currentTimeMillis() + CUSTOMER_DISPLAY_PAIRING_WINDOW_MS
        onStateChanged(
            CustomerDisplayPairingState(
                status = CustomerDisplayPairingStatus.STARTING,
                endsAtMillis = endsAt,
                message = "ペアリング受付を開始しています",
            ),
        )

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = CustomerDisplayPairingCodec.serviceName(config.storeName)
            serviceType = CUSTOMER_DISPLAY_PAIRING_SERVICE_TYPE
            port = config.port
            CustomerDisplayPairingCodec.attributes(config).forEach { (key, value) ->
                setAttribute(key, value.toByteArray(StandardCharsets.UTF_8))
            }
        }

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) {
                registered = true
                handler.removeCallbacks(stopRunnable)
                handler.postDelayed(stopRunnable, CUSTOMER_DISPLAY_PAIRING_WINDOW_MS)
                onStateChanged(
                    CustomerDisplayPairingState(
                        status = CustomerDisplayPairingStatus.ACTIVE,
                        serviceName = service.serviceName,
                        endsAtMillis = endsAt,
                        message = "つぐレジ CDから［レジを探す］を押してください",
                    ),
                )
            }

            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                registered = false
                listener = null
                onStateChanged(
                    CustomerDisplayPairingState(
                        status = CustomerDisplayPairingStatus.IDLE,
                        message = "ペアリング受付を開始できませんでした（$errorCode）",
                    ),
                )
            }

            override fun onServiceUnregistered(service: NsdServiceInfo) {
                registered = false
            }

            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                registered = false
            }
        }
        listener = registrationListener
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure { error ->
            listener = null
            registered = false
            onStateChanged(
                CustomerDisplayPairingState(
                    status = CustomerDisplayPairingStatus.IDLE,
                    message = error.message ?: "ペアリング受付を開始できませんでした",
                ),
            )
        }
    }

    fun stop(message: String? = "ペアリング受付を停止しました") {
        handler.removeCallbacks(stopRunnable)
        val current = listener
        listener = null
        if (current != null && registered) {
            runCatching { nsdManager.unregisterService(current) }
        }
        registered = false
        onStateChanged(
            CustomerDisplayPairingState(
                status = CustomerDisplayPairingStatus.IDLE,
                message = message,
            ),
        )
    }
}
