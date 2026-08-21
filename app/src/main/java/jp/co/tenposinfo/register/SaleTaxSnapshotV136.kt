package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger

/**
 * TAX-012: 同一税率の内税・外税を適格請求書へ集約する際の対価額基準。
 * 取引確定時の値を売上snapshotへ保存し、設定変更後の再印字で再評価しない。
 */
enum class InvoiceAggregationBasisV136 {
    TAX_INCLUDED,
    TAX_EXCLUDED,
}

data class InvoiceTaxBucketSnapshotV136(
    val ratePercent: Int,
    val taxable: Boolean,
    val aggregationBasis: InvoiceAggregationBasisV136,
    val taxableAmount: Long,
    val netAmount: Long,
    val taxAmount: Long,
    val grossAmount: Long,
    val includedGrossSourceAmount: Long,
    val excludedNetSourceAmount: Long,
    val roundingDelta: Long,
    val sourceTaxKeys: Set<String>,
)

data class SaleTaxSnapshotV136(
    val saleId: Long,
    val sameRateMixedModePolicy: MixedTaxPolicy,
    val taxRoundingMode: String,
    val taxRoundUnit: String,
    val invoiceAggregationBasis: InvoiceAggregationBasisV136,
    val buckets: List<InvoiceTaxBucketSnapshotV136>,
    val recordedAt: Long,
) {
    fun toTaxSummary(): TaxSummary = TaxSummary(
        buckets = buckets.map { bucket ->
            TaxBucket(
                ratePercent = bucket.ratePercent,
                taxable = bucket.taxable,
                sourceCategories = bucket.sourceTaxKeys.mapNotNullTo(linkedSetOf()) { key ->
                    runCatching { TaxCategory.valueOf(key) }.getOrNull()
                },
                netAmount = bucket.netAmount,
                taxAmount = bucket.taxAmount,
                grossAmount = bucket.grossAmount,
                sourceTaxKeys = bucket.sourceTaxKeys,
            )
        },
    )
}

/**
 * TAX-005/TAX-012 sale-time immutable tax snapshot.
 *
 * line_tax_snapshots が各明細の税区分・税率・内外税・軽減・記号を保持し、
 * 本テーブルは取引単位の端数規則・混在ポリシー・インボイス集計基準と、
 * 税率単位で確定済みの対象額/税額/由来額を保持する。
 */
object SaleTaxSnapshotStoreV136 {
    const val SALE_TABLE = "sale_tax_snapshot_v136"
    const val BUCKET_TABLE = "invoice_tax_summary_snapshot_v136"
    const val ROUNDING_MODE_FLOOR = "FLOOR"
    const val ROUND_UNIT_RATE_PER_INVOICE = "RATE_PER_INVOICE"

    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $SALE_TABLE (
                sale_id INTEGER PRIMARY KEY,
                same_rate_mixed_mode_policy_snapshot TEXT NOT NULL,
                tax_rounding_mode_snapshot TEXT NOT NULL,
                tax_round_unit_snapshot TEXT NOT NULL,
                invoice_aggregation_basis_snapshot TEXT NOT NULL,
                recorded_at INTEGER NOT NULL,
                FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $BUCKET_TABLE (
                sale_id INTEGER NOT NULL,
                sequence_no INTEGER NOT NULL,
                tax_rate INTEGER NOT NULL,
                taxable INTEGER NOT NULL,
                aggregation_basis TEXT NOT NULL,
                taxable_amount INTEGER NOT NULL,
                net_amount INTEGER NOT NULL,
                tax_amount INTEGER NOT NULL,
                gross_amount INTEGER NOT NULL,
                included_gross_source_amount INTEGER NOT NULL,
                excluded_net_source_amount INTEGER NOT NULL,
                rounding_delta INTEGER NOT NULL,
                source_tax_keys TEXT NOT NULL,
                PRIMARY KEY(sale_id, sequence_no),
                FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_invoice_tax_summary_snapshot_v136_rate " +
                "ON $BUCKET_TABLE(sale_id, tax_rate, sequence_no)",
        )
    }

