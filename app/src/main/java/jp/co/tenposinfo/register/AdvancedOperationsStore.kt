package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate

enum class BusinessSessionStatus(val displayName: String) {
    OPEN("営業中"),
    Z_SETTLED("旧Z精算済み"),
    CLOSED("営業終了"),
}

data class BusinessSessionRecord(
    val id: Long,
    val businessDate: String,
    val status: BusinessSessionStatus,
    val openingCash: Long,
    val openedBy: String,
    val openedAt: Long,
    val closedBy: String?,
    val closedAt: Long?,
    val closingActual: Long?,
    val closeVariance: Long?,
)

data class AdvancedDailySummary(
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

data class ReturnLineRecord(
    val saleItemId: Long,
    val productId: String,
    val productName: String,
    val unitPrice: Long,
    val taxCategory: TaxCategory,
    val taxKey: String = taxCategory.name,
    val taxLabel: String = taxCategory.displayName,
    val taxRatePercent: Int = taxCategory.ratePercent,
    val taxIncluded: Boolean = taxCategory.taxIncluded,
    val taxable: Boolean = taxCategory.taxable,
    val reduced: Boolean = taxCategory.symbol.contains("※"),
    val taxSymbol: String = taxCategory.symbol,
    val originalQuantity: Int,
    val originalDiscount: Long,
    val note: String,
    val returnedQuantity: Int,
    val refundedDiscount: Long,
) {
    val remainingQuantity: Int get() = (originalQuantity - returnedQuantity).coerceAtLeast(0)
    val remainingDiscount: Long get() = (originalDiscount - refundedDiscount).coerceAtLeast(0)

    fun toReturnItem(quantity: Int): CartItem {
        require(quantity in 1..remainingQuantity) { "返品数量が残数を超えています" }
        val allocatedDiscount = if (quantity == remainingQuantity) {
            remainingDiscount
        } else {
            (originalDiscount * quantity / originalQuantity).coerceAtMost(remainingDiscount)
        }
        val product = TaxSnapshot(
            key = taxKey,
            label = taxLabel,
            ratePercent = taxRatePercent,
            taxIncluded = taxIncluded,
            taxable = taxable,
            reduced = reduced,
            symbol = taxSymbol,
        ).applyTo(
            Product(productId, productName, unitPrice, taxCategory, saleItemId.toInt()),
        )
        return CartItem(product, quantity, unitPrice, allocatedDiscount, note)
    }
}

data class ReversalSaveResult(
    val reversalId: Long,
    val refundAmount: Long,
    val printJobId: Long,
    val previewText: String,
)

data class SettlementSaveResult(
    val reportId: Long,
    val printJobId: Long,
    val previewText: String,
)

enum class OperationDocumentType(val displayName: String) {
    REVERSAL_RECEIPT("返品・取消レシート"),
    SETTLEMENT_REPORT("点検・精算票"),
}

data class DocumentPrintJobRecord(
    val id: Long,
    val documentType: OperationDocumentType,
    val referenceId: Long,
    val paperWidthMm: Int,
    val status: PrintJobStatus,
    val attemptCount: Int,
    val lastError: String?,
    val payloadText: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class AdvancedOperationsStore(context: Context) {
    private val baseDatabase = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase = baseDatabase.writableDatabase

    init {
        ensureSchema()
    }

    fun close() = baseDatabase.close()

    fun activeSession(): BusinessSessionRecord? = db.query(
        "business_sessions",
        SESSION_COLUMNS,
        "status = ?",
        arrayOf(BusinessSessionStatus.OPEN.name),
        null,
        null,
        "opened_at DESC",
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) {
            BusinessSessionRecord(
                id = cursor.getLong(0),
                businessDate = cursor.getString(1),
                status = BusinessSessionStatus.valueOf(cursor.getString(2)),
                openingCash = cursor.getLong(3),
                openedBy = cursor.getString(4),
                openedAt = cursor.getLong(5),
                closedBy = if (cursor.isNull(6)) null else cursor.getString(6),
                closedAt = if (cursor.isNull(7)) null else cursor.getLong(7),
                closingActual = if (cursor.isNull(8)) null else cursor.getLong(8),
                closeVariance = if (cursor.isNull(9)) null else cursor.getLong(9),
            )
        } else {
            null
        }
    }

    fun recentSessions(limit: Int = 30): List<BusinessSessionRecord> = db.query(
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
        while (cursor.moveToNext()) {
            result += BusinessSessionRecord(
                id = cursor.getLong(0),
                businessDate = cursor.getString(1),
                status = BusinessSessionStatus.valueOf(cursor.getString(2)),
                openingCash = cursor.getLong(3),
                openedBy = cursor.getString(4),
                openedAt = cursor.getLong(5),
                closedBy = if (cursor.isNull(6)) null else cursor.getString(6),
                closedAt = if (cursor.isNull(7)) null else cursor.getLong(7),
                closingActual = if (cursor.isNull(8)) null else cursor.getLong(8),
                closeVariance = if (cursor.isNull(9)) null else cursor.getLong(9),
            )
        }
        result
    }

    fun startBusinessDay(
        businessDate: LocalDate,
        openingCash: Long,
        operatorName: String,
    ): Long {
        require(BusinessSessionTransitionPolicy.mayStart(businessDate)) { "営業日は本日または前日を指定してください" }
        require(activeSession() == null) { "営業中の営業セッションがあります" }
        require(openingCash >= 0) { "開始釣銭は0円以上で入力してください" }
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val dateText = businessDate.toString()
        val now = System.currentTimeMillis()
        return db.transaction {
            val id = insertOrThrow(
                "business_sessions",
                null,
                ContentValues().apply {
                    put("business_date", dateText)
                    put("status", BusinessSessionStatus.OPEN.name)
                    put("opening_cash", openingCash)
                    put("opened_by", operatorName.trim())
                    put("opened_at", now)
                },
            )
            insertAudit("BUSINESS_OPEN", id, "営業日 $dateText / セッションNo.$id / 開始釣銭 ${openingCash}円", operatorName, now)
            id
        }
    }

    @Deprecated("v0.24以降、営業終了はZ精算と同一トランザクションで完了します")
    fun endBusinessDay(actualCash: Long, operatorName: String): Long {
        throw IllegalStateException("営業終了はZ精算の完了時に自動で行われます")
    }

    fun dailySummary(date: LocalDate = activeSession()?.let { LocalDate.parse(it.businessDate) } ?: LocalDate.now()): AdvancedDailySummary {
        BusinessSessionSchema.ensure(db)
        val active = activeSession()
        val session = active?.takeIf { it.businessDate == date.toString() }?.let {
            BusinessSessionSchema.sessionById(db, it.id)
        } ?: BusinessSessionSchema.sessionForDate(db, date)
            ?: error("営業日 ${date} の営業セッションが見つかりません")
        val sessionId = session.id
        val dateText = session.businessDate
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
        val openingCash = session.openingCash
        val expectedCash = openingCash + (paymentMap[PaymentMethod.CASH.name] ?: 0L) + cashIn - cashOut
        val pendingPrints = longQuery(
            "SELECT COUNT(*) FROM print_jobs WHERE status <> ?",
            arrayOf(PrintJobStatus.COMPLETED.name),
        ).toInt() + longQuery(
            "SELECT COUNT(*) FROM document_print_jobs WHERE status <> ?",
            arrayOf(PrintJobStatus.COMPLETED.name),
        ).toInt()
        val heldTickets = longQuery("SELECT COUNT(*) FROM held_tickets").toInt()
        val settled = longQuery(
            "SELECT COUNT(*) FROM settlement_reports WHERE business_session_id = ? AND report_type = ?",
            arrayOf(sessionId.toString(), SettlementReportType.Z_SETTLEMENT.name),
        ) > 0
        return AdvancedDailySummary(
            businessDate = dateText,
            salesGross = salesGross,
            reversalGross = reversalGross,
            netSales = salesGross - reversalGross,
            transactionCount = transactionCount,
            reversalCount = reversalCount,
            paymentTotals = paymentMap.map { PaymentTotal(it.key, it.value) },
            openingCash = openingCash,
            cashIn = cashIn,
            cashOut = cashOut,
            expectedCash = expectedCash,
            pendingPrints = pendingPrints,
            heldTickets = heldTickets,
            settled = settled,
        )
    }

    fun recordCashMovement(type: CashMovementType, amount: Long, reason: String, operatorName: String): Long {
        val session = activeSession() ?: error("営業開始後に入出金を登録してください")
        require(session.status == BusinessSessionStatus.OPEN) { "Z精算後は入出金できません" }
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
                    put("business_session_id", session.id)
                    put("business_date", session.businessDate)
                    put("created_at", now)
                },
            )
            insertAudit("CASH_${type.name}", id, "${type.displayName} ${amount}円 / ${reason.trim()}", operatorName, now)
            id
        }
    }

    fun recentCashMovements(limit: Int = 50): List<CashMovementRecord> = db.query(
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
        result
    }

    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        operatorName: String,
        paperWidthMm: Int,
    ): SettlementSaveResult {
        val session = activeSession() ?: error("営業中の営業セッションがありません")
        require(session.status == BusinessSessionStatus.OPEN) { "この営業セッションは既に終了しています" }
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val summary = dailySummary(LocalDate.parse(session.businessDate))
        if (type == SettlementReportType.Z_SETTLEMENT && summary.settled) error("この営業セッションは既にZ精算済みです")
        val actual = actualCash ?: summary.expectedCash
        require(actual >= 0) { "現金実査額は0円以上で入力してください" }
        val variance = actual - summary.expectedCash
        val now = System.currentTimeMillis()
        var previewText = ""
        var printJobId = 0L
        val reportId = db.transaction {
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
            val document = SettlementDocumentData(
                reportId = id,
                businessDate = summary.businessDate,
                type = type,
                createdAt = now,
                operatorName = operatorName.trim(),
                salesGross = summary.salesGross,
                reversalGross = summary.reversalGross,
                netSales = summary.netSales,
                openingCash = summary.openingCash,
                cashIn = summary.cashIn,
                cashOut = summary.cashOut,
                expectedCash = summary.expectedCash,
                actualCash = actual,
                variance = variance,
                transactionCount = summary.transactionCount,
                reversalCount = summary.reversalCount,
                pendingPrints = summary.pendingPrints,
                heldTickets = summary.heldTickets,
                paymentTotals = summary.paymentTotals,
            )
            previewText = OperationDocumentRenderer.renderSettlement(document, ReceiptPaper.fromWidth(paperWidthMm))
            printJobId = insertDocumentJob(OperationDocumentType.SETTLEMENT_REPORT, id, paperWidthMm, previewText, now)
            if (type == SettlementReportType.Z_SETTLEMENT) {
                val updated = update(
                    "business_sessions",
                    ContentValues().apply {
                        put("status", BusinessSessionStatus.CLOSED.name)
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
            insertAudit(type.name, id, "営業日 ${summary.businessDate} / セッションNo.${session.id} / 純売上 ${summary.netSales}円 / 現金差異 ${variance}円", operatorName, now)
            if (type == SettlementReportType.Z_SETTLEMENT) {
                insertAudit("BUSINESS_CLOSE", session.id, "Z精算No.$idにより営業終了 / 現金実査 ${actual}円 / 過不足 ${variance}円", operatorName, now)
            }
            id
        }
        return SettlementSaveResult(reportId, printJobId, previewText)
    }

    fun recentSettlements(limit: Int = 50): List<SettlementRecord> = db.query(
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
        result
    }

    fun loadReturnableLines(saleId: Long): List<ReturnLineRecord> = db.rawQuery(
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
               COALESCE(SUM(ri.return_quantity), 0) AS returned_quantity,
               COALESCE(SUM(ri.discount_amount), 0) AS refunded_discount
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
        val result = mutableListOf<ReturnLineRecord>()
        while (cursor.moveToNext()) {
            val legacy = TaxCategory.valueOf(cursor.getString(4))
            result += ReturnLineRecord(
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
    ): ReversalSaveResult {
        val session = activeSession() ?: error("営業開始後に返品・取消を実行してください")
        require(session.status == BusinessSessionStatus.OPEN) { "Z精算後は返品・取消できません" }
        require(reason.isNotBlank()) { "理由を入力してください" }
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val saleTotal = longQuery("SELECT COALESCE(total_amount, -1) FROM sales WHERE id = ?", arrayOf(originalSaleId.toString()))
        require(saleTotal >= 0) { "元売上が見つかりません" }
        val lines = loadReturnableLines(originalSaleId)
        require(lines.isNotEmpty()) { "元売上の商品明細が見つかりません" }
        if (type == ReversalType.CANCEL) {
            require(lines.all { it.returnedQuantity == 0 }) { "一部返品済みの売上は取消できません" }
        }
        val selected = lines.mapNotNull { line ->
            val quantity = if (type == ReversalType.CANCEL) line.remainingQuantity else (requestedQuantities[line.saleItemId] ?: 0)
            if (quantity <= 0) null else line to line.toReturnItem(quantity)
        }
        require(selected.isNotEmpty()) { "返品する商品と数量を選択してください" }
        require(selected.all { (line, item) -> item.quantity <= line.remainingQuantity }) { "返品数量が残数を超えています" }
        val items = selected.map { it.second }
        val taxSummary = TaxEngine.calculate(items)
        val refundTotal = taxSummary.grossAmount
        require(refundTotal > 0) { "返金額が0円です" }
        val originalPayments = mutableListOf<PaymentTotal>()
        db.query(
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
        if (originalPayments.isEmpty()) originalPayments += PaymentTotal("OTHER", saleTotal)
        val originalPaymentTotal = originalPayments.sumOf { it.amount }.coerceAtLeast(1)
        val refundPayments = mutableListOf<PaymentTotal>()
        var allocated = 0L
        originalPayments.forEachIndexed { index, payment ->
            val amount = if (index == originalPayments.lastIndex) {
                refundTotal - allocated
            } else {
                refundTotal * payment.amount / originalPaymentTotal
            }.coerceAtLeast(0)
            allocated += amount
            if (amount > 0) refundPayments += PaymentTotal(payment.method, amount)
        }
        val now = System.currentTimeMillis()
        var previewText = ""
        var printJobId = 0L
        val reversalId = db.transaction {
            val id = insertOrThrow(
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
                        put("reversal_id", id)
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
                        put("reversal_id", id)
                        put("payment_method", payment.method)
                        put("amount", payment.amount)
                    },
                )
            }
            val document = ReversalDocumentData(
                reversalId = id,
                originalSaleId = originalSaleId,
                type = type,
                createdAt = now,
                operatorName = operatorName.trim(),
                reason = reason.trim(),
                items = items,
                taxSummary = taxSummary,
                refundPayments = refundPayments,
            )
            previewText = OperationDocumentRenderer.renderReversal(document, ReceiptPaper.fromWidth(paperWidthMm))
            printJobId = insertDocumentJob(OperationDocumentType.REVERSAL_RECEIPT, id, paperWidthMm, previewText, now)
            insertAudit(type.name, id, "元売上 No.$originalSaleId / 返金 ${refundTotal}円 / ${reason.trim()}", operatorName, now)
            id
        }
        return ReversalSaveResult(reversalId, refundTotal, printJobId, previewText)
    }

    fun recentReversals(limit: Int = 50): List<ReversalRecord> = db.query(
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
        result
    }

    fun listDocumentPrintJobs(limit: Int = 100): List<DocumentPrintJobRecord> = db.query(
        "document_print_jobs",
        DOCUMENT_JOB_COLUMNS,
        null,
        null,
        null,
        null,
        "created_at DESC",
        limit.coerceIn(1, 500).toString(),
    ).use { cursor ->
        val result = mutableListOf<DocumentPrintJobRecord>()
        while (cursor.moveToNext()) {
            result += DocumentPrintJobRecord(
                id = cursor.getLong(0),
                documentType = OperationDocumentType.valueOf(cursor.getString(1)),
                referenceId = cursor.getLong(2),
                paperWidthMm = cursor.getInt(3),
                status = PrintJobStatus.valueOf(cursor.getString(4)),
                attemptCount = cursor.getInt(5),
                lastError = if (cursor.isNull(6)) null else cursor.getString(6),
                payloadText = cursor.getString(7),
                createdAt = cursor.getLong(8),
                updatedAt = cursor.getLong(9),
            )
        }
        result
    }

    fun retryDocumentPrint(jobId: Long) {
        db.update(
            "document_print_jobs",
            ContentValues().apply {
                put("status", PrintJobStatus.RETRY.name)
                putNull("last_error")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(jobId.toString()),
        )
    }

    fun processDocumentPrint(jobId: Long, gateway: PrinterGateway): Result<Unit> {
        val job = listDocumentPrintJobs(500).firstOrNull { it.id == jobId }
            ?: return Result.failure(IllegalArgumentException("印刷ジョブが見つかりません"))
        val attempt = job.attemptCount + 1
        db.update(
            "document_print_jobs",
            ContentValues().apply {
                put("status", PrintJobStatus.PRINTING.name)
                put("attempt_count", attempt)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(jobId.toString()),
        )
        val result = gateway.send(TextEscPosEncoder.encode(job.payloadText))
        result.onSuccess {
            db.update(
                "document_print_jobs",
                ContentValues().apply {
                    put("status", PrintJobStatus.COMPLETED.name)
                    putNull("last_error")
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(jobId.toString()),
            )
        }.onFailure { error ->
            db.update(
                "document_print_jobs",
                ContentValues().apply {
                    put("status", if (attempt >= 5) PrintJobStatus.FAILED.name else PrintJobStatus.RETRY.name)
                    put("last_error", (error.message ?: error.javaClass.simpleName).take(500))
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(jobId.toString()),
            )
        }
        return result
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

    private fun longQuery(sql: String, args: Array<String> = emptyArray()): Long =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun SQLiteDatabase.insertAudit(eventType: String, referenceId: Long, detail: String, operatorName: String, createdAt: Long) {
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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_business_sessions_status ON business_sessions(status, opened_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reversal_items_sale_item ON reversal_items(sale_item_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_jobs_status ON document_print_jobs(status, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_date ON settlement_reports(business_date, report_type)")
        BusinessSessionSchema.ensure(db)
        TaxSnapshotSchema.ensureReversalColumns(db)
    }

    companion object {
        private val SESSION_COLUMNS = arrayOf(
            "id", "business_date", "status", "opening_cash", "opened_by", "opened_at",
            "closed_by", "closed_at", "closing_actual", "close_variance",
        )
        private val DOCUMENT_JOB_COLUMNS = arrayOf(
            "id", "document_type", "reference_id", "paper_width_mm", "status",
            "attempt_count", "last_error", "payload_text", "created_at", "updated_at",
        )

        fun isBusinessOpen(context: Context): Boolean {
            val store = AdvancedOperationsStore(context.applicationContext)
            return try {
                store.activeSession()?.status == BusinessSessionStatus.OPEN
            } finally {
                store.close()
            }
        }
    }
}

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}
