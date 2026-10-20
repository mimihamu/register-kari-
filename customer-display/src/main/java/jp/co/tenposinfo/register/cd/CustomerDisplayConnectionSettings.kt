package jp.co.tenposinfo.register.cd

import android.content.Context
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID

data class CustomerDisplayConnectionSettings(
    val host: String,
    val port: Int,
    val token: String,
    val autoConnect: Boolean,
    val displayDeviceId: String = "",
) {
    init {
        require(port in 1024..65535) { "port must be between 1024 and 65535" }
    }

    val isConfigured: Boolean get() =
        token.length >= 16 && CustomerDisplayPrivateLanHostPolicy.isPermitted(host)
    val socketHost: String get() = host.trim().removePrefix("[").removeSuffix("]")
    val httpHost: String get() = if (socketHost.contains(':')) "[$socketHost]:$port" else "$socketHost:$port"
    val displayAddress: String get() = "$httpHost$CUSTOMER_DISPLAY_PATH"
}

/**
 * v2.5 CDP-NET-001: the initial release may only connect inside the private
 * LAN. Numeric-only parsing intentionally avoids DNS rebinding and prevents
 * accidentally sending the bearer token to an external hostname.
 */
internal object CustomerDisplayPrivateLanHostPolicy {
    fun isPermitted(host: String): Boolean {
        val literal = host.trim()
        if (literal.isEmpty() || literal.length > 80) return false

        // Link-local IPv6 is supported for environments where discovery returns
        // a scoped address (e.g. fe80::1234%wlan0). Global/ULA/loopback are not.
        if (literal.contains(':')) {
            val unbracketed = literal.removePrefix("[").removeSuffix("]")
            // Never ignore arbitrary bytes following a zone separator:
            // Host headers must not contain CR/LF or a second HTTP header.
            if (unbracketed.count { it == '%' } > 1) return false
            val zone = unbracketed.substringAfter('%', "")
            if (unbracketed.contains('%') && (
                zone.isEmpty() || zone.length > 24 ||
                !zone.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            )) return false
            if (literal.contains('[') != literal.contains(']')) return false
            val bareAddress = unbracketed.substringBefore('%')
            if (!bareAddress.contains(':')) return false
            val parsed = runCatching { InetAddress.getByName(bareAddress) }.getOrNull()
            return parsed is Inet6Address && parsed.isLinkLocalAddress
        }

        val parts = literal.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 ||
                (part.length > 1 && part.startsWith('0')) ||
                !part.all { it in '0'..'9' }
            ) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }
}

class CustomerDisplayConnectionSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CustomerDisplayConnectionSettings = CustomerDisplayConnectionSettings(
        host = preferences.getString(KEY_HOST, "").orEmpty().trim(),
        port = preferences.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535),
        token = preferences.getString(KEY_TOKEN, "").orEmpty().trim(),
        autoConnect = preferences.getBoolean(KEY_AUTO_CONNECT, true),
        displayDeviceId = persistentId(),
    )

    fun save(settings: CustomerDisplayConnectionSettings) {
        preferences.edit()
            .putString(KEY_HOST, settings.host.trim())
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_TOKEN, settings.token.trim())
            .putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            .putString(KEY_DISPLAY_DEVICE_ID, settings.displayDeviceId.ifBlank { persistentId() })
            .apply()
    }

    private fun persistentId(): String {
        val current = preferences.getString(KEY_DISPLAY_DEVICE_ID, null)
            ?.takeIf { it.isNotBlank() }
        if (current != null) return current
        return UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DISPLAY_DEVICE_ID, it).apply()
        }
    }

    companion object {
        const val DEFAULT_PORT = 18080
        private const val PREFS_NAME = "customer_display_connection"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_DISPLAY_DEVICE_ID = "display_device_id"
    }
}
