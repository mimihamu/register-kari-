package jp.co.tenposinfo.register.cd

import java.util.concurrent.TimeUnit

/**
 * A SNAPSHOT_REQUEST that gets lost must never leave the customer display
 * indefinitely unable to receive updates while its WebSocket remains live.
 *
 * Uses monotonic nanoTime, not the device wall clock (NTP/manual changes).
 */
internal object CustomerDisplayResyncDeadlineV194 {
    const val MAX_AWAIT_MILLIS = 15_000L
    const val RECONNECT_MESSAGE =
        "E-CD-004: 最新の表示データを取得できません。レジへ再接続します"

    fun exceeded(startedAtNanos: Long?, observedAtNanos: Long): Boolean {
        if (startedAtNanos == null) return false
        val elapsedNanos = observedAtNanos - startedAtNanos
        return elapsedNanos >= TimeUnit.MILLISECONDS.toNanos(MAX_AWAIT_MILLIS)
    }
}
