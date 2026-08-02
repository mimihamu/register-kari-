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

internal data class CustomerDisplayTransportLossDecision(
    val generation: Long,
    val delayMillis: Long,
    val notifyImmediately: Boolean,
)

/**
 * ソケット状態と顧客に見せる状態を分離する純粋な状態機械。
 * 呼び出し側が同期を保証する。
 */
internal class CustomerDisplayConnectionVisibilityState {
    var transportConnected: Boolean = false
        private set
    var visibleDisconnected: Boolean = true
        private set
    var hasPresentedSnapshot: Boolean = false
        private set
    var latestDisconnectReason: String? = null
        private set
    var generation: Long = 0L
        private set

    fun onConnected(): Long {
        transportConnected = true
        visibleDisconnected = false
        latestDisconnectReason = null
        generation++
        return generation
    }

    fun onSnapshot(): Long {
        hasPresentedSnapshot = true
        return onConnected()
    }

    fun onTransportLost(reason: String): CustomerDisplayTransportLossDecision {
        transportConnected = false
        latestDisconnectReason = reason
        generation++
        val delay = if (visibleDisconnected) {
            0L
        } else {
            CustomerDisplayConnectionPresentationPolicy.disconnectDelayMillis(hasPresentedSnapshot)
        }
        if (delay == 0L) visibleDisconnected = true
        return CustomerDisplayTransportLossDecision(
            generation = generation,
            delayMillis = delay,
            notifyImmediately = delay == 0L,
        )
    }

    fun revealDisconnectedIfCurrent(expectedGeneration: Long): Boolean {
        if (transportConnected || expectedGeneration != generation) return false
        visibleDisconnected = true
        return true
    }

    fun shouldPresentAsConnected(): Boolean =
        transportConnected || CustomerDisplayConnectionPresentationPolicy.shouldReplayLastSnapshot(
            hasPresentedSnapshot = hasPresentedSnapshot,
            visibleDisconnected = visibleDisconnected,
        )
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
 * 同じ内容をLogcatの「TsuguRegiCD」タグへも出力する。
 */
internal object CustomerDisplayConnectionEventLog {
    private const val MAX_ENTRIES = 100
    private const val LOG_TAG = "TsuguRegiCD"
    private val lock = Any()
    private val entries = ArrayDeque<CustomerDisplayConnectionLogEntry>()

    fun record(type: CustomerDisplayConnectionEventType, message: String) {
        val normalized = message.take(240)
        synchronized(lock) {
            entries.addLast(
                CustomerDisplayConnectionLogEntry(
                    timestampMillis = System.currentTimeMillis(),
                    type = type,
                    message = normalized,
                ),
            )
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        runCatching {
            android.util.Log.i(LOG_TAG, "${type.name}: $normalized")
        }
    }

    fun snapshot(limit: Int = 30): List<CustomerDisplayConnectionLogEntry> = synchronized(lock) {
        entries.toList().takeLast(limit.coerceIn(1, MAX_ENTRIES)).reversed()
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
