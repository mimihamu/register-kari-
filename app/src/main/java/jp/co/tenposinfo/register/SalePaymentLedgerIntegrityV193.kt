package jp.co.tenposinfo.register

/**
 * Read-only consistency checks for archived sale tenders, including backups
 * before restore and restored databases before success is recorded.
 *
 * SQL uses basic GROUP BY / COUNT(DISTINCT) rather than window functions so
 * it works with Android's SQLite on minSdk 26.
 */
internal object SalePaymentLedgerIntegrityV193 {
    // Values derive only from our enum, never from external SQL or backup data.
    private val allowedMethodsSql = PaymentMethod.entries.joinToString(",") { "'${it.name}'" }

    val INVALID_ROW_SQL: String
        get() = """
            SELECT COUNT(*) FROM sale_payments
            WHERE applied_amount <= 0
               OR received_amount < applied_amount
               OR payment_method NOT IN ($allowedMethodsSql)
        """.trimIndent()

    // MIN = 1, MAX = row count and DISTINCT = row count together guarantee
    // [1,2,...,N] per sale. Duplicate numbers cannot conceal a missing row.
    const val INVALID_SEQUENCE_SQL = """
        SELECT COUNT(*) FROM (
            SELECT sale_id FROM sale_payments GROUP BY sale_id
            HAVING MIN(sequence_no) <> 1
                OR MAX(sequence_no) <> COUNT(*)
                OR COUNT(DISTINCT sequence_no) <> COUNT(*)
        )
    """

    fun validSequence(sequences: List<Long>): Boolean =
        sequences.withIndex().all { (index, number) -> number == index.toLong() + 1L }

    fun validTender(methodName: String, applied: Long, received: Long): Boolean =
        PaymentMethod.entries.any { it.name == methodName } &&
            applied > 0L && received >= applied
}
