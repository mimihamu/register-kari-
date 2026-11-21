package jp.co.tenposinfo.register.cd

import java.util.concurrent.TimeUnit

/**
 * HTTP 101 (or ping/pong) never means the register has sent business data.
 * If a full verified snapshot is not received, close this transport and retry.
 * Monotonic time protects the deadline from wall-clock adjustments.
 */
internal object CustomerDisplayInitialSnapshotDeadlineV195 {
    const val MAX_AWAIT_MILLIS = 15_000L
    const val RECONNECT_MESSAGE =
        "E-CD-006: レジの初回表示データが届きません。再接続します"

    fun exceeded(
        connectedAtNanos: Long,
        receivedSnapshot: Boolean,
        observedAtNanos: Long,
    ): Boolean {
        if (receivedSnapshot) return false
        val elapsed = observedAtNanos - connectedAtNanos
        return elapsed >= TimeUnit.MILLISECONDS.toNanos(MAX_AWAIT_MILLIS)
    }
}
