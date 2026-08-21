package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlin.math.abs

/**
 * REP-004 immutable settlement snapshot introduced in v1.35.
 *
 * The legacy settlement row and settlement_payment_totals remain the canonical normalized header and
 * tender tables. This sidecar freezes the remaining report inputs (REP-001 metrics, tax-rate buckets,
 * issuer metadata and the optional-X cash-entry flag) and also stores one canonical JSON document.
 * All writes are executed through the same SQLiteDatabase transaction that inserts settlement_reports.
 */
internal data class FrozenSettlementSnapshotV135(
    val issuer: InvoiceIssuerProfile,
    val rep001Totals: SettlementRep001TotalsV135,
    val taxBreakdown: List<SettlementTaxRateBucketV135>,
    val actualCashEntered: Boolean,
    val json: String,
)

internal object SettlementSnapshotSchemaV135 {
    const val SNAPSHOT_VERSION = 2

    private const val METRICS_TABLE = "settlement_snapshot_metrics_v135"
    private const val TAX_TABLE = "settlement_snapshot_tax_v135"
    private const val JSON_TABLE = "settlement_snapshot_json_v135"

    private data class Core(
        val reportId: Long,
        val businessSessionId: Long,
        val businessDate: String,
        val reportType: SettlementReportType,
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
        val openingCash: Long,
        val cashIn: Long,
        val cashOut: Long,
    )

