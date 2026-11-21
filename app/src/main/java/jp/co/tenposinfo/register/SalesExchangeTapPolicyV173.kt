package jp.co.tenposinfo.register

/**
 * A short cooldown after dispatch keeps two rapid taps from causing multiple physical pulses.
 * This state is local to the sales UI; each accepted request still has a unique durable event key.
 */
internal object SalesExchangeTapPolicyV173 {
    const val MIN_INTERVAL_MS = 1500L

    fun mayStart(nowElapsedMs: Long, previousElapsedMs: Long?): Boolean =
        previousElapsedMs == null ||
            nowElapsedMs < previousElapsedMs ||
            nowElapsedMs - previousElapsedMs >= MIN_INTERVAL_MS
}
