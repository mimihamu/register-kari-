package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ManualRefundMethodV135(val displayName: String) {
    CASH("現金"),
    CARD("カード"),
    GIFT_CERTIFICATE("商品券"),
    ACCOUNT_RECEIVABLE("掛売"),
    OTHER("その他"),
}

data class ManualReturnLineRequestV135(
    val product: Product,
    val quantity: Int,
)

data class ManualReturnRequestV135(
    val lines: List<ManualReturnLineRequestV135>,
    val reason: String,
    val refundMethod: ManualRefundMethodV135,
)

data class ManualReturnResultV135(
    val manualReturnId: Long,
    val signedGrossAmount: Long,
    val printJobId: Long,
    val previewText: String,
)

data class ManualReturnAccountingContributionV135(
    val returnCount: Int,
    val signedGrossAmount: Long,
    val signedPaymentTotals: Map<String, Long>,
)

object ManualReturnPolicyV135 {
    fun toPositiveCartItems(
        request: ManualReturnRequestV135,
        reasonRequired: Boolean,
    ): List<CartItem> {
        require(request.lines.isNotEmpty()) { "返品商品を1件以上追加してください" }
        if (reasonRequired) require(request.reason.isNotBlank()) { "返品理由を入力してください" }
        return request.lines.map { line ->
            require(line.quantity > 0) { "返品数量は1以上で入力してください" }
            require(line.product.unitPrice >= 0) { "商品単価が不正です" }
            CartItem(
                product = line.product,
                quantity = line.quantity,
                unitPrice = line.product.unitPrice,
            )
        }
    }

    fun signedQuantity(quantity: Int): Int {
        require(quantity > 0)
        return -quantity
    }

    fun signedAmount(amount: Long): Long {
        require(amount > 0)
        return -amount
    }
}

class ManualReturnSettingsV135(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isReasonRequired(): Boolean = prefs.getBoolean(KEY_REASON_REQUIRED, true)

    fun setReasonRequired(required: Boolean) {
        prefs.edit().putBoolean(KEY_REASON_REQUIRED, required).apply()
    }

    companion object {
        private const val PREFS = "manual_return_settings_v135"
        private const val KEY_REASON_REQUIRED = "return_reason_required"
    }
}

