package jp.co.tenposinfo.register.cd

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 通信瞬断を顧客向けの「切断画面」へ即時反映しないための表示方針。
 * 実際のソケット再接続は直ちに開始し、表示だけを猶予時間中維持する。
 */
internal object CustomerDisplayConnectionPresentationPolicy {
    const val TRANSIENT_OUTAGE_GRACE_MS = 3_000L

    fun disconnectDelayMillis(hasPresentedSnapshot: Boolean): Long =
        if (hasPresentedSnapshot) TRANSIENT_OUTAGE_GRACE_MS else 0L

    fun shouldReplayLastSnapshot(
        hasPresentedSnapshot: Boolean,
        visibleDisconnected: Boolean,
    ): Boolean = hasPresentedSnapshot && !visibleDisconnected
}

internal enum class CustomerDisplayConnectionEventType {
    STARTING,
    CONNECTED,
    MODE_CHANGED,
    TRANSIENT_LOSS,
    DISCONNECTED,
    STOPPED,
}

internal data class CustomerDisplayConnectionLogEntry(
    val timestampMillis: Long,
    val type: CustomerDisplayConnectionEventType,
    val message: String,
) {
    fun displayText(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN).format(Date(timestampMillis))
        return "$time  ${type.name}  $message"
    }
}

/**
 * 実機調査用の軽量リングバッファ。売上・商品名・トークンは記録しない。
 */
internal object CustomerDisplayConnectionEventLog {
    private const val MAX_ENTRIES = 100
    private val lock = Any()
    private val entries = ArrayDeque<CustomerDisplayConnectionLogEntry>()

    fun record(type: CustomerDisplayConnectionEventType, message: String) {
        synchronized(lock) {
            entries.addLast(
                CustomerDisplayConnectionLogEntry(
                    timestampMillis = System.currentTimeMillis(),
                    type = type,
                    message = message.take(240),
                ),
            )
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun snapshot(limit: Int = 30): List<CustomerDisplayConnectionLogEntry> = synchronized(lock) {
        entries.toList().takeLast(limit.coerceIn(1, MAX_ENTRIES)).reversed()
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
