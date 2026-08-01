package jp.co.tenposinfo.register

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

data class CustomerDisplayServerConfig(
    val enabled: Boolean,
    val port: Int,
    val path: String,
    val token: String,
    val storeName: String,
    val completeSeconds: Int,
) {
    init {
        require(port in 1024..65535) { "port must be between 1024 and 65535" }
        require(path.startsWith('/')) { "path must start with /" }
        require(token.length >= 16) { "token is too short" }
        require(completeSeconds in 1..30) { "completeSeconds must be between 1 and 30" }
    }

    fun connectionUrl(host: String): String = "ws://$host:$port$path?token=$token"
}

class CustomerDisplaySettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CustomerDisplayServerConfig {
        val token = preferences.getString(KEY_TOKEN, null)
            ?.takeIf { it.length >= 16 }
            ?: generateToken().also { preferences.edit().putString(KEY_TOKEN, it).apply() }
        return CustomerDisplayServerConfig(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            port = preferences.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535),
            path = CUSTOMER_DISPLAY_PATH,
            token = token,
            storeName = preferences.getString(KEY_STORE_NAME, DEFAULT_STORE_NAME)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_STORE_NAME,
            completeSeconds = preferences.getInt(KEY_COMPLETE_SECONDS, DEFAULT_COMPLETE_SECONDS).coerceIn(1, 30),
        )
    }

    fun save(config: CustomerDisplayServerConfig) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_STORE_NAME, config.storeName.trim().ifEmpty { DEFAULT_STORE_NAME })
            .putInt(KEY_COMPLETE_SECONDS, config.completeSeconds)
            .apply()
    }

    fun regenerateToken(): String = generateToken().also {
        preferences.edit().putString(KEY_TOKEN, it).apply()
    }

    companion object {
        const val DEFAULT_PORT = 18080
        const val DEFAULT_STORE_NAME = "つぐレジ"
        const val DEFAULT_COMPLETE_SECONDS = 5

        private const val PREFS_NAME = "customer_display_server"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_STORE_NAME = "store_name"
        private const val KEY_COMPLETE_SECONDS = "complete_seconds"

        fun generateToken(): String = UUID.randomUUID().toString().replace("-", "")

        fun localIpv4Addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .map { it.hostAddress.orEmpty() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }
}