    fun build(
        saleId: Long,
        items: List<CartItem>,
        summary: TaxSummary,
        settings: TaxInvoiceSettings,
        recordedAt: Long,
    ): SaleTaxSnapshotV136 {
        val basis = settings.invoiceAggregationBasis
        val buckets = summary.buckets.map { bucket ->
            val sourceItems = items.filter { item ->
                item.product.taxable == bucket.taxable &&
                    (!bucket.taxable || item.product.taxRatePercent == bucket.ratePercent)
            }
            val includedGross = if (bucket.taxable) {
                sourceItems.filter { it.product.taxIncluded }.sumOf { it.baseAmount }
            } else {
                0L
            }
            val excludedNet = if (bucket.taxable) {
                sourceItems.filter { !it.product.taxIncluded }.sumOf { it.baseAmount }
            } else {
                0L
            }
            val componentFloorTax = if (bucket.taxable) {
                includedTaxFloor(includedGross, bucket.ratePercent) +
                    excludedTaxFloor(excludedNet, bucket.ratePercent)
            } else {
                0L
            }
            val taxableAmount = when {
                !bucket.taxable -> bucket.grossAmount
                basis == InvoiceAggregationBasisV136.TAX_INCLUDED -> bucket.grossAmount
                else -> bucket.netAmount
            }
            InvoiceTaxBucketSnapshotV136(
                ratePercent = bucket.ratePercent,
                taxable = bucket.taxable,
                aggregationBasis = basis,
                taxableAmount = taxableAmount,
                netAmount = bucket.netAmount,
                taxAmount = bucket.taxAmount,
                grossAmount = bucket.grossAmount,
                includedGrossSourceAmount = includedGross,
                excludedNetSourceAmount = excludedNet,
                roundingDelta = bucket.taxAmount - componentFloorTax,
                sourceTaxKeys = bucket.sourceTaxKeys,
            )
        }
        return SaleTaxSnapshotV136(
            saleId = saleId,
            sameRateMixedModePolicy = settings.mixedTaxPolicy,
            taxRoundingMode = ROUNDING_MODE_FLOOR,
            taxRoundUnit = ROUND_UNIT_RATE_PER_INVOICE,
            invoiceAggregationBasis = basis,
            buckets = buckets,
            recordedAt = recordedAt,
        )
    }

    fun save(
        db: SQLiteDatabase,
        saleId: Long,
        items: List<CartItem>,
        summary: TaxSummary,
        settings: TaxInvoiceSettings,
        recordedAt: Long,
    ): SaleTaxSnapshotV136 {
        ensureSchema(db)
        val snapshot = build(saleId, items, summary, settings, recordedAt)
        db.delete(BUCKET_TABLE, "sale_id = ?", arrayOf(saleId.toString()))
        db.delete(SALE_TABLE, "sale_id = ?", arrayOf(saleId.toString()))
        db.insertOrThrow(
            SALE_TABLE,
            null,
            ContentValues().apply {
                put("sale_id", saleId)
                put("same_rate_mixed_mode_policy_snapshot", snapshot.sameRateMixedModePolicy.name)
                put("tax_rounding_mode_snapshot", snapshot.taxRoundingMode)
                put("tax_round_unit_snapshot", snapshot.taxRoundUnit)
                put("invoice_aggregation_basis_snapshot", snapshot.invoiceAggregationBasis.name)
                put("recorded_at", snapshot.recordedAt)
            },
        )
        snapshot.buckets.forEachIndexed { index, bucket ->
            db.insertOrThrow(
                BUCKET_TABLE,
                null,
                ContentValues().apply {
                    put("sale_id", saleId)
                    put("sequence_no", index + 1)
                    put("tax_rate", bucket.ratePercent)
                    put("taxable", if (bucket.taxable) 1 else 0)
                    put("aggregation_basis", bucket.aggregationBasis.name)
                    put("taxable_amount", bucket.taxableAmount)
                    put("net_amount", bucket.netAmount)
                    put("tax_amount", bucket.taxAmount)
                    put("gross_amount", bucket.grossAmount)
                    put("included_gross_source_amount", bucket.includedGrossSourceAmount)
                    put("excluded_net_source_amount", bucket.excludedNetSourceAmount)
                    put("rounding_delta", bucket.roundingDelta)
                    put("source_tax_keys", bucket.sourceTaxKeys.sorted().joinToString(","))
                },
            )
        }
        return snapshot
    }

