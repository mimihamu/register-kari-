package jp.co.tenposinfo.register

data class PrinterPersistentAlertState(
    val incidentStartedAt: Long = 0L,
    val lastNotifiedAt: Long = 0L,
)

data class PrinterPersistentAlertDecision(
    val active: Boolean,
    val incidentStartedAt: Long,
    val durationMillis: Long,
    val notificationDue: Boolean,
    val clearNotification: Boolean,
)

/**
 * プリンター状態取得結果だけを対象とする継続異常判定。
 * 印刷ジョブのFAILED／送信結果不明とは完全に分離し、自動再送判断には使用しない。
 */
object PrinterPersistentAlertPolicy {
    const val ALERT_AFTER_MILLIS = 60_000L
    const val REMIND_AFTER_MILLIS = 30L * 60L * 1_000L

    fun isAlertable(level: PrinterHealthLevel): Boolean = when (level) {
        PrinterHealthLevel.ERROR,
        PrinterHealthLevel.UNCONFIGURED,
        -> true

        PrinterHealthLevel.CHECKING,
        PrinterHealthLevel.READY,
        PrinterHealthLevel.WARNING,
        PrinterHealthLevel.DISABLED,
        -> false
    }

    fun evaluate(
        previous: PrinterPersistentAlertState,
        level: PrinterHealthLevel,
        nowMillis: Long,
    ): PrinterPersistentAlertDecision {
        if (!isAlertable(level)) {
            return PrinterPersistentAlertDecision(
                active = false,
                incidentStartedAt = 0L,
                durationMillis = 0L,
                notificationDue = false,
                clearNotification = previous.incidentStartedAt > 0L || previous.lastNotifiedAt > 0L,
            )
        }

        val startedAt = previous.incidentStartedAt
            .takeIf { it in 1L..nowMillis }
            ?: nowMillis
        val duration = (nowMillis - startedAt).coerceAtLeast(0L)
        val notificationDue = duration >= ALERT_AFTER_MILLIS && (
            previous.lastNotifiedAt <= 0L ||
                nowMillis - previous.lastNotifiedAt >= REMIND_AFTER_MILLIS
            )

        return PrinterPersistentAlertDecision(
            active = true,
            incidentStartedAt = startedAt,
            durationMillis = duration,
            notificationDue = notificationDue,
            clearNotification = false,
        )
    }
}
