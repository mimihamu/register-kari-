package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import java.time.ZoneId

enum class CashMovementType(val displayName: String, val sign: Long) {
    IN("入金", 1),
    OUT("出金", -1),
}

enum class ReversalType(val displayName: String) {
    RETURN("返品"),
    CANCEL("取消"),
}

enum class SettlementReportType(val displayName: String) {
    X_INSPECTION("X点検"),
    Z_SETTLEMENT("Z精算"),
}

data class PaymentTotal(
    val method: String,
    val amount: Long,
)

data class DailyOperationsSummary(
    val businessDate: String,
    val salesGross: Long,
    val reversalGross: Long,
    val netSales: Long,
    val transactionCount: Int,
    val reversalCount: Int,
    val paymentTotals: List<PaymentTotal>,
    val cashIn: Long,
    val cashOut: Long,
    val expectedCash: Long,
    val pendingPrints: Int,
    val heldTickets: Int,
    val settled: Boolean,
)

data class CashMovementRecord(
    val id: Long,
    val type: CashMovementType,
    val amount: Long,
    val reason: String,
    val operatorName: String,
    val createdAt: Long,
)

data class ReversalRecord(
    val id: Long,
    val originalSaleId: Long,
    val type: ReversalType,
    val grossAmount: Long,
    val reason: String,
    val operatorName: String,
    val createdAt: Long,
)

data class SettlementRecord(
    val id: Long,
    val businessDate: String,
    val type: SettlementReportType,
    val salesGross: Long,
    val reversalGross: Long,
    val netSales: Long,
    val expectedCash: Long,
    val actualCash: Long,
    val variance: Long,
    val transactionCount: Int,
    val reversalCount: Int,
    val pendingPrints: Int,
    val heldTickets: Int,
    val operatorName: String,
    val createdAt: Long,
)

object OperationsMath {
    fun expectedCash(
        cashSalesAfterRefunds: Long,
        cashIn: Long,
        cashOut: Long,
    ): Long = cashSalesAfterRefunds + cashIn - cashOut

    fun variance(actualCash: Long, expectedCash: Long): Long = actualCash - expectedCash
}

/**
 * 既存の売上テーブルは更新せず、入出金・返品取消・点検精算を追記専用で保存する。
 * 返品・取消は元売上を上書きせず、反対取引と反対支払を別テーブルへ記録する。
 */
class OperationsStore(context: Context) {
    private val baseDatabase = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase = baseDatabase.writableDatabase

    init {
        ensureSchema()
    }

    fun close() = baseDatabase.close()

