package jp.co.tenposinfo.register

import android.content.Context
import android.database.sqlite.SQLiteDatabase

internal data class SettlementTaxRateBucketV135(
    val ratePercent: Int,
    val taxable: Boolean,
    val targetAmountYen: Long,
    val taxAmountYen: Long,
) {
    val label: String get() = if (taxable) "${ratePercent}%対象" else "非課税"
}

internal object SettlementTaxBreakdownPolicyV135 {
    fun aggregate(
        signedSummaries: List<Pair<Int, TaxSummary>>,
    ): List<SettlementTaxRateBucketV135> {
        data class Acc(var target: Long = 0L, var tax: Long = 0L)
        val values = linkedMapOf<Pair<Boolean, Int>, Acc>()
        signedSummaries.forEach { (sign, summary) ->
            require(sign == 1 || sign == -1) { "sign must be +1 or -1" }
            summary.buckets.forEach { bucket ->
                val key = bucket.taxable to if (bucket.taxable) bucket.ratePercent else 0
                val acc = values.getOrPut(key) { Acc() }
                acc.target = Math.addExact(acc.target, Math.multiplyExact(bucket.grossAmount, sign.toLong()))
                acc.tax = Math.addExact(acc.tax, Math.multiplyExact(bucket.taxAmount, sign.toLong()))
            }
        }
        return values.map { (key, value) ->
            SettlementTaxRateBucketV135(
                ratePercent = key.second,
                taxable = key.first,
                targetAmountYen = value.target,
                taxAmountYen = value.tax,
            )
        }.filter { it.targetAmountYen != 0L || it.taxAmountYen != 0L }
            .sortedWith(compareBy<SettlementTaxRateBucketV135> { !it.taxable }.thenByDescending { it.ratePercent })
    }
}

/**
 * REP-001/Tax-rate report.
 *
 * Uses immutable tax snapshots stored with each finalized sale/reversal/manual return. Report history
 * is bounded by the settlement report's created_at, so later transactions and later tax-master changes
 * cannot alter a past X/Z tax breakdown. Returns are negative contributions, matching net daily sales.
 */
internal object SettlementTaxBreakdownRuntimeV135 {
    @Volatile private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun current(businessSessionId: Long): List<SettlementTaxRateBucketV135> =
        calculate(businessSessionId, Long.MAX_VALUE)

    fun document(
        businessSessionId: Long,
        createdAt: Long,
    ): List<SettlementTaxRateBucketV135> = calculate(businessSessionId, createdAt)

