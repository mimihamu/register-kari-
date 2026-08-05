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
    val presentation: CustomerDisplayPresentation = CustomerDisplayPresentation(),
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
            presentation = CustomerDisplayPresentation(
                theme = runCatching {
                    CustomerDisplayTheme.valueOf(
                        preferences.getString(KEY_THEME, CustomerDisplayTheme.NAVY.name)
                            ?: CustomerDisplayTheme.NAVY.name,
                    )
                }.getOrDefault(CustomerDisplayTheme.NAVY),
                showLogo = preferences.getBoolean(KEY_SHOW_LOGO, true),
                logoText = preferences.getString(KEY_LOGO_TEXT, "つぐ").orEmpty().take(12),
                textScalePercent = preferences.getInt(KEY_TEXT_SCALE, 100).coerceIn(80, 150),
                maxVisibleRows = preferences.getInt(KEY_MAX_ROWS, 8).coerceIn(3, 20),
                rowSpacingDp = preferences.getInt(KEY_ROW_SPACING, 8).coerceIn(0, 24),
                showCancelledItems = preferences.getBoolean(KEY_SHOW_CANCELLED, true),
                showTaxSymbol = preferences.getBoolean(KEY_SHOW_TAX, true),
                standbyMessage = preferences.getString(KEY_STANDBY_MESSAGE, "いらっしゃいませ")
                    .orEmpty()
                    .take(80),
            ),
        )
    }

    fun save(config: CustomerDisplayServerConfig) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_STORE_NAME, config.storeName.trim().ifEmpty { DEFAULT_STORE_NAME })
            .putInt(KEY_COMPLETE_SECONDS, config.completeSeconds)
            .putString(KEY_THEME, config.presentation.theme.name)
            .putBoolean(KEY_SHOW_LOGO, config.presentation.showLogo)
            .putString(KEY_LOGO_TEXT, config.presentation.logoText)
            .putInt(KEY_TEXT_SCALE, config.presentation.textScalePercent)
            .putInt(KEY_MAX_ROWS, config.presentation.maxVisibleRows)
            .putInt(KEY_ROW_SPACING, config.presentation.rowSpacingDp)
            .putBoolean(KEY_SHOW_CANCELLED, config.presentation.showCancelledItems)
            .putBoolean(KEY_SHOW_TAX, config.presentation.showTaxSymbol)
            .putString(KEY_STANDBY_MESSAGE, config.presentation.standbyMessage)
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
        private const val KEY_THEME = "presentation_theme"
        private const val KEY_SHOW_LOGO = "presentation_show_logo"
        private const val KEY_LOGO_TEXT = "presentation_logo_text"
        private const val KEY_TEXT_SCALE = "presentation_text_scale"
        private const val KEY_MAX_ROWS = "presentation_max_rows"
        private const val KEY_ROW_SPACING = "presentation_row_spacing"
        private const val KEY_SHOW_CANCELLED = "presentation_show_cancelled"
        private const val KEY_SHOW_TAX = "presentation_show_tax"
        private const val KEY_STANDBY_MESSAGE = "presentation_standby_message"

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