    fun dailySummary(date: LocalDate = LocalDate.now()): DailyOperationsSummary {
        val (from, to) = dayBounds(date)
        val salesGross = longQuery(
            "SELECT COALESCE(SUM(total_amount), 0) FROM sales WHERE created_at >= ? AND created_at < ?",
            arrayOf(from.toString(), to.toString()),
        )
        val transactionCount = longQuery(
            "SELECT COUNT(*) FROM sales WHERE created_at >= ? AND created_at < ?",
            arrayOf(from.toString(), to.toString()),
        ).toInt()
        val reversalGross = longQuery(
            "SELECT COALESCE(SUM(gross_amount), 0) FROM reversal_transactions WHERE created_at >= ? AND created_at < ?",
            arrayOf(from.toString(), to.toString()),
        )
        val reversalCount = longQuery(
            "SELECT COUNT(*) FROM reversal_transactions WHERE created_at >= ? AND created_at < ?",
            arrayOf(from.toString(), to.toString()),
        ).toInt()

        val paymentMap = linkedMapOf<String, Long>()
        db.rawQuery(
            """
            SELECT p.payment_method, COALESCE(SUM(p.applied_amount), 0)
            FROM sale_payments p
            INNER JOIN sales s ON s.id = p.sale_id
            WHERE s.created_at >= ? AND s.created_at < ?
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """.trimIndent(),
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                paymentMap[cursor.getString(0)] = cursor.getLong(1)
            }
        }
        db.rawQuery(
            """
            SELECT p.payment_method, COALESCE(SUM(p.amount), 0)
            FROM reversal_payments p
            INNER JOIN reversal_transactions r ON r.id = p.reversal_id
            WHERE r.created_at >= ? AND r.created_at < ?
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """.trimIndent(),
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val method = cursor.getString(0)
                paymentMap[method] = (paymentMap[method] ?: 0L) - cursor.getLong(1)
            }
        }

        val cashIn = movementTotal(CashMovementType.IN, from, to)
        val cashOut = movementTotal(CashMovementType.OUT, from, to)
        val expectedCash = OperationsMath.expectedCash(
            cashSalesAfterRefunds = paymentMap[PaymentMethod.CASH.name] ?: 0L,
            cashIn = cashIn,
            cashOut = cashOut,
        )
        val pendingPrints = longQuery(
            "SELECT COUNT(*) FROM print_jobs WHERE status <> ?",
            arrayOf(PrintJobStatus.COMPLETED.name),
        ).toInt()
        val heldTickets = longQuery("SELECT COUNT(*) FROM held_tickets").toInt()
        val businessDate = date.toString()
        val settled = longQuery(
            "SELECT COUNT(*) FROM settlement_reports WHERE business_date = ? AND report_type = ?",
            arrayOf(businessDate, SettlementReportType.Z_SETTLEMENT.name),
        ) > 0

        return DailyOperationsSummary(
            businessDate = businessDate,
            salesGross = salesGross,
            reversalGross = reversalGross,
            netSales = salesGross - reversalGross,
            transactionCount = transactionCount,
            reversalCount = reversalCount,
            paymentTotals = paymentMap.map { PaymentTotal(it.key, it.value) },
            cashIn = cashIn,
            cashOut = cashOut,
            expectedCash = expectedCash,
            pendingPrints = pendingPrints,
            heldTickets = heldTickets,
            settled = settled,
        )
    }

    fun recordCashMovement(
        type: CashMovementType,
        amount: Long,
        reason: String,
        operatorName: String,
    ): Long {
        require(amount > 0) { "金額を入力してください" }
        require(reason.isNotBlank()) { "理由を入力してください" }
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val now = System.currentTimeMillis()
        return db.transaction {
            val id = insertOrThrow(
                "cash_movements",
                null,
                ContentValues().apply {
                    put("movement_type", type.name)
                    put("amount", amount)
                    put("reason", reason.trim())
                    put("operator_name", operatorName.trim())
                    put("created_at", now)
                },
            )
            insertAudit(
                eventType = "CASH_${type.name}",
                referenceId = id,
                detail = "${type.displayName} ${amount}円 / ${reason.trim()}",
                operatorName = operatorName,
                createdAt = now,
            )
            id
        }
    }

    fun recentCashMovements(limit: Int = 50): List<CashMovementRecord> {
        db.query(
            "cash_movements",
            arrayOf("id", "movement_type", "amount", "reason", "operator_name", "created_at"),
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            val result = mutableListOf<CashMovementRecord>()
            while (cursor.moveToNext()) {
                result += CashMovementRecord(
                    id = cursor.getLong(0),
                    type = CashMovementType.valueOf(cursor.getString(1)),
                    amount = cursor.getLong(2),
                    reason = cursor.getString(3),
                    operatorName = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                )
            }
            return result
        }
    }

    fun createFullReversal(
        originalSaleId: Long,
        type: ReversalType,
        reason: String,
        operatorName: String,
    ): Long {
        require(reason.isNotBlank()) { "理由を入力してください" }
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val now = System.currentTimeMillis()
        val operationKey = OperationsIdempotencyPolicy.reversalKey(originalSaleId)

        return db.transaction {
            claimOperationKey(
                operationKey = operationKey,
                operationType = type.name,
                duplicateMessage = "この売上は既に返品または取消済みです",
                createdAt = now,
            )
            val alreadyReversed = rawQuery(
                "SELECT COUNT(*) FROM reversal_transactions WHERE original_sale_id = ?",
                arrayOf(originalSaleId.toString()),
            ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) > 0 }
            check(!alreadyReversed) { "この売上は既に返品または取消済みです" }

            val sale = query(
                "sales",
                arrayOf("total_amount", "payment_method"),
                "id = ?",
                arrayOf(originalSaleId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getLong(0) to cursor.getString(1)
            } ?: throw IllegalArgumentException("元売上が見つかりません")

            val reversalId = insertOrThrow(
                "reversal_transactions",
                null,
                ContentValues().apply {
                    put("original_sale_id", originalSaleId)
                    put("reversal_type", type.name)
                    put("gross_amount", sale.first)
                    put("reason", reason.trim())
                    put("operator_name", operatorName.trim())
                    put("created_at", now)
                },
            )

            var paymentRows = 0
            query(
                "sale_payments",
                arrayOf("payment_method", "applied_amount"),
                "sale_id = ?",
                arrayOf(originalSaleId.toString()),
                null,
                null,
                "sequence_no ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    insertOrThrow(
                        "reversal_payments",
                        null,
                        ContentValues().apply {
                            put("reversal_id", reversalId)
                            put("payment_method", cursor.getString(0))
                            put("amount", cursor.getLong(1))
                        },
                    )
                    paymentRows++
                }
            }
            if (paymentRows == 0) {
                val fallbackMethod = if (sale.second.contains("現金")) PaymentMethod.CASH.name else "OTHER"
                insertOrThrow(
                    "reversal_payments",
                    null,
                    ContentValues().apply {
                        put("reversal_id", reversalId)
                        put("payment_method", fallbackMethod)
                        put("amount", sale.first)
                    },
                )
            }

            insertAudit(
                eventType = type.name,
                referenceId = reversalId,
                detail = "元売上 No.$originalSaleId / ${sale.first}円 / ${reason.trim()}",
                operatorName = operatorName,
                createdAt = now,
            )
            bindOperationKey(operationKey, reversalId)
            reversalId
        }
    }

    fun isSaleReversed(saleId: Long): Boolean = longQuery(
        "SELECT COUNT(*) FROM reversal_transactions WHERE original_sale_id = ?",
        arrayOf(saleId.toString()),
    ) > 0

    fun reversedSaleIds(): Set<Long> {
        db.query(
            "reversal_transactions",
            arrayOf("original_sale_id"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val result = mutableSetOf<Long>()
            while (cursor.moveToNext()) result += cursor.getLong(0)
            return result
        }
    }

    fun recentReversals(limit: Int = 50): List<ReversalRecord> {
        db.query(
            "reversal_transactions",
            arrayOf("id", "original_sale_id", "reversal_type", "gross_amount", "reason", "operator_name", "created_at"),
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            val result = mutableListOf<ReversalRecord>()
            while (cursor.moveToNext()) {
                result += ReversalRecord(
                    id = cursor.getLong(0),
                    originalSaleId = cursor.getLong(1),
                    type = ReversalType.valueOf(cursor.getString(2)),
                    grossAmount = cursor.getLong(3),
                    reason = cursor.getString(4),
                    operatorName = cursor.getString(5),
                    createdAt = cursor.getLong(6),
                )
            }
            return result
        }
    }

    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        operatorName: String,
        date: LocalDate = LocalDate.now(),
    ): Long {
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val now = System.currentTimeMillis()
        val operationKey = OperationsIdempotencyPolicy.settlementKey(type, date)

        return db.transaction {
            if (operationKey != null) {
                claimOperationKey(
                    operationKey = operationKey,
                    operationType = type.name,
                    duplicateMessage = "この営業日は既にZ精算済みです",
                    createdAt = now,
                )
            }
            val summary = dailySummary(date)
            if (type == SettlementReportType.Z_SETTLEMENT && summary.settled) {
                throw IllegalStateException("この営業日は既にZ精算済みです")
            }
            val actual = actualCash ?: summary.expectedCash
            require(actual >= 0) { "現金実査額は0円以上で入力してください" }
            val variance = OperationsMath.variance(actual, summary.expectedCash)

            val id = insertOrThrow(
                "settlement_reports",
                null,
                ContentValues().apply {
                    put("business_date", summary.businessDate)
                    put("report_type", type.name)
                    put("sales_gross", summary.salesGross)
                    put("reversal_gross", summary.reversalGross)
                    put("net_sales", summary.netSales)
                    put("expected_cash", summary.expectedCash)
                    put("actual_cash", actual)
                    put("variance", variance)
                    put("transaction_count", summary.transactionCount)
                    put("reversal_count", summary.reversalCount)
                    put("pending_prints", summary.pendingPrints)
                    put("held_tickets", summary.heldTickets)
                    put("operator_name", operatorName.trim())
                    put("created_at", now)
                },
            )
            insertAudit(
                eventType = type.name,
                referenceId = id,
                detail = "営業日 ${summary.businessDate} / 純売上 ${summary.netSales}円 / 現金差異 ${variance}円",
                operatorName = operatorName,
                createdAt = now,
            )
            if (operationKey != null) bindOperationKey(operationKey, id)
            id
        }
    }

    fun recentSettlements(limit: Int = 50): List<SettlementRecord> {
        db.query(
            "settlement_reports",
            arrayOf(
                "id", "business_date", "report_type", "sales_gross", "reversal_gross", "net_sales",
                "expected_cash", "actual_cash", "variance", "transaction_count", "reversal_count",
                "pending_prints", "held_tickets", "operator_name", "created_at",
            ),
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            val result = mutableListOf<SettlementRecord>()
            while (cursor.moveToNext()) {
                result += SettlementRecord(
                    id = cursor.getLong(0),
                    businessDate = cursor.getString(1),
                    type = SettlementReportType.valueOf(cursor.getString(2)),
                    salesGross = cursor.getLong(3),
                    reversalGross = cursor.getLong(4),
                    netSales = cursor.getLong(5),
                    expectedCash = cursor.getLong(6),
                    actualCash = cursor.getLong(7),
                    variance = cursor.getLong(8),
                    transactionCount = cursor.getInt(9),
                    reversalCount = cursor.getInt(10),
                    pendingPrints = cursor.getInt(11),
                    heldTickets = cursor.getInt(12),
                    operatorName = cursor.getString(13),
                    createdAt = cursor.getLong(14),
                )
            }
            return result
        }
    }

    private fun movementTotal(type: CashMovementType, from: Long, to: Long): Long = longQuery(
        "SELECT COALESCE(SUM(amount), 0) FROM cash_movements WHERE movement_type = ? AND created_at >= ? AND created_at < ?",
        arrayOf(type.name, from.toString(), to.toString()),
    )

    private fun dayBounds(date: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return from to to
    }

    private fun longQuery(sql: String, args: Array<String> = emptyArray()): Long =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun SQLiteDatabase.claimOperationKey(
        operationKey: String,
        operationType: String,
        duplicateMessage: String,
        createdAt: Long,
    ) {
        val inserted = insertWithOnConflict(
            "operation_commit_keys",
            null,
            ContentValues().apply {
                put("operation_key", operationKey)
                put("operation_type", operationType)
                put("reference_id", 0L)
                put("created_at", createdAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (inserted == -1L) throw IllegalStateException(duplicateMessage)
    }

    private fun SQLiteDatabase.bindOperationKey(operationKey: String, referenceId: Long) {
        val updated = update(
            "operation_commit_keys",
            ContentValues().apply { put("reference_id", referenceId) },
            "operation_key = ?",
            arrayOf(operationKey),
        )
        check(updated == 1) { "操作キーの確定に失敗しました" }
    }

    private fun SQLiteDatabase.insertAudit(
        eventType: String,
        referenceId: Long,
        detail: String,
        operatorName: String,
        createdAt: Long,
    ) {
        insertOrThrow(
            "operation_audit",
            null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", referenceId)
                put("detail", detail)
                put("operator_name", operatorName.trim())
                put("created_at", createdAt)
            },
        )
    }

    private fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cash_movements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                movement_type TEXT NOT NULL,
                amount INTEGER NOT NULL,
                reason TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reversal_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_sale_id INTEGER NOT NULL,
                reversal_type TEXT NOT NULL,
                gross_amount INTEGER NOT NULL,
                reason TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(original_sale_id) REFERENCES sales(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reversal_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                reversal_id INTEGER NOT NULL,
                payment_method TEXT NOT NULL,
                amount INTEGER NOT NULL,
                FOREIGN KEY(reversal_id) REFERENCES reversal_transactions(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settlement_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_date TEXT NOT NULL,
                report_type TEXT NOT NULL,
                sales_gross INTEGER NOT NULL,
                reversal_gross INTEGER NOT NULL,
                net_sales INTEGER NOT NULL,
                expected_cash INTEGER NOT NULL,
                actual_cash INTEGER NOT NULL,
                variance INTEGER NOT NULL,
                transaction_count INTEGER NOT NULL,
                reversal_count INTEGER NOT NULL,
                pending_prints INTEGER NOT NULL,
                held_tickets INTEGER NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS operation_commit_keys (
                operation_key TEXT PRIMARY KEY,
                operation_type TEXT NOT NULL,
                reference_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS operation_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                reference_id INTEGER NOT NULL,
                detail TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cash_movements_created ON cash_movements(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reversal_original_sale ON reversal_transactions(original_sale_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reversal_created ON reversal_transactions(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_date ON settlement_reports(business_date, report_type)")
    }
}

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val value = block()
        setTransactionSuccessful()
        value
    } finally {
        endTransaction()
    }
}