    private fun calculate(
        businessSessionId: Long,
        cutoffCreatedAt: Long,
    ): List<SettlementTaxRateBucketV135> {
        if (businessSessionId <= 0L) return emptyList()
        val context = applicationContext ?: return emptyList()
        val helper = RegisterDatabase(context)
        return try {
            val db = helper.readableDatabase
            LineTaxSnapshotStore.ensureSchema(db)
            val summaries = mutableListOf<Pair<Int, TaxSummary>>()
            queryIds(
                db,
                "SELECT id FROM sales WHERE business_session_id = ? AND created_at <= ? ORDER BY id",
                businessSessionId,
                cutoffCreatedAt,
            ).forEach { saleId ->
                val items = saleItems(db, saleId)
                if (items.isNotEmpty()) summaries += 1 to TaxEngine.calculate(items)
            }
            if (SchemaMigration.tableExists(db, "reversal_transactions")) {
                queryIds(
                    db,
                    "SELECT id FROM reversal_transactions WHERE business_session_id = ? AND created_at <= ? ORDER BY id",
                    businessSessionId,
                    cutoffCreatedAt,
                ).forEach { reversalId ->
                    val items = reversalItems(db, reversalId)
                    if (items.isNotEmpty()) summaries += -1 to TaxEngine.calculate(items)
                }
            }
            if (SchemaMigration.tableExists(db, "manual_return_transactions")) {
                queryIds(
                    db,
                    "SELECT id FROM manual_return_transactions WHERE business_session_id = ? AND created_at <= ? ORDER BY id",
                    businessSessionId,
                    cutoffCreatedAt,
                ).forEach { returnId ->
                    val items = manualReturnItems(db, returnId)
                    if (items.isNotEmpty()) summaries += -1 to TaxEngine.calculate(items)
                }
            }
            SettlementTaxBreakdownPolicyV135.aggregate(summaries)
        } finally {
            helper.close()
        }
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
               COALESCE(lts.tax_key, si.tax_category),
               COALESCE(lts.tax_label, si.tax_category),
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
          ON lts.scope = 'SALE'
         AND lts.owner_id = si.sale_id
         AND lts.line_no = (
             SELECT COUNT(*) FROM sale_items si2
             WHERE si2.sale_id = si.sale_id AND si2.id <= si.id
         )
        WHERE si.sale_id = ?
        ORDER BY si.id
        """.trimIndent(),
        arrayOf(saleId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    item(
                        productId = cursor.getString(0),
                        productName = cursor.getString(1),
                        unitPrice = cursor.getLong(2),
                        legacyCategory = TaxCategory.valueOf(cursor.getString(3)),
                        taxKey = cursor.getString(4),
                        taxLabel = cursor.getString(5),
                        ratePercent = cursor.getInt(6),
                        taxIncluded = cursor.getInt(7) != 0,
                        taxable = cursor.getInt(8) != 0,
                        reduced = cursor.getInt(9) != 0,
                        symbol = cursor.getString(10),
                        quantity = cursor.getInt(11),
                        discountAmount = cursor.getLong(12),
                        note = cursor.getString(13),
                    ),
                )
            }
        }
    }

    private fun reversalItems(db: SQLiteDatabase, reversalId: Long): List<CartItem> = db.rawQuery(
        """
        SELECT product_id, product_name, unit_price, tax_category, tax_key, tax_label,
               tax_rate_percent, tax_included, taxable, reduced, tax_symbol,
               return_quantity, discount_amount
        FROM reversal_items
        WHERE reversal_id = ?
        ORDER BY id
        """.trimIndent(),
        arrayOf(reversalId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val legacy = TaxCategory.valueOf(cursor.getString(3))
                add(
                    item(
                        productId = cursor.getString(0),
                        productName = cursor.getString(1),
                        unitPrice = cursor.getLong(2),
                        legacyCategory = legacy,
                        taxKey = cursor.getString(4).ifBlank { legacy.name },
                        taxLabel = cursor.getString(5).ifBlank { legacy.displayName },
                        ratePercent = cursor.getInt(6),
                        taxIncluded = cursor.getInt(7) != 0,
                        taxable = cursor.getInt(8) != 0,
                        reduced = cursor.getInt(9) != 0,
                        symbol = cursor.getString(10).ifBlank { legacy.symbol },
                        quantity = cursor.getInt(11),
                        discountAmount = cursor.getLong(12),
                        note = "",
                    ),
                )
            }
        }
    }

    private fun manualReturnItems(db: SQLiteDatabase, returnId: Long): List<CartItem> = db.rawQuery(
        """
        SELECT product_id, product_name, unit_price, tax_category, tax_key, tax_label,
               tax_rate_percent, tax_included, taxable, reduced, tax_symbol, quantity
        FROM manual_return_items
        WHERE manual_return_id = ?
        ORDER BY line_no, id
        """.trimIndent(),
        arrayOf(returnId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val legacy = TaxCategory.valueOf(cursor.getString(3))
                add(
                    item(
                        productId = cursor.getString(0),
                        productName = cursor.getString(1),
                        unitPrice = cursor.getLong(2),
                        legacyCategory = legacy,
                        taxKey = cursor.getString(4).ifBlank { legacy.name },
                        taxLabel = cursor.getString(5).ifBlank { legacy.displayName },
                        ratePercent = cursor.getInt(6),
                        taxIncluded = cursor.getInt(7) != 0,
                        taxable = cursor.getInt(8) != 0,
                        reduced = cursor.getInt(9) != 0,
                        symbol = cursor.getString(10).ifBlank { legacy.symbol },
                        quantity = kotlin.math.abs(cursor.getInt(11)),
                        discountAmount = 0L,
                        note = "",
                    ),
                )
            }
        }
    }

    private fun item(
        productId: String,
        productName: String,
        unitPrice: Long,
        legacyCategory: TaxCategory,
        taxKey: String,
        taxLabel: String,
        ratePercent: Int,
        taxIncluded: Boolean,
        taxable: Boolean,
        reduced: Boolean,
        symbol: String,
        quantity: Int,
        discountAmount: Long,
        note: String,
    ): CartItem = CartItem(
        product = Product(
            id = productId,
            name = productName,
            unitPrice = unitPrice,
            taxCategory = legacyCategory,
            displayOrder = 0,
            taxKey = taxKey,
            taxLabel = taxLabel,
            taxSymbol = symbol,
            taxRatePercent = ratePercent,
            taxIncluded = taxIncluded,
            taxable = taxable,
            reducedTax = reduced,
        ),
        quantity = quantity,
        unitPrice = unitPrice,
        discountAmount = discountAmount.coerceAtLeast(0L),
        note = note,
    )
}
