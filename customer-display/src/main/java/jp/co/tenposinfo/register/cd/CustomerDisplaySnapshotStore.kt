package jp.co.tenposinfo.register.cd

import android.content.Context

internal object CustomerDisplaySnapshotCachePolicy {
    const val MAX_AGE_MS = 30 * 60 * 1_000L

    fun isFresh(savedAtMillis: Long, nowMillis: Long): Boolean =
        savedAtMillis > 0L &&
            nowMillis >= savedAtMillis &&
            nowMillis - savedAtMillis <= MAX_AGE_MS
}

class CustomerDisplaySnapshotStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(nowMillis: Long = System.currentTimeMillis()): CustomerDisplaySnapshot? {
        val savedAt = preferences.getLong(KEY_SAVED_AT, 0L)
        if (!CustomerDisplaySnapshotCachePolicy.isFresh(savedAt, nowMillis)) return null
        val json = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { CustomerDisplaySnapshot.parse(json) }.getOrNull()
    }

    fun save(snapshot: CustomerDisplaySnapshot, nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putString(KEY_SNAPSHOT, snapshot.toJson())
            .putLong(KEY_SAVED_AT, nowMillis)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "customer_display_snapshot_cache"
        private const val KEY_SNAPSHOT = "snapshot_json"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