    fun ensure(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $METRICS_TABLE (
                report_id INTEGER PRIMARY KEY,
                discount_total_yen INTEGER NOT NULL,
                tax_total_yen INTEGER NOT NULL,
                item_count INTEGER NOT NULL,
                guest_count INTEGER NOT NULL,
                actual_cash_entered INTEGER NOT NULL,
                store_name TEXT NOT NULL,
                store_address TEXT NOT NULL,
                store_phone TEXT NOT NULL,
                registration_number TEXT NOT NULL,
                FOREIGN KEY(report_id) REFERENCES settlement_reports(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TAX_TABLE (
                report_id INTEGER NOT NULL,
                sequence_no INTEGER NOT NULL,
                rate_percent INTEGER NOT NULL,
                taxable INTEGER NOT NULL,
                target_amount_yen INTEGER NOT NULL,
                tax_amount_yen INTEGER NOT NULL,
                PRIMARY KEY(report_id, sequence_no),
                FOREIGN KEY(report_id) REFERENCES settlement_reports(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $JSON_TABLE (
                report_id INTEGER PRIMARY KEY,
                snapshot_version INTEGER NOT NULL,
                snapshot_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(report_id) REFERENCES settlement_reports(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_snapshot_tax_report ON $TAX_TABLE(report_id, sequence_no)")
    }

    /** Called from SettlementSnapshotSchemaV027.savePaymentTotals() inside recordSettlement's transaction. */
    fun save(
        db: SQLiteDatabase,
        reportId: Long,
        paymentTotals: List<PaymentTotal>,
    ) {
        require(reportId > 0L) { "reportId must be positive" }
        ensure(db)
        val core = loadCore(db, reportId)
            ?: throw IllegalStateException("点検・精算スナップショット元データが見つかりません: $reportId")
        val rep001 = calculateRep001(db, core.businessSessionId, core.createdAt)
        val taxBreakdown = calculateTaxBreakdown(db, core.businessSessionId, core.createdAt)
        val issuer = TaxInvoiceSettingsRegistry.current().issuer
        val actualCashEntered = SettlementActualCashPresentationV135.currentInputEnteredOrNull()
            ?: (core.reportType == SettlementReportType.Z_SETTLEMENT)

        db.insertWithOnConflict(
            METRICS_TABLE,
            null,
            ContentValues().apply {
                put("report_id", reportId)
                put("discount_total_yen", rep001.discountTotalYen)
                put("tax_total_yen", rep001.taxTotalYen)
                put("item_count", rep001.itemCount)
                put("guest_count", rep001.guestCount)
                put("actual_cash_entered", if (actualCashEntered) 1 else 0)
                put("store_name", issuer.storeName)
                put("store_address", issuer.address)
                put("store_phone", issuer.phone)
                put("registration_number", issuer.registrationNumber)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )

        db.delete(TAX_TABLE, "report_id = ?", arrayOf(reportId.toString()))
        taxBreakdown.forEachIndexed { index, bucket ->
            db.insertOrThrow(
                TAX_TABLE,
                null,
                ContentValues().apply {
                    put("report_id", reportId)
                    put("sequence_no", index + 1)
                    put("rate_percent", bucket.ratePercent)
                    put("taxable", if (bucket.taxable) 1 else 0)
                    put("target_amount_yen", bucket.targetAmountYen)
                    put("tax_amount_yen", bucket.taxAmountYen)
                },
            )
        }

        val json = buildJson(core, rep001, taxBreakdown, paymentTotals, issuer, actualCashEntered)
        db.insertWithOnConflict(
            JSON_TABLE,
            null,
            ContentValues().apply {
                put("report_id", reportId)
                put("snapshot_version", SNAPSHOT_VERSION)
                put("snapshot_json", json)
                put("created_at", core.createdAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        val updated = db.update(
            "settlement_reports",
            ContentValues().apply { put("snapshot_version", SNAPSHOT_VERSION) },
            "id = ?",
            arrayOf(reportId.toString()),
        )
        check(updated == 1) { "点検・精算スナップショット版を更新できませんでした" }
    }

    fun load(db: SQLiteDatabase, reportId: Long): FrozenSettlementSnapshotV135? {
        val metrics = runCatching {
            db.query(
                METRICS_TABLE,
                arrayOf(
                    "discount_total_yen", "tax_total_yen", "item_count", "guest_count",
                    "actual_cash_entered", "store_name", "store_address", "store_phone", "registration_number",
                ),
                "report_id = ?",
                arrayOf(reportId.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val totals = SettlementRep001TotalsV135(
                    discountTotalYen = cursor.getLong(0),
                    taxTotalYen = cursor.getLong(1),
                    itemCount = cursor.getInt(2),
                    guestCount = cursor.getInt(3),
                )
                val entered = cursor.getInt(4) != 0
                val issuer = InvoiceIssuerProfile(
                    storeName = cursor.getString(5),
                    address = cursor.getString(6),
                    phone = cursor.getString(7),
                    registrationNumber = cursor.getString(8),
                )
                Triple(totals, entered, issuer)
            }
        }.getOrNull() ?: return null

        val tax = runCatching {
            db.query(
                TAX_TABLE,
                arrayOf("rate_percent", "taxable", "target_amount_yen", "tax_amount_yen"),
                "report_id = ?",
                arrayOf(reportId.toString()),
                null,
                null,
                "sequence_no ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SettlementTaxRateBucketV135(
                                ratePercent = cursor.getInt(0),
                                taxable = cursor.getInt(1) != 0,
                                targetAmountYen = cursor.getLong(2),
                                taxAmountYen = cursor.getLong(3),
                            ),
                        )
                    }
                }
            }
        }.getOrNull() ?: return null

        val json = snapshotJson(db, reportId) ?: return null
        return FrozenSettlementSnapshotV135(
            issuer = metrics.third,
            rep001Totals = metrics.first,
            taxBreakdown = tax,
            actualCashEntered = metrics.second,
            json = json,
        )
    }

    fun snapshotJson(db: SQLiteDatabase, reportId: Long): String? = runCatching {
        db.query(
            JSON_TABLE,
            arrayOf("snapshot_json"),
            "report_id = ? AND snapshot_version = ?",
            arrayOf(reportId.toString(), SNAPSHOT_VERSION.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun loadCore(db: SQLiteDatabase, reportId: Long): Core? = db.query(
        "settlement_reports",
        arrayOf(
            "id", "business_session_id", "business_date", "report_type",
            "sales_gross", "reversal_gross", "net_sales", "expected_cash", "actual_cash", "variance",
            "transaction_count", "reversal_count", "pending_prints", "held_tickets", "operator_name", "created_at",
            "opening_cash", "cash_in", "cash_out",
        ),
        "id = ?",
        arrayOf(reportId.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        Core(
            reportId = cursor.getLong(0),
            businessSessionId = cursor.getLong(1),
            businessDate = cursor.getString(2),
            reportType = SettlementReportType.valueOf(cursor.getString(3)),
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
            openingCash = cursor.getLong(16),
            cashIn = cursor.getLong(17),
            cashOut = cursor.getLong(18),
        )
    }

    private fun calculateRep001(
        db: SQLiteDatabase,
        businessSessionId: Long,
        cutoffCreatedAt: Long,
    ): SettlementRep001TotalsV135 {
        val args = arrayOf(businessSessionId.toString(), cutoffCreatedAt.toString())
        val discount = scalarLong(
            db,
            """
            SELECT COALESCE(SUM(si.discount_amount), 0)
            FROM sale_items si INNER JOIN sales s ON s.id = si.sale_id
            WHERE s.business_session_id = ? AND s.created_at <= ?
            """.trimIndent(),
            args,
        )
        val tax = scalarLong(
            db,
            "SELECT COALESCE(SUM(tax_amount), 0) FROM sales WHERE business_session_id = ? AND created_at <= ?",
            args,
        )
        val itemCount = scalarLong(
            db,
            """
            SELECT COALESCE(SUM(si.quantity), 0)
            FROM sale_items si INNER JOIN sales s ON s.id = si.sale_id
            WHERE s.business_session_id = ? AND s.created_at <= ?
            """.trimIndent(),
            args,
        ).toInt()
        val guestCount = scalarLong(
            db,
            "SELECT COALESCE(SUM(guest_count), 0) FROM sales WHERE business_session_id = ? AND created_at <= ?",
            args,
        ).toInt()
        return SettlementRep001TotalsV135(discount, tax, itemCount, guestCount)
    }

    private fun calculateTaxBreakdown(
        db: SQLiteDatabase,
        businessSessionId: Long,
        cutoffCreatedAt: Long,
    ): List<SettlementTaxRateBucketV135> {
        val summaries = mutableListOf<Pair<Int, TaxSummary>>()
        queryIds(
            db,
            "SELECT id FROM sales WHERE business_session_id = ? AND created_at <= ? ORDER BY id",
            businessSessionId,
            cutoffCreatedAt,
        ).forEach { id -> saleItems(db, id).takeIf { it.isNotEmpty() }?.let { summaries += 1 to TaxEngine.calculate(it) } }
        if (SchemaMigration.tableExists(db, "reversal_transactions")) {
            queryIds(
                db,
                "SELECT id FROM reversal_transactions WHERE business_session_id = ? AND created_at <= ? ORDER BY id",
                businessSessionId,
                cutoffCreatedAt,
            ).forEach { id -> reversalItems(db, id).takeIf { it.isNotEmpty() }?.let { summaries += -1 to TaxEngine.calculate(it) } }
        }
        if (SchemaMigration.tableExists(db, "manual_return_transactions")) {
            queryIds(
                db,
                "SELECT id FROM manual_return_transactions WHERE business_session_id = ? AND created_at <= ? ORDER BY id",
                businessSessionId,
                cutoffCreatedAt,
            ).forEach { id -> manualReturnItems(db, id).takeIf { it.isNotEmpty() }?.let { summaries += -1 to TaxEngine.calculate(it) } }
        }
        return SettlementTaxBreakdownPolicyV135.aggregate(summaries)
    }

    private fun queryIds(
        db: SQLiteDatabase,
        sql: String,
        businessSessionId: Long,
        cutoffCreatedAt: Long,
    ): List<Long> = db.rawQuery(
        sql,
        arrayOf(businessSessionId.toString(), cutoffCreatedAt.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    private fun saleItems(db: SQLiteDatabase, saleId: Long): List<CartItem> = db.rawQuery(
        """
        SELECT si.product_id, si.product_name, si.unit_price, si.tax_category,
               COALESCE(lts.tax_key, si.tax_category), COALESCE(lts.tax_label, si.tax_category),
               COALESCE(lts.rate_percent, CASE si.tax_category
                   WHEN 'INCLUDED_10' THEN 10 WHEN 'EXCLUDED_10' THEN 10
                   WHEN 'INCLUDED_8' THEN 8 WHEN 'EXCLUDED_8' THEN 8 ELSE 0 END),
               COALESCE(lts.tax_included, CASE WHEN si.tax_category IN ('INCLUDED_10','INCLUDED_8') THEN 1 ELSE 0 END),
               COALESCE(lts.taxable, CASE WHEN si.tax_category = 'NON_TAXABLE' THEN 0 ELSE 1 END),
               COALESCE(lts.reduced, CASE WHEN si.tax_category IN ('INCLUDED_8','EXCLUDED_8') THEN 1 ELSE 0 END),
               COALESCE(lts.tax_symbol, CASE si.tax_category
                   WHEN 'INCLUDED_10' THEN '内' WHEN 'EXCLUDED_10' THEN '外'
                   WHEN 'INCLUDED_8' THEN '内※' WHEN 'EXCLUDED_8' THEN '外※' ELSE '非' END),
               si.quantity, si.discount_amount, si.note
        FROM sale_items si
        LEFT JOIN line_tax_snapshots lts
          ON lts.scope = 'SALE' AND lts.owner_id = si.sale_id
         AND lts.line_no = (SELECT COUNT(*) FROM sale_items si2 WHERE si2.sale_id = si.sale_id AND si2.id <= si.id)
        WHERE si.sale_id = ? ORDER BY si.id
        """.trimIndent(),
        arrayOf(saleId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(snapshotItem(
                    cursor.getString(0), cursor.getString(1), cursor.getLong(2), TaxCategory.valueOf(cursor.getString(3)),
                    cursor.getString(4), cursor.getString(5), cursor.getInt(6), cursor.getInt(7) != 0,
                    cursor.getInt(8) != 0, cursor.getInt(9) != 0, cursor.getString(10), cursor.getInt(11),
                    cursor.getLong(12), cursor.getString(13),
                ))
            }
        }
    }

    private fun reversalItems(db: SQLiteDatabase, reversalId: Long): List<CartItem> = db.rawQuery(
        """
        SELECT product_id, product_name, unit_price, tax_category, tax_key, tax_label,
               tax_rate_percent, tax_included, taxable, reduced, tax_symbol, return_quantity, discount_amount
        FROM reversal_items WHERE reversal_id = ? ORDER BY id
        """.trimIndent(),
        arrayOf(reversalId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val legacy = TaxCategory.valueOf(cursor.getString(3))
                add(snapshotItem(
                    cursor.getString(0), cursor.getString(1), cursor.getLong(2), legacy,
                    cursor.getString(4).ifBlank { legacy.name }, cursor.getString(5).ifBlank { legacy.displayName },
                    cursor.getInt(6), cursor.getInt(7) != 0, cursor.getInt(8) != 0, cursor.getInt(9) != 0,
                    cursor.getString(10).ifBlank { legacy.symbol }, cursor.getInt(11), cursor.getLong(12), "",
                ))
            }
        }
    }

    private fun manualReturnItems(db: SQLiteDatabase, returnId: Long): List<CartItem> = db.rawQuery(
        """
        SELECT product_id, product_name, unit_price, tax_category, tax_key, tax_label,
               tax_rate_percent, tax_included, taxable, reduced, tax_symbol, quantity
        FROM manual_return_items WHERE manual_return_id = ? ORDER BY line_no, id
        """.trimIndent(),
        arrayOf(returnId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val legacy = TaxCategory.valueOf(cursor.getString(3))
                add(snapshotItem(
                    cursor.getString(0), cursor.getString(1), cursor.getLong(2), legacy,
                    cursor.getString(4).ifBlank { legacy.name }, cursor.getString(5).ifBlank { legacy.displayName },
                    cursor.getInt(6), cursor.getInt(7) != 0, cursor.getInt(8) != 0, cursor.getInt(9) != 0,
                    cursor.getString(10).ifBlank { legacy.symbol }, abs(cursor.getInt(11)), 0L, "",
                ))
            }
        }
    }

    private fun snapshotItem(
        productId: String,
        productName: String,
        unitPrice: Long,
        legacy: TaxCategory,
        taxKey: String,
        taxLabel: String,
        rate: Int,
        included: Boolean,
        taxable: Boolean,
        reduced: Boolean,
        symbol: String,
        quantity: Int,
        discount: Long,
        note: String,
    ): CartItem = CartItem(
        product = Product(
            id = productId,
            name = productName,
            unitPrice = unitPrice,
            taxCategory = legacy,
            displayOrder = 0,
            taxKey = taxKey,
            taxLabel = taxLabel,
            taxSymbol = symbol,
            taxRatePercent = rate,
            taxIncluded = included,
            taxable = taxable,
            reducedTax = reduced,
        ),
        quantity = quantity,
        unitPrice = unitPrice,
        discountAmount = discount.coerceAtLeast(0L),
        note = note,
    )

    private fun scalarLong(db: SQLiteDatabase, sql: String, args: Array<String>): Long =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun buildJson(
        core: Core,
        rep001: SettlementRep001TotalsV135,
        taxBreakdown: List<SettlementTaxRateBucketV135>,
        payments: List<PaymentTotal>,
        issuer: InvoiceIssuerProfile,
        actualCashEntered: Boolean,
    ): String = buildString {
        append('{')
        append("\"schemaVersion\":").append(SNAPSHOT_VERSION).append(',')
        append("\"documentType\":\"SETTLEMENT_SNAPSHOT\",")
        append("\"reportId\":").append(core.reportId).append(',')
        append("\"businessSessionId\":").append(core.businessSessionId).append(',')
        append("\"businessDate\":").append(jsonString(core.businessDate)).append(',')
        append("\"reportType\":").append(jsonString(core.reportType.name)).append(',')
        append("\"createdAt\":").append(core.createdAt).append(',')
        append("\"operatorName\":").append(jsonString(core.operatorName)).append(',')
        append("\"salesGross\":").append(core.salesGross).append(',')
        append("\"reversalGross\":").append(core.reversalGross).append(',')
        append("\"netSales\":").append(core.netSales).append(',')
        append("\"discountTotalYen\":").append(rep001.discountTotalYen).append(',')
        append("\"transactionCount\":").append(core.transactionCount).append(',')
        append("\"reversalCount\":").append(core.reversalCount).append(',')
        append("\"itemCount\":").append(rep001.itemCount).append(',')
        append("\"guestCount\":").append(rep001.guestCount).append(',')
        append("\"openingCash\":").append(core.openingCash).append(',')
        append("\"cashIn\":").append(core.cashIn).append(',')
        append("\"cashOut\":").append(core.cashOut).append(',')
        append("\"expectedCash\":").append(core.expectedCash).append(',')
        append("\"actualCash\":").append(core.actualCash).append(',')
        append("\"actualCashEntered\":").append(actualCashEntered).append(',')
        append("\"variance\":").append(core.variance).append(',')
        append("\"pendingPrints\":").append(core.pendingPrints).append(',')
        append("\"heldTickets\":").append(core.heldTickets).append(',')
        append("\"issuer\":{")
        append("\"storeName\":").append(jsonString(issuer.storeName)).append(',')
        append("\"address\":").append(jsonString(issuer.address)).append(',')
        append("\"phone\":").append(jsonString(issuer.phone)).append(',')
        append("\"registrationNumber\":").append(jsonString(issuer.registrationNumber)).append("},")
        append("\"paymentTotals\":[")
        payments.forEachIndexed { index, payment ->
            if (index > 0) append(',')
            append("{\"method\":").append(jsonString(payment.method))
                .append(",\"amount\":").append(payment.amount).append('}')
        }
        append("],\"taxRateTotals\":[")
        taxBreakdown.forEachIndexed { index, bucket ->
            if (index > 0) append(',')
            append("{\"ratePercent\":").append(bucket.ratePercent)
                .append(",\"taxable\":").append(bucket.taxable)
                .append(",\"targetAmountYen\":").append(bucket.targetAmountYen)
                .append(",\"taxAmountYen\":").append(bucket.taxAmountYen).append('}')
        }
        append("]}")
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

/** Read-only post-commit bridge used by preview/reprint/PDF rendering. */
internal object SettlementSnapshotRuntimeV135 {
    @Volatile private var applicationContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val helper = RegisterDatabase(appContext)
        try {
            SettlementSnapshotSchemaV135.ensure(helper.writableDatabase)
            applicationContext = appContext
        } finally {
            helper.close()
        }
    }

    fun document(reportId: Long): FrozenSettlementSnapshotV135? {
        if (reportId <= 0L) return null
        val context = applicationContext ?: return null
        val helper = RegisterDatabase(context)
        return try {
            SettlementSnapshotSchemaV135.load(helper.readableDatabase, reportId)
        } catch (_: Exception) {
            null
        } finally {
            helper.close()
        }
    }
}