    fun load(db: SQLiteDatabase, saleId: Long): SaleTaxSnapshotV136? {
        ensureSchema(db)
        val header = db.query(
            SALE_TABLE,
            arrayOf(
                "same_rate_mixed_mode_policy_snapshot",
                "tax_rounding_mode_snapshot",
                "tax_round_unit_snapshot",
                "invoice_aggregation_basis_snapshot",
                "recorded_at",
            ),
            "sale_id = ?",
            arrayOf(saleId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Header(
                mixedPolicy = runCatching { MixedTaxPolicy.valueOf(cursor.getString(0)) }
                    .getOrDefault(MixedTaxPolicy.BLOCK),
                roundingMode = cursor.getString(1),
                roundUnit = cursor.getString(2),
                basis = runCatching { InvoiceAggregationBasisV136.valueOf(cursor.getString(3)) }
                    .getOrDefault(InvoiceAggregationBasisV136.TAX_INCLUDED),
                recordedAt = cursor.getLong(4),
            )
        }
        val buckets = mutableListOf<InvoiceTaxBucketSnapshotV136>()
        db.query(
            BUCKET_TABLE,
            arrayOf(
                "tax_rate",
                "taxable",
                "aggregation_basis",
                "taxable_amount",
                "net_amount",
                "tax_amount",
                "gross_amount",
                "included_gross_source_amount",
                "excluded_net_source_amount",
                "rounding_delta",
                "source_tax_keys",
            ),
            "sale_id = ?",
            arrayOf(saleId.toString()),
            null,
            null,
            "sequence_no ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                buckets += InvoiceTaxBucketSnapshotV136(
                    ratePercent = cursor.getInt(0),
                    taxable = cursor.getInt(1) != 0,
                    aggregationBasis = runCatching { InvoiceAggregationBasisV136.valueOf(cursor.getString(2)) }
                        .getOrDefault(header.basis),
                    taxableAmount = cursor.getLong(3),
                    netAmount = cursor.getLong(4),
                    taxAmount = cursor.getLong(5),
                    grossAmount = cursor.getLong(6),
                    includedGrossSourceAmount = cursor.getLong(7),
                    excludedNetSourceAmount = cursor.getLong(8),
                    roundingDelta = cursor.getLong(9),
                    sourceTaxKeys = cursor.getString(10)
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toCollection(linkedSetOf()),
                )
            }
        }
        if (buckets.isEmpty()) return null
        return SaleTaxSnapshotV136(
            saleId = saleId,
            sameRateMixedModePolicy = header.mixedPolicy,
            taxRoundingMode = header.roundingMode,
            taxRoundUnit = header.roundUnit,
            invoiceAggregationBasis = header.basis,
            buckets = buckets,
            recordedAt = header.recordedAt,
        )
    }

    /**
     * つぐレジ＋へ渡すSALE journalも同じsnapshotから生成する。
     * recordSale() と同一DBトランザクション内で呼ぶため、未確定の中間値は外へ出ない。
     */
    fun enrichSaleJournal(db: SQLiteDatabase, saleId: Long) {
        val snapshot = load(db, saleId) ?: return
        val payload = toJournalPayload(snapshot)
        db.update(
            "sales_journal",
            ContentValues().apply { put("payload_json", payload) },
            "event_type = ? AND aggregate_id = ?",
            arrayOf(JournalEventType.SALE.name, saleId.toString()),
        )
    }

    fun toJournalPayload(snapshot: SaleTaxSnapshotV136): String {
        val summary = snapshot.toTaxSummary()
        return buildString {
            append('{')
            append("\"saleId\":").append(snapshot.saleId).append(',')
            append("\"totalAmount\":").append(summary.grossAmount).append(',')
            append("\"taxAmount\":").append(summary.taxAmount).append(',')
            append("\"sameRateMixedModePolicy\":\"").append(snapshot.sameRateMixedModePolicy.name).append("\",")
            append("\"taxRoundingMode\":\"").append(snapshot.taxRoundingMode).append("\",")
            append("\"taxRoundUnit\":\"").append(snapshot.taxRoundUnit).append("\",")
            append("\"invoiceAggregationBasis\":\"").append(snapshot.invoiceAggregationBasis.name).append("\",")
            append("\"invoiceTaxes\":[")
            snapshot.buckets.forEachIndexed { index, bucket ->
                if (index > 0) append(',')
                append('{')
                append("\"taxRate\":").append(bucket.ratePercent).append(',')
                append("\"taxable\":").append(bucket.taxable).append(',')
                append("\"aggregationBasis\":\"").append(bucket.aggregationBasis.name).append("\",")
                append("\"taxableAmount\":").append(bucket.taxableAmount).append(',')
                append("\"netAmount\":").append(bucket.netAmount).append(',')
                append("\"taxAmount\":").append(bucket.taxAmount).append(',')
                append("\"grossAmount\":").append(bucket.grossAmount).append(',')
                append("\"includedGrossSourceAmount\":").append(bucket.includedGrossSourceAmount).append(',')
                append("\"excludedNetSourceAmount\":").append(bucket.excludedNetSourceAmount).append(',')
                append("\"roundingDelta\":").append(bucket.roundingDelta).append(',')
                append("\"sourceTaxKeys\":[")
                bucket.sourceTaxKeys.sorted().forEachIndexed { keyIndex, key ->
                    if (keyIndex > 0) append(',')
                    append('"').append(key).append('"')
                }
                append("]}")
            }
            append("]}")
        }
    }

    private fun includedTaxFloor(gross: Long, rate: Int): Long =
        ratioFloor(gross, rate, 100 + rate)

    private fun excludedTaxFloor(net: Long, rate: Int): Long =
        ratioFloor(net, rate, 100)

    private fun ratioFloor(amount: Long, numerator: Int, denominator: Int): Long {
        if (amount == 0L || numerator == 0) return 0L
        require(amount >= 0L) { "税計算元額は0円以上です" }
        return BigInteger.valueOf(amount)
            .multiply(BigInteger.valueOf(numerator.toLong()))
            .divide(BigInteger.valueOf(denominator.toLong()))
            .longValueExact()
    }

    private data class Header(
        val mixedPolicy: MixedTaxPolicy,
        val roundingMode: String,
        val roundUnit: String,
        val basis: InvoiceAggregationBasisV136,
        val recordedAt: Long,
    )
}

