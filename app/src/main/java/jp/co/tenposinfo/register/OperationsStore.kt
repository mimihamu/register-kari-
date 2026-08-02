package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate

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
    val businessSessionId: Long,
    val businessDate: String,
    val salesGross: Long,
    val reversalGross: Long,
    val netSales: Long,
    val transactionCount: Int,
    val reversalCount: Int,
    val paymentTotals: List<PaymentTotal>,
    val openingCash: Long,
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
    val businessSessionId: Long,
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
        openingCash: Long = 0L,
    ): Long = openingCash + cashSalesAfterRefunds + cashIn - cashOut

    fun variance(actualCash: Long, expectedCash: Long): Long = actualCash - expectedCash
}


object BusinessSessionTransitionPolicy {
    fun mayStart(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
        !date.isAfter(today) && !date.isBefore(today.minusDays(1))

    fun mayOperate(status: BusinessSessionStatus?): Boolean = BusinessSessionLifecyclePolicy.isActive(status)
    fun maySettle(status: BusinessSessionStatus?): Boolean = BusinessSessionLifecyclePolicy.isActive(status)
}

/**
 * 既存の売上テーブルは更新せず、入出金・返品取消・点検精算を追記専用で保存する。
 * 返品・取消は元売上を上書きせず、反対取引と反対支払を別テーブルへ記録する。
 */
class OperationsStore(context: Context) {
    private val appContext = context.applicationContext
    private val baseDatabase = RegisterDatabase(appContext)
    private val db: SQLiteDatabase = baseDatabase.writableDatabase

    init {
        ensureSchema()
    }

    fun close() = baseDatabase.close()

    fun activeBusinessSession(): BusinessSessionRecord? = queryActiveSession(db)

    fun recentBusinessSessions(limit: Int = 30): List<BusinessSessionRecord> = db.query(
        "business_sessions",
        SESSION_COLUMNS,
        null,
        null,
        null,
        null,
        "opened_at DESC",
        limit.coerceIn(1, 100).toString(),
    ).use { cursor ->
        val result = mutableListOf<BusinessSessionRecord>()
        while (cursor.moveToNext()) result += cursor.toBusinessSessionRecord()
        result
    }

    fun startBusinessDay(businessDate: LocalDate, openingCash: Long, operatorName: String): Long {
        require(BusinessSessionTransitionPolicy.mayStart(businessDate)) { "営業日は本日または前日を指定してください" }
        require(openingCash >= 0) { "開始釣銭は0円以上で入力してください" }
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val now = System.currentTimeMillis()
        return db.transaction {
            check(BusinessSessionLifecyclePolicy.mayStart(queryActiveSession(this)?.status)) { "営業中の営業セッションがあります" }
            val id = insertOrThrow(
                "business_sessions",
                null,
                ContentValues().apply {
                    put("business_date", businessDate.toString())
                    put("status", BusinessSessionStatus.OPEN.name)
                    put("opening_cash", openingCash)
                    put("opened_by", operatorName.trim())
                    put("opened_at", now)
                },
            )
            insertAudit(
                eventType = "BUSINESS_OPEN",
                referenceId = id,
                detail = "営業日 $businessDate / セッションNo.$id / 開始釣銭 ${openingCash}円",
                operatorName = operatorName,
                createdAt = now,
            )
            id
        }
    }

    @Deprecated("v0.24以降、営業終了はZ精算と同一トランザクションで完了します")
    fun endBusinessDay(actualCash: Long, operatorName: String): Long {
        throw IllegalStateException("営業終了はZ精算の完了時に自動で行われます")
    }

    fun dailySummary(date: LocalDate? = null): DailyOperationsSummary {
        BusinessSessionSchema.ensure(db)
        val session = if (date == null) {
            queryActiveSession(db)?.toWindow()
                ?: BusinessSessionSchema.sessionForDate(db, LocalDate.now())
                ?: BusinessSessionDisplayFallback.forDate(LocalDate.now())
        } else {
            BusinessSessionSchema.sessionForDate(db, date)
                ?: BusinessSessionDisplayFallback.forDate(date)
        } ?: error("営業日を特定できません")
        return summaryForSession(session)
    }

    fun summaryForSession(sessionId: Long): DailyOperationsSummary {
        val session = BusinessSessionSchema.sessionById(db, sessionId)
            ?: error("営業セッションNo.${sessionId}が見つかりません")
        return summaryForSession(session)
    }

