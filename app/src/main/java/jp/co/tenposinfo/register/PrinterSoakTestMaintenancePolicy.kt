package jp.co.tenposinfo.register

object PrinterSoakTestMaintenancePolicy {
    const val DEFAULT_RETENTION_DAYS = 90
    const val MIN_RETENTION_DAYS = 1
    const val MAX_RETENTION_DAYS = 365
    const val MAX_STORED_RUNS = 500

    fun normalizeRetentionDays(value: Int): Int =
        value.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

    fun shouldRecover(status: PrinterSoakTestRunStatus): Boolean =
        status == PrinterSoakTestRunStatus.RUNNING

    fun recoverySummary(completedCount: Int, totalPlanned: Int): String {
        val completed = completedCount.coerceAtLeast(0)
        val total = totalPlanned.coerceAtLeast(completed)
        return "アプリ強制終了または端末再起動で中断した試験を安全停止として回収しました（$completed/$total）。最後の用紙が出ている可能性があるため、自動再開・自動再送は行いません。"
    }
}
