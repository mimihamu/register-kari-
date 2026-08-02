package jp.co.tenposinfo.register.cd

import android.content.Context

data class CustomerDisplayConnectionSettings(
    val host: String,
    val port: Int,
    val token: String,
    val autoConnect: Boolean,
) {
    init {
        require(port in 1024..65535) { "port must be between 1024 and 65535" }
    }

    val isConfigured: Boolean get() = host.isNotBlank() && token.length >= 16
    val displayAddress: String get() = "$host:$port$CUSTOMER_DISPLAY_PATH"
}

class CustomerDisplayConnectionSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CustomerDisplayConnectionSettings = CustomerDisplayConnectionSettings(
        host = preferences.getString(KEY_HOST, "").orEmpty().trim(),
        port = preferences.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535),
        token = preferences.getString(KEY_TOKEN, "").orEmpty().trim(),
        autoConnect = preferences.getBoolean(KEY_AUTO_CONNECT, true),
    )

    fun save(settings: CustomerDisplayConnectionSettings) {
        preferences.edit()
            .putString(KEY_HOST, settings.host.trim())
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_TOKEN, settings.token.trim())
            .putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            .apply()
    }

    companion object {
        const val DEFAULT_PORT = 18080
        private const val PREFS_NAME = "customer_display_connection"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_AUTO_CONNECT = "auto_connect"
    }
}
