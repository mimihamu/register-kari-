package jp.co.tenposinfo.register

internal class OutboxExternalDeliveryLeaseLostExceptionV113(outboxId: Long) :
    IllegalStateException("Outbox送信所有権が変化したため状態を更新できません: id=$outboxId")

/**
 * STAGED JSONの外部配送だけに使うlease。
 *
 * 定期Workと即時Workは別のunique work名なので同時実行し得る。STAGEDのままtokenをclaimし、
 * 外部I/O中に別Workerが同じJSONや.partialを操作しないようにする。
 */
internal object OutboxExternalDeliveryLeaseV113 {
    const val LEASE_MILLIS = 30L * 60L * 1_000L

    fun claimable(workerToken: String?, leaseUntil: Long?, now: Long): Boolean =
        workerToken.isNullOrBlank() || leaseUntil == null || leaseUntil <= now

    fun requireOwnedTransition(outboxId: Long, changedRows: Int) {
        if (changedRows != 1) throw OutboxExternalDeliveryLeaseLostExceptionV113(outboxId)
    }
}
