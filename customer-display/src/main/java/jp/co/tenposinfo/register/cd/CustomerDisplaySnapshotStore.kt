package jp.co.tenposinfo.register.cd

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * A callback queued on the Android main thread remains pending even after its
 * socket was closed. An epoch token must be current at the moment of applying
 * or persisting the snapshot, not merely when the network thread receives it.
 */
internal class CustomerDisplayConnectionEpochGuard {
    private val epoch = AtomicLong(0L)

    fun start(): Long = epoch.incrementAndGet()

    fun accepts(candidate: Long): Boolean = candidate == epoch.get()

    fun invalidate(candidate: Long) {
        epoch.compareAndSet(candidate, candidate + 1L)
    }

    fun invalidateCurrent() {
        epoch.incrementAndGet()
    }
}

internal object CustomerDisplaySnapshotTargetPolicy {
    fun fingerprint(settings: CustomerDisplayConnectionSettings): String {
        // Hash includes the pairing token but never persists the secret itself.
        // Changing register host, port or token makes the old state unreadable.
        val identity = buildString {
            append(settings.socketHost.lowercase(Locale.ROOT))
            append(':')
            append(settings.port)
            append(':')
            append(settings.token)
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal object CustomerDisplaySnapshotCachePolicy {
    const val MAX_AGE_MS = 30L * 60L * 1_000L

    fun isFresh(savedAtMillis: Long, nowMillis: Long): Boolean {
        if (savedAtMillis <= 0L || nowMillis < savedAtMillis) return false
        val ageMillis = nowMillis - savedAtMillis
        return ageMillis in 0L..MAX_AGE_MS
    }

    fun belongsTo(settings: CustomerDisplayConnectionSettings, savedFingerprint: String?): Boolean =
        settings.isConfigured && savedFingerprint == CustomerDisplaySnapshotTargetPolicy.fingerprint(settings)
}

class CustomerDisplaySnapshotStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(
        settings: CustomerDisplayConnectionSettings,
        nowMillis: Long = System.currentTimeMillis(),
    ): CustomerDisplaySnapshot? {
        val savedAt = preferences.getLong(KEY_SAVED_AT, 0L)
        if (!CustomerDisplaySnapshotCachePolicy.isFresh(savedAt, nowMillis)) return null
        if (!CustomerDisplaySnapshotCachePolicy.belongsTo(
                settings, preferences.getString(KEY_CONNECTION_FINGERPRINT, null),
            )
        ) return null
        val json = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { CustomerDisplaySnapshot.parse(json) }.getOrNull()
    }

    fun save(
        snapshot: CustomerDisplaySnapshot,
        settings: CustomerDisplayConnectionSettings,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (!settings.isConfigured) return
        preferences.edit()
            .putString(KEY_SNAPSHOT, snapshot.toJson())
            .putString(KEY_CONNECTION_FINGERPRINT, CustomerDisplaySnapshotTargetPolicy.fingerprint(settings))
            .putLong(KEY_SAVED_AT, nowMillis)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "customer_display_snapshot_cache"
        private const val KEY_SNAPSHOT = "snapshot_json"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_CONNECTION_FINGERPRINT = "connection_fingerprint"
    }
}