class ManualReturnStoreV135(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = RegisterDatabase(appContext)
    private val db: SQLiteDatabase = database.writableDatabase
    private val settings = ManualReturnSettingsV135(appContext)

    init {
        // operation_audit / document_print_jobs / business_sessions を既存管理機能と同一仕様で用意する。
        OperationsStore(appContext).close()
        ensureSchema()
    }

    fun products(): List<Product> = database.loadProducts()

    fun reasonRequired(): Boolean = settings.isReasonRequired()

    fun setReasonRequired(required: Boolean) = settings.setReasonRequired(required)

    fun create(
        request: ManualReturnRequestV135,
        operatorName: String,
        managerName: String,
        requestId: String,
    ): ManualReturnResultV135 {
        require(operatorName.isNotBlank()) { "担当者を確認できません" }
        require(managerName.isNotBlank()) { "責任者を確認できません" }
        require(requestId.isNotBlank()) { "操作IDがありません" }
        val items = ManualReturnPolicyV135.toPositiveCartItems(request, settings.isReasonRequired())
        val taxSummary = TaxEngine.calculate(items)
        val gross = taxSummary.grossAmount
        require(gross > 0) { "返金額が0円です" }
        val signedGross = ManualReturnPolicyV135.signedAmount(gross)
        val now = System.currentTimeMillis()
        val paperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext)

        db.beginTransaction()
        try {
            val session = currentOpenSession(db)
                ?: throw IllegalStateException("営業開始後に元取引なし返品を実行してください")
            val id = db.insertOrThrow(
                "manual_return_transactions",
                null,
                ContentValues().apply {
                    put("gross_amount", signedGross)
                    put("reason", request.reason.trim())
                    put("refund_method", request.refundMethod.name)
                    put("operator_name", operatorName.trim())
                    put("manager_name", managerName.trim())
                    put("approval_request_id", requestId.trim())
                    put("business_session_id", session.first)
                    put("business_date", session.second)
                    put("created_at", now)
                },
            )
            items.forEachIndexed { index, item ->
                db.insertOrThrow(
                    "manual_return_items",
                    null,
                    ContentValues().apply {
                        put("manual_return_id", id)
                        put("line_no", index + 1)
                        put("product_id", item.product.id)
                        put("product_name", item.product.name)
                        put("unit_price", item.unitPrice)
                        put("quantity", ManualReturnPolicyV135.signedQuantity(item.quantity))
                        put("line_amount", -item.baseAmount)
                        put("tax_category", item.product.taxCategory.name)
                        put("tax_key", item.product.taxKey)
                        put("tax_label", item.product.taxLabel)
                        put("tax_rate_percent", item.product.taxRatePercent)
                        put("tax_included", if (item.product.taxIncluded) 1 else 0)
                        put("taxable", if (item.product.taxable) 1 else 0)
                        put("reduced", if (item.product.reducedTax) 1 else 0)
                        put("tax_symbol", item.product.taxSymbol)
                    },
                )
            }
            db.insertOrThrow(
                "manual_return_payments",
                null,
                ContentValues().apply {
                    put("manual_return_id", id)
                    put("payment_method", request.refundMethod.name)
                    put("amount", signedGross)
                },
            )

            val preview = ManualReturnDocumentRendererV135.render(
                manualReturnId = id,
                businessDate = session.second,
                createdAt = now,
                operatorName = operatorName.trim(),
                managerName = managerName.trim(),
                request = request,
                items = items,
                taxSummary = taxSummary,
            )
            val printJobId = db.insertOrThrow(
                "document_print_jobs",
                null,
                ContentValues().apply {
                    // 既存の返品票自動印刷設定を共有する。manual return は負の参照IDで通常返品No.と衝突させない。
                    put("document_type", OperationDocumentType.REVERSAL_RECEIPT.name)
                    put("reference_id", -id)
                    put("paper_width_mm", if (paperWidthMm >= 80) 80 else 58)
                    put("status", PrintJobStatus.PENDING.name)
                    put("attempt_count", 0)
                    putNull("last_error")
                    put("payload_text", preview)
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
            db.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", "MANUAL_RETURN")
                    put("reference_id", id)
                    put(
                        "detail",
                        "元取引不明返品 / ${signedGross}円 / 返金:${request.refundMethod.displayName} / 理由:${request.reason.trim()} / 承認:${managerName.trim()} / requestId:${requestId.trim()}",
                    )
                    put("operator_name", operatorName.trim())
                    put("created_at", now)
                },
            )
            db.setTransactionSuccessful()
            return ManualReturnResultV135(id, signedGross, printJobId, preview)
        } finally {
            db.endTransaction()
        }
    }

    fun accountingContribution(sessionId: Long): ManualReturnAccountingContributionV135 {
        if (!SchemaMigration.tableExists(db, "manual_return_transactions")) {
            return ManualReturnAccountingContributionV135(0, 0L, emptyMap())
        }
        val pair = db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(gross_amount), 0) FROM manual_return_transactions WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getLong(1) else 0 to 0L
        }
        val payments = linkedMapOf<String, Long>()
        db.rawQuery(
            """
            SELECT p.payment_method, COALESCE(SUM(p.amount), 0)
            FROM manual_return_payments p
            INNER JOIN manual_return_transactions r ON r.id = p.manual_return_id
            WHERE r.business_session_id = ?
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) payments[cursor.getString(0)] = cursor.getLong(1)
        }
        return ManualReturnAccountingContributionV135(pair.first, pair.second, payments)
    }

    override fun close() = database.close()

    private fun currentOpenSession(database: SQLiteDatabase): Pair<Long, String>? = database.rawQuery(
        "SELECT id, business_date FROM business_sessions WHERE status = ? ORDER BY opened_at DESC LIMIT 1",
        arrayOf(BusinessSessionStatus.OPEN.name),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.getLong(0) to cursor.getString(1)
    }

    private fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS manual_return_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                gross_amount INTEGER NOT NULL CHECK(gross_amount < 0),
                reason TEXT NOT NULL DEFAULT '',
                refund_method TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                manager_name TEXT NOT NULL,
                approval_request_id TEXT NOT NULL UNIQUE,
                business_session_id INTEGER NOT NULL,
                business_date TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(business_session_id) REFERENCES business_sessions(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS manual_return_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                manual_return_id INTEGER NOT NULL,
                line_no INTEGER NOT NULL,
                product_id TEXT NOT NULL,
                product_name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                quantity INTEGER NOT NULL CHECK(quantity < 0),
                line_amount INTEGER NOT NULL CHECK(line_amount <= 0),
                tax_category TEXT NOT NULL,
                tax_key TEXT NOT NULL,
                tax_label TEXT NOT NULL,
                tax_rate_percent INTEGER NOT NULL,
                tax_included INTEGER NOT NULL,
                taxable INTEGER NOT NULL,
                reduced INTEGER NOT NULL,
                tax_symbol TEXT NOT NULL,
                FOREIGN KEY(manual_return_id) REFERENCES manual_return_transactions(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS manual_return_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                manual_return_id INTEGER NOT NULL,
                payment_method TEXT NOT NULL,
                amount INTEGER NOT NULL CHECK(amount < 0),
                FOREIGN KEY(manual_return_id) REFERENCES manual_return_transactions(id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_manual_return_session ON manual_return_transactions(business_session_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_manual_return_items_return ON manual_return_items(manual_return_id, line_no)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_manual_return_payments_return ON manual_return_payments(manual_return_id)")
    }
}

class ManualReturnCoordinatorV135(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val store = ManualReturnStoreV135(appContext)

    fun products(): List<Product> = store.products()
    fun reasonRequired(): Boolean = store.reasonRequired()

    fun setReasonRequired(required: Boolean, managerPin: String) {
        requireAuthorizedActor(managerPin)
        store.setReasonRequired(required)
    }

    fun create(request: ManualReturnRequestV135, managerPin: String): ManualReturnResultV135 {
        val (operator, managerName) = requireAuthorizedActor(managerPin)
        val result = store.create(
            request = request,
            operatorName = operator.name,
            managerName = managerName,
            requestId = UUID.randomUUID().toString(),
        )
        runCatching { AutomaticPrintScheduler.enqueueNow(appContext) }
        return result
    }

    override fun close() = store.close()

    private fun requireAuthorizedActor(managerPin: String): Pair<AuthenticatedOperator, String> {
        val operator = OperatorSessionRegistry.current(appContext)
            ?: throw SecurityException("ログインセッションが失効しています。販売画面から再ログインしてください")
        if (!operator.allows(RegisterPermission.REVERSAL)) {
            throw SecurityException("${RegisterPermission.REVERSAL.displayName}の権限がありません")
        }
        require(managerPin.isNotBlank()) { "責任者PINを入力してください" }
        val managerName = AdminSettingsStore(appContext).use { it.managerNameForPin(managerPin) }
            ?: throw SecurityException("責任者PINが違います")
        return operator to managerName
    }
}

object ManualReturnAccountingV135 {
    fun apply(context: Context, base: DailyOperationsSummary): DailyOperationsSummary {
        ManualReturnStoreV135(context.applicationContext).use { store ->
            val contribution = store.accountingContribution(base.businessSessionId)
            if (contribution.returnCount == 0 && contribution.signedGrossAmount == 0L) return base
            val payments = linkedMapOf<String, Long>()
            base.paymentTotals.forEach { payments[it.method] = (payments[it.method] ?: 0L) + it.amount }
            contribution.signedPaymentTotals.forEach { (method, signedAmount) ->
                payments[method] = (payments[method] ?: 0L) + signedAmount
            }
            val manualRefundAbsolute = -contribution.signedGrossAmount
            val reversalGross = base.reversalGross + manualRefundAbsolute
            val expectedCash = OperationsMath.expectedCash(
                cashSalesAfterRefunds = payments[ManualRefundMethodV135.CASH.name] ?: 0L,
                cashIn = base.cashIn,
                cashOut = base.cashOut,
                openingCash = base.openingCash,
            )
            return base.copy(
                reversalGross = reversalGross,
                netSales = base.salesGross - reversalGross,
                reversalCount = base.reversalCount + contribution.returnCount,
                paymentTotals = payments.map { PaymentTotal(it.key, it.value) },
                expectedCash = expectedCash,
            )
        }
    }
}

object ManualReturnDocumentRendererV135 {
    fun render(
        manualReturnId: Long,
        businessDate: String,
        createdAt: Long,
        operatorName: String,
        managerName: String,
        request: ManualReturnRequestV135,
        items: List<CartItem>,
        taxSummary: TaxSummary,
    ): String {
        val yen = NumberFormat.getCurrencyInstance(Locale.JAPAN)
        val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(createdAt))
        return buildString {
            appendLine("================================")
            appendLine("      返品（元取引不明）")
            appendLine("================================")
            appendLine("返品No. MR-$manualReturnId")
            appendLine("営業日 $businessDate")
            appendLine("日時   $timestamp")
            appendLine("--------------------------------")
            items.forEach { item ->
                appendLine("${item.product.name} ${item.product.taxSymbol}")
                appendLine("  -${item.quantity} × ${yen.format(item.unitPrice)}   -${yen.format(item.baseAmount)}")
            }
            appendLine("--------------------------------")
            taxSummary.buckets.forEach { bucket ->
                val label = if (bucket.taxable) "${bucket.ratePercent}%対象" else "非課税"
                appendLine("$label  -${yen.format(bucket.grossAmount)}")
                if (bucket.taxable) appendLine("  消費税等  -${yen.format(bucket.taxAmount)}")
            }
            appendLine("--------------------------------")
            appendLine("返金合計  -${yen.format(taxSummary.grossAmount)}")
            appendLine("返金方法  ${request.refundMethod.displayName}")
            appendLine("返品理由  ${request.reason.trim().ifBlank { "（任意・未入力）" }}")
            appendLine("担当      $operatorName")
            appendLine("責任者    $managerName")
            if (request.refundMethod == ManualRefundMethodV135.CARD) {
                appendLine("※カード端末側の返金操作を別途確認してください")
            }
            appendLine("================================")
        }
    }
}