/** TAX-012 CSV契約。DB/レシート/同期JSONと同じsnapshotを入力にする。 */
object SaleTaxSnapshotCsvV136 {
    val header = listOf(
        "sale_id",
        "tax_rate",
        "taxable",
        "aggregation_basis",
        "taxable_amount",
        "net_amount",
        "tax_amount",
        "gross_amount",
        "included_gross_source_amount",
        "excluded_net_source_amount",
        "rounding_delta",
        "source_tax_keys",
    )

    fun headerRow(): String = row(header)

    fun rows(snapshot: SaleTaxSnapshotV136): List<String> = snapshot.buckets.map { bucket ->
        row(
            listOf(
                snapshot.saleId.toString(),
                bucket.ratePercent.toString(),
                bucket.taxable.toString(),
                bucket.aggregationBasis.name,
                bucket.taxableAmount.toString(),
                bucket.netAmount.toString(),
                bucket.taxAmount.toString(),
                bucket.grossAmount.toString(),
                bucket.includedGrossSourceAmount.toString(),
                bucket.excludedNetSourceAmount.toString(),
                bucket.roundingDelta.toString(),
                bucket.sourceTaxKeys.sorted().joinToString("|"),
            ),
        )
    }

    private fun row(values: List<String>): String = values.joinToString(",") { value ->
        "\"${value.replace("\"", "\"\"")}\""
    }
}