    private fun summaryForSession(session: BusinessSessionWindow): DailyOperationsSummary {
        val sessionId = session.id
        val salesGross = longQuery(
            "SELECT COALESCE(SUM(total_amount), 0) FROM sales WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        )
        val transactionCount = longQuery(
            "SELECT COUNT(*) FROM sales WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        ).toInt()
        val reversalGross = longQuery(
            "SELECT COALESCE(SUM(gross_amount), 0) FROM reversal_transactions WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        )
        val reversalCount = longQuery(
            "SELECT COUNT(*) FROM reversal_transactions WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        ).toInt()

        val paymentMap = linkedMapOf<String, Long>()
        db.rawQuery(
            """
            SELECT p.payment_method, COALESCE(SUM(p.applied_amount), 0)
            FROM sale_payments p
            INNER JOIN sales s ON s.id = p.sale_id
            WHERE s.business_session_id = ?
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) paymentMap[cursor.getString(0)] = cursor.getLong(1)
        }
        db.rawQuery(
            """
            SELECT p.payment_method, COALESCE(SUM(p.amount), 0)
            FROM reversal_payments p
            INNER JOIN reversal_transactions r ON r.id = p.reversal_id
            WHERE r.business_session_id = ?
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val method = cursor.getString(0)
                paymentMap[method] = (paymentMap[method] ?: 0L) - cursor.getLong(1)
            }
        }

        val cashIn = movementTotal(CashMovementType.IN, sessionId)
        val cashOut = movementTotal(CashMovementType.OUT, sessionId)
        val expectedCash = OperationsMath.expectedCash(
            cashSalesAfterRefunds = paymentMap[PaymentMethod.CASH.name] ?: 0L,
            cashIn = cashIn,
            cashOut = cashOut,
            openingCash = session.openingCash,
        )
        val pendingPrints = (
            longQuery(
                "SELECT COUNT(*) FROM print_jobs WHERE status <> ?",
                arrayOf(PrintJobStatus.COMPLETED.name),
            ) + longQuery(
                "SELECT COUNT(*) FROM document_print_jobs WHERE status <> ?",
                arrayOf(PrintJobStatus.COMPLETED.name),
            )
        ).toInt()
        val heldTickets = longQuery("SELECT COUNT(*) FROM held_tickets").toInt()
        val settled = longQuery(
            "SELECT COUNT(*) FROM settlement_reports WHERE business_session_id = ? AND report_type = ?",
            arrayOf(sessionId.toString(), SettlementReportType.Z_SETTLEMENT.name),
        ) > 0

        return DailyOperationsSummary(
            businessSessionId = sessionId,
            businessDate = session.businessDate,
            salesGross = salesGross,
            reversalGross = reversalGross,
            netSales = salesGross - reversalGross,
            transactionCount = transactionCount,
            reversalCount = reversalCount,
            paymentTotals = paymentMap.map { PaymentTotal(it.key, it.value) },
            openingCash = session.openingCash,
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
            val session = queryActiveSession(this) ?: error("営業開始後に入出金を登録してください")
            check(BusinessSessionTransitionPolicy.mayOperate(session.status)) { "Z精算後は入出金できません" }
            val id = insertOrThrow(
                "cash_movements",
                null,
                ContentValues().apply {
                    put("movement_type", type.name)
                    put("amount", amount)
                    put("reason", reason.trim())
                    put("operator_name", operatorName.trim())
                    put("business_session_id", session.id)
                    put("business_date", session.businessDate)
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

    fun loadReturnableLines(saleId: Long): List<ReturnableSaleLine> =
        loadReturnableLines(db, saleId)

    private fun loadReturnableLines(database: SQLiteDatabase, saleId: Long): List<ReturnableSaleLine> = database.rawQuery(
        """
        SELECT si.id, si.product_id, si.product_name, si.unit_price, si.tax_category,
               COALESCE(lts.tax_key, si.tax_category),
               COALESCE(lts.tax_label, si.tax_category),
               COALESCE(lts.rate_percent, CASE si.tax_category WHEN 'INCLUDED_10' THEN 10 WHEN 'EXCLUDED_10' THEN 10 WHEN 'INCLUDED_8' THEN 8 WHEN 'EXCLUDED_8' THEN 8 ELSE 0 END),
               COALESCE(lts.tax_included, CASE WHEN si.tax_category IN ('INCLUDED_10','INCLUDED_8') THEN 1 ELSE 0 END),
               COALESCE(lts.taxable, CASE WHEN si.tax_category = 'NON_TAXABLE' THEN 0 ELSE 1 END),
               COALESCE(lts.reduced, CASE WHEN si.tax_category IN ('INCLUDED_8','EXCLUDED_8') THEN 1 ELSE 0 END),
               COALESCE(lts.tax_symbol, CASE si.tax_category WHEN 'INCLUDED_10' THEN '内' WHEN 'EXCLUDED_10' THEN '外' WHEN 'INCLUDED_8' THEN '内※' WHEN 'EXCLUDED_8' THEN '外※' ELSE '非' END),
               si.quantity, si.discount_amount, si.note,
               CASE WHEN EXISTS (
                   SELECT 1 FROM reversal_transactions legacy
                   WHERE legacy.original_sale_id = si.sale_id
                     AND NOT EXISTS (SELECT 1 FROM reversal_items legacy_item WHERE legacy_item.reversal_id = legacy.id)
               ) THEN si.quantity ELSE COALESCE(SUM(ri.return_quantity), 0) END AS returned_quantity,
               CASE WHEN EXISTS (
                   SELECT 1 FROM reversal_transactions legacy
                   WHERE legacy.original_sale_id = si.sale_id
                     AND NOT EXISTS (SELECT 1 FROM reversal_items legacy_item WHERE legacy_item.reversal_id = legacy.id)
               ) THEN si.discount_amount ELSE COALESCE(SUM(ri.discount_amount), 0) END AS refunded_discount
        FROM sale_items si
        LEFT JOIN line_tax_snapshots lts
          ON lts.scope = 'SALE'
         AND lts.owner_id = si.sale_id
         AND lts.line_no = (SELECT COUNT(*) FROM sale_items si2 WHERE si2.sale_id = si.sale_id AND si2.id <= si.id)
        LEFT JOIN reversal_items ri ON ri.sale_item_id = si.id
        WHERE si.sale_id = ?
        GROUP BY si.id, si.product_id, si.product_name, si.unit_price, si.tax_category,
                 lts.tax_key, lts.tax_label, lts.rate_percent, lts.tax_included, lts.taxable, lts.reduced, lts.tax_symbol,
                 si.quantity, si.discount_amount, si.note
        ORDER BY si.id ASC
        """.trimIndent(),
        arrayOf(saleId.toString()),
    ).use { cursor ->
        val result = mutableListOf<ReturnableSaleLine>()
        while (cursor.moveToNext()) {
            val legacy = TaxCategory.valueOf(cursor.getString(4))
            result += ReturnableSaleLine(
                saleItemId = cursor.getLong(0),
                productId = cursor.getString(1),
                productName = cursor.getString(2),
                unitPrice = cursor.getLong(3),
                taxCategory = legacy,
                taxKey = cursor.getString(5),
                taxLabel = cursor.getString(6).takeUnless { it == legacy.name } ?: legacy.displayName,
                taxRatePercent = cursor.getInt(7),
                taxIncluded = cursor.getInt(8) != 0,
                taxable = cursor.getInt(9) != 0,
                reduced = cursor.getInt(10) != 0,
                taxSymbol = cursor.getString(11),
                originalQuantity = cursor.getInt(12),
                originalDiscount = cursor.getLong(13),
                note = cursor.getString(14),
                returnedQuantity = cursor.getInt(15),
                refundedDiscount = cursor.getLong(16),
            )
        }
        result
    }

    fun createReversal(
        originalSaleId: Long,
        type: ReversalType,
        requestedQuantities: Map<Long, Int>,
        reason: String,
        operatorName: String,
        paperWidthMm: Int,
        requestId: String,
    ): PartialReversalResult {
        require(reason.isNotBlank()) { "理由を入力してください" }
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val now = System.currentTimeMillis()
        val operationKey = OperationsIdempotencyPolicy.reversalRequestKey(type, originalSaleId, requestId)
        val issuer = TaxInvoiceSettingsStore(appContext).load().issuer
        var savedResult: PartialReversalResult? = null

        db.transaction {
            val session = queryActiveSession(this) ?: error("営業開始後に返品・取消を実行してください")
            check(BusinessSessionTransitionPolicy.mayOperate(session.status)) { "Z精算後は返品・取消できません" }
            claimOperationKey(
                operationKey = operationKey,
                operationType = type.name,
                duplicateMessage = "同じ返品・取消要求は既に処理済みです",
                createdAt = now,
            )
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

            val lines = loadReturnableLines(this, originalSaleId)
            val selected = PartialReturnPolicy.select(type, lines, requestedQuantities)
            val items = selected.map { it.second }
            val taxSummary = TaxEngine.calculate(items)
            val refundTotal = taxSummary.grossAmount
            require(refundTotal > 0) { "返金額が0円です" }

            val originalPayments = mutableListOf<PaymentTotal>()
            query(
                "sale_payments",
                arrayOf("payment_method", "applied_amount"),
                "sale_id = ?",
                arrayOf(originalSaleId.toString()),
                null,
                null,
                "sequence_no ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) originalPayments += PaymentTotal(cursor.getString(0), cursor.getLong(1))
            }
            val fallbackMethod = if (sale.second.contains("現金")) PaymentMethod.CASH.name else "OTHER"
            val refundPayments = PartialReturnPolicy.allocateRefundPayments(refundTotal, originalPayments, fallbackMethod)

            val reversalId = insertOrThrow(
                "reversal_transactions",
                null,
                ContentValues().apply {
                    put("original_sale_id", originalSaleId)
                    put("reversal_type", type.name)
                    put("gross_amount", refundTotal)
                    put("reason", reason.trim())
                    put("operator_name", operatorName.trim())
                    put("business_session_id", session.id)
                    put("business_date", session.businessDate)
                    put("created_at", now)
                },
            )
            selected.forEach { (line, item) ->
                insertOrThrow(
                    "reversal_items",
                    null,
                    ContentValues().apply {
                        put("reversal_id", reversalId)
                        put("sale_item_id", line.saleItemId)
                        put("product_id", line.productId)
                        put("product_name", line.productName)
                        put("unit_price", line.unitPrice)
                        put("tax_category", line.taxCategory.name)
                        put("tax_key", line.taxKey)
                        put("tax_label", line.taxLabel)
                        put("tax_rate_percent", line.taxRatePercent)
                        put("tax_included", if (line.taxIncluded) 1 else 0)
                        put("taxable", if (line.taxable) 1 else 0)
                        put("reduced", if (line.reduced) 1 else 0)
                        put("tax_symbol", line.taxSymbol)
                        put("original_quantity", line.originalQuantity)
                        put("return_quantity", item.quantity)
                        put("discount_amount", item.discountAmount)
                        put("gross_amount", item.baseAmount)
                    },
                )
            }
            refundPayments.forEach { payment ->
                insertOrThrow(
                    "reversal_payments",
                    null,
                    ContentValues().apply {
                        put("reversal_id", reversalId)
                        put("payment_method", payment.method)
                        put("amount", payment.amount)
                    },
                )
            }
            val document = ReversalDocumentData(
                reversalId = reversalId,
                originalSaleId = originalSaleId,
                type = type,
                createdAt = now,
                operatorName = operatorName.trim(),
                reason = reason.trim(),
                items = items,
                taxSummary = taxSummary,
                refundPayments = refundPayments,
                issuer = issuer,
            )
            val preview = OperationDocumentRenderer.renderReversal(document, ReceiptPaper.fromWidth(paperWidthMm))
            val printJobId = insertDocumentJob(
                OperationDocumentType.REVERSAL_RECEIPT,
                reversalId,
                paperWidthMm,
                preview,
                now,
            )
            insertAudit(
                eventType = type.name,
                referenceId = reversalId,
                detail = "元売上 No.$originalSaleId / 返金 ${refundTotal}円 / ${reason.trim()}",
                operatorName = operatorName,
                createdAt = now,
            )
            bindOperationKey(operationKey, reversalId)
            savedResult = PartialReversalResult(reversalId, refundTotal, printJobId, preview)
        }
        return checkNotNull(savedResult)
    }

    fun createFullReversal(
        originalSaleId: Long,
        type: ReversalType,
        reason: String,
        operatorName: String,
    ): Long {
        val requested = if (type == ReversalType.RETURN) {
            loadReturnableLines(originalSaleId).associate { it.saleItemId to it.remainingQuantity }
        } else {
            emptyMap()
        }
        return createReversal(
            originalSaleId = originalSaleId,
            type = type,
            requestedQuantities = requested,
            reason = reason,
            operatorName = operatorName,
            paperWidthMm = 80,
            requestId = "FULL-${type.name}",
        ).reversalId
    }

    fun isSaleReversed(saleId: Long): Boolean = longQuery(
        "SELECT COUNT(*) FROM reversal_transactions WHERE original_sale_id = ?",
        arrayOf(saleId.toString()),
    ) > 0

    fun reversedSaleIds(): Set<Long> = db.rawQuery(
        """
        SELECT si.sale_id
        FROM sale_items si
        LEFT JOIN reversal_items ri ON ri.sale_item_id = si.id
        GROUP BY si.sale_id
        HAVING SUM(si.quantity) <= COALESCE(SUM(ri.return_quantity), 0)
        UNION
        SELECT rt.original_sale_id
        FROM reversal_transactions rt
        WHERE NOT EXISTS (SELECT 1 FROM reversal_items ri2 WHERE ri2.reversal_id = rt.id)
        """.trimIndent(),
        null,
    ).use { cursor ->
        val result = mutableSetOf<Long>()
        while (cursor.moveToNext()) result += cursor.getLong(0)
        result
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
    ): Long {
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val now = System.currentTimeMillis()

        return db.transaction {
            val session = queryActiveSession(this) ?: error("営業中の営業セッションがありません")
            check(BusinessSessionTransitionPolicy.maySettle(session.status)) { "この営業セッションは既に終了しています" }
            val operationKey = OperationsIdempotencyPolicy.settlementKey(type, session.id)
            if (operationKey != null) {
                claimOperationKey(
                    operationKey = operationKey,
                    operationType = type.name,
                    duplicateMessage = "この営業セッションは既にZ精算済みです",
                    createdAt = now,
                )
            }
            val summary = summaryForSession(session.toWindow())
            if (type == SettlementReportType.Z_SETTLEMENT && summary.settled) {
                throw IllegalStateException("この営業セッションは既にZ精算済みです")
            }
            val actual = actualCash ?: summary.expectedCash
            require(actual >= 0) { "現金実査額は0円以上で入力してください" }
            val variance = OperationsMath.variance(actual, summary.expectedCash)

            val id = insertOrThrow(
                "settlement_reports",
                null,
                ContentValues().apply {
                    put("business_session_id", session.id)
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
            if (type == SettlementReportType.Z_SETTLEMENT) {
                val updated = update(
                    "business_sessions",
                    ContentValues().apply {
                        put("status", BusinessSessionLifecyclePolicy.resultStatus(type, session.status).name)
                        put("closed_by", operatorName.trim())
                        put("closed_at", now)
                        put("closing_actual", actual)
                        put("close_variance", variance)
                    },
                    "id = ? AND status = ?",
                    arrayOf(session.id.toString(), BusinessSessionStatus.OPEN.name),
                )
                check(updated == 1) { "営業セッション状態が更新されました。画面を更新してください" }
            }
            insertAudit(
                eventType = type.name,
                referenceId = id,
                detail = "営業日 ${summary.businessDate} / セッションNo.${session.id} / 純売上 ${summary.netSales}円 / 現金差異 ${variance}円",
                operatorName = operatorName,
                createdAt = now,
            )
            if (type == SettlementReportType.Z_SETTLEMENT) {
                insertAudit(
                    eventType = "BUSINESS_CLOSE",
                    referenceId = session.id,
                    detail = "Z精算No.${id}により営業終了 / 営業日 ${summary.businessDate} / 現金実査 ${actual}円 / 過不足 ${variance}円",
                    operatorName = operatorName,
                    createdAt = now,
                )
            }
            if (operationKey != null) bindOperationKey(operationKey, id)
            id
        }
    }

    fun recentSettlements(limit: Int = 50): List<SettlementRecord> {
        db.query(
            "settlement_reports",
            arrayOf(
                "id", "business_session_id", "business_date", "report_type", "sales_gross", "reversal_gross", "net_sales",
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
                    businessSessionId = cursor.getLong(1),
                    businessDate = cursor.getString(2),
                    type = SettlementReportType.valueOf(cursor.getString(3)),
                    salesGross = cursor.getLong(4),
                    reversalGross = cursor.getLong(5),
                    netSales = cursor.getLong(6),
                    expectedCash = cursor.getLong(7),
                    actualCash = cursor.getLong(8),
                    variance = cursor.getLong(9),
                    transactionCount = cursor.getInt(10),
                    reversalCount = cursor.getInt(11),
                    pendingPrints = cursor.getInt(12),
                    heldTickets = cursor.getInt(13),
                    operatorName = cursor.getString(14),
                    createdAt = cursor.getLong(15),
                )
            }
            return result
        }
    }

    private fun SQLiteDatabase.insertDocumentJob(
        type: OperationDocumentType,
        referenceId: Long,
        paperWidthMm: Int,
        payloadText: String,
        now: Long,
    ): Long = insertOrThrow(
        "document_print_jobs",
        null,
        ContentValues().apply {
            put("document_type", type.name)
            put("reference_id", referenceId)
            put("paper_width_mm", if (paperWidthMm >= 80) 80 else 58)
            put("status", PrintJobStatus.PENDING.name)
            put("attempt_count", 0)
            putNull("last_error")
            put("payload_text", payloadText)
            put("created_at", now)
            put("updated_at", now)
        },
    )

    private fun movementTotal(type: CashMovementType, sessionId: Long): Long = longQuery(
        "SELECT COALESCE(SUM(amount), 0) FROM cash_movements WHERE movement_type = ? AND business_session_id = ?",
        arrayOf(type.name, sessionId.toString()),
    )

    private fun queryActiveSession(database: SQLiteDatabase): BusinessSessionRecord? = database.query(
        "business_sessions",
        SESSION_COLUMNS,
        "status = ?",
        arrayOf(BusinessSessionStatus.OPEN.name),
        null,
        null,
        "opened_at DESC",
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toBusinessSessionRecord() else null }

    private fun android.database.Cursor.toBusinessSessionRecord() = BusinessSessionRecord(
        id = getLong(0),
        businessDate = getString(1),
        status = BusinessSessionStatus.valueOf(getString(2)),
        openingCash = getLong(3),
        openedBy = getString(4),
        openedAt = getLong(5),
        closedBy = if (isNull(6)) null else getString(6),
        closedAt = if (isNull(7)) null else getLong(7),
        closingActual = if (isNull(8)) null else getLong(8),
        closeVariance = if (isNull(9)) null else getLong(9),
    )

    private fun BusinessSessionRecord.toWindow() = BusinessSessionWindow(
        id = id,
        businessDate = businessDate,
        openedAt = openedAt,
        closedAt = closedAt,
        openingCash = openingCash,
    )

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
            CREATE TABLE IF NOT EXISTS business_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_date TEXT NOT NULL,
                status TEXT NOT NULL,
                opening_cash INTEGER NOT NULL,
                opened_by TEXT NOT NULL,
                opened_at INTEGER NOT NULL,
                closed_by TEXT,
                closed_at INTEGER,
                closing_actual INTEGER,
                close_variance INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cash_movements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                movement_type TEXT NOT NULL,
                amount INTEGER NOT NULL,
                reason TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                business_session_id INTEGER,
                business_date TEXT,
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
                business_session_id INTEGER,
                business_date TEXT,
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
            CREATE TABLE IF NOT EXISTS reversal_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                reversal_id INTEGER NOT NULL,
                sale_item_id INTEGER NOT NULL,
                product_id TEXT NOT NULL,
                product_name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                tax_category TEXT NOT NULL,
                tax_key TEXT NOT NULL DEFAULT '',
                tax_label TEXT NOT NULL DEFAULT '',
                tax_rate_percent INTEGER NOT NULL DEFAULT 0,
                tax_included INTEGER NOT NULL DEFAULT 0,
                taxable INTEGER NOT NULL DEFAULT 0,
                reduced INTEGER NOT NULL DEFAULT 0,
                tax_symbol TEXT NOT NULL DEFAULT '',
                original_quantity INTEGER NOT NULL,
                return_quantity INTEGER NOT NULL,
                discount_amount INTEGER NOT NULL,
                gross_amount INTEGER NOT NULL,
                FOREIGN KEY(reversal_id) REFERENCES reversal_transactions(id) ON DELETE CASCADE,
                FOREIGN KEY(sale_item_id) REFERENCES sale_items(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS document_print_jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_type TEXT NOT NULL,
                reference_id INTEGER NOT NULL,
                paper_width_mm INTEGER NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL,
                last_error TEXT,
                payload_text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settlement_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                business_session_id INTEGER,
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
        BusinessSessionSchema.ensure(db)
        TaxSnapshotSchema.ensureReversalColumns(db)
        DocumentPrintSafetySchema.ensure(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_business_sessions_status ON business_sessions(status, opened_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cash_movements_session ON cash_movements(business_session_id, movement_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reversal_original_sale ON reversal_transactions(original_sale_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reversal_items_sale_item ON reversal_items(sale_item_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_jobs_status ON document_print_jobs(status, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reversal_session ON reversal_transactions(business_session_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_session ON settlement_reports(business_session_id, report_type)")
    }

    companion object {
        private val SESSION_COLUMNS = arrayOf(
            "id", "business_date", "status", "opening_cash", "opened_by", "opened_at",
            "closed_by", "closed_at", "closing_actual", "close_variance",
        )
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
