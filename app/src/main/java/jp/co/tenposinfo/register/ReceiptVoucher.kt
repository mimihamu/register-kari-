package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal data class ReceiptVoucherBatchRequest(
    val requestId: String,
    val saleId: Long,
    val unitAmount: Long,
    val copies: Int,
    val addressee: String,
    val purpose: String,
    val operatorName: String,
)

internal data class ReceiptVoucherPlan(
    val requestId: String,
    val saleId: Long,
    val unitAmount: Long,
    val copies: Int,
    val totalAmount: Long,
    val addressee: String,
    val purpose: String,
    val operatorName: String,
)

internal data class ReceiptVoucherAvailability(
    val saleTotal: Long,
    val allocatedAmount: Long,
) {
    val remainingAmount: Long get() = (saleTotal - allocatedAmount).coerceAtLeast(0L)
}

internal data class ReceiptVoucherRecord(
    val id: Long,
    val batchId: Long,
    val saleId: Long,
    val sequenceNo: Int,
    val sequenceCount: Int,
    val amount: Long,
    val addressee: String,
    val purpose: String,
    val operatorName: String,
    val createdAt: Long,
)

internal data class ReceiptVoucherIssueResult(
    val batchId: Long,
    val issuanceIds: List<Long>,
    val printJobIds: List<Long>,
    val totalAmount: Long,
    val remainingAmount: Long,
    val idempotentReplay: Boolean,
)

internal data class ReceiptVoucherReprintResult(
    val issuanceId: Long,
    val reprintEventId: Long,
    val printJobId: Long,
)

internal object ReceiptVoucherPolicy {
    /** v2.5 設定値の絶対範囲。実際の発行上限は店舗設定（初期100枚）を使う。 */
    const val MAX_COPIES = ReceiptVoucherBatchSettingsV135.MAX_BATCH_COPIES
    const val MAX_TEXT_LENGTH = 80

    fun plan(
        request: ReceiptVoucherBatchRequest,
        availability: ReceiptVoucherAvailability,
        maxCopies: Int = ReceiptVoucherBatchSettingsV135.DEFAULT_MAX_BATCH_COPIES,
    ): ReceiptVoucherPlan {
        val requestId = request.requestId.trim()
        require(runCatching { UUID.fromString(requestId) }.isSuccess) { "発行要求IDが不正です" }
        require(request.saleId > 0) { "売上番号が不正です" }
        require(availability.saleTotal > 0) { "売上金額が0円のため領収書を発行できません" }
        require(availability.allocatedAmount in 0..availability.saleTotal) { "領収済み金額が売上金額を超えています" }
        require(request.unitAmount > 0) { "領収金額は1円以上で入力してください" }
        require(maxCopies in 1..MAX_COPIES) { "一括発行上限の設定値が不正です" }
        require(request.copies in 1..maxCopies) { "発行枚数は1～${maxCopies}枚で指定してください" }
        val total = BigInteger.valueOf(request.unitAmount)
            .multiply(BigInteger.valueOf(request.copies.toLong()))
        require(total <= BigInteger.valueOf(Long.MAX_VALUE)) { "領収金額が大きすぎます" }
        val totalAmount = total.longValueExact()
        require(totalAmount <= availability.remainingAmount) {
            "発行合計${totalAmount}円が発行可能残額${availability.remainingAmount}円を超えています"
        }
        return ReceiptVoucherPlan(
            requestId = requestId,
            saleId = request.saleId,
            unitAmount = request.unitAmount,
            copies = request.copies,
            totalAmount = totalAmount,
            addressee = normalizeOptional(request.addressee),
            purpose = normalizeRequired(request.purpose, "但し書き"),
            operatorName = normalizeRequired(request.operatorName, "担当者"),
        )
    }

    fun normalizeRequired(raw: String, label: String): String {
        val normalized = normalizeOptional(raw)
        require(normalized.isNotBlank()) { "${label}を入力してください" }
        return normalized
    }

    fun normalizeOptional(raw: String): String = raw
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .trim()
        .take(MAX_TEXT_LENGTH)

    fun addresseeForPrint(value: String): String = when {
        value.isBlank() -> "________________ 様"
        value.endsWith("様") || value.endsWith("御中") -> value
        else -> "$value 様"
    }
}

internal data class ReceiptVoucherDocumentData(
    val issuanceId: Long,
    val saleId: Long,
    val sequenceNo: Int,
    val sequenceCount: Int,
    val amount: Long,
    val addressee: String,
    val purpose: String,
    val operatorName: String,
    val issuedAt: Long,
    val issuer: InvoiceIssuerProfile,
    val batchId: Long = 0L,
    val supplementary: Boolean = sequenceCount > 1,
    val reprintedAt: Long? = null,
    val reprintedBy: String? = null,
)

internal object ReceiptVoucherRenderer {
    const val NOT_QUALIFIED_LABEL = "適格簡易請求書ではありません"
    private val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun render(data: ReceiptVoucherDocumentData, paper: ReceiptPaper): String {
        val width = paper.charsPerLine
        val lines = mutableListOf<String>()
        lines += center(data.issuer.storeName, width)
        if (data.issuer.address.isNotBlank()) lines += center(data.issuer.address, width)
        if (data.issuer.phone.isNotBlank()) lines += center(data.issuer.phone, width)
        lines += center("【領収書】", width)
        if (data.reprintedAt != null) lines += center("【再発行】", width)
        if (data.supplementary) lines += center("【$NOT_QUALIFIED_LABEL】", width)
        lines += separator(width, '=')
        lines += fit("領収書No.R${data.issuanceId} / 元売上No.${data.saleId}", width)
        if (data.batchId > 0L) lines += fit("発行グループ RG-${data.batchId}", width)
        if (data.sequenceCount > 1) lines += fit("枝番 ${data.sequenceNo}/${data.sequenceCount}", width)
        lines += "発行 ${formatDate(data.issuedAt)}"
        if (data.reprintedAt != null) lines += "再発行 ${formatDate(data.reprintedAt)}"
        data.reprintedBy?.takeIf(String::isNotBlank)?.let { lines += fit("再発行担当 $it", width) }
        lines += separator(width, '-')
        lines += center(ReceiptVoucherPolicy.addresseeForPrint(data.addressee), width)
        lines += separator(width, '-')
        lines += amountLine("領収金額", yen(data.amount), width)
        lines += fit("但し ${data.purpose} として", width)
        lines += center("上記正に領収いたしました", width)
        lines += separator(width, '-')
        if (data.supplementary) {
            lines += fit(NOT_QUALIFIED_LABEL, width)
            lines += fit("税率別対価額・税額は元売上の監査情報を参照してください", width)
        } else {
            lines += fit("税率別の取引内容・消費税額等は元売上レシートを参照してください", width)
        }
        lines += fit("元売上レシート No.${data.saleId} と関連する領収書です", width)
        lines += fit("発行担当 ${data.operatorName}", width)
        if (!data.supplementary && data.issuer.registrationNumber.isNotBlank()) {
            lines += fit("登録番号 ${data.issuer.registrationNumber}", width)
        }
        return lines.joinToString("\n")
    }

    private fun formatDate(value: Long): String = Instant.ofEpochMilli(value)
        .atZone(ZoneId.systemDefault())
        .format(formatter)

    private fun yen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)
    private fun separator(width: Int, char: Char): String = char.toString().repeat(width)

    private fun amountLine(label: String, amount: String, width: Int): String {
        val amountWidth = displayWidth(amount)
        val labelWidth = (width - amountWidth - 1).coerceAtLeast(1)
        return padRight(fit(label, labelWidth), labelWidth) + " " + amount
    }

    private fun center(value: String, width: Int): String {
        val fitted = fit(value, width)
        val left = ((width - displayWidth(fitted)) / 2).coerceAtLeast(0)
        return " ".repeat(left) + fitted
    }

    private fun fit(value: String, width: Int): String {
        val result = StringBuilder()
        var used = 0
        value.forEach { char ->
            val charWidth = if (char.code <= 0xFF) 1 else 2
            if (used + charWidth <= width) {
                result.append(char)
                used += charWidth
            }
        }
        return result.toString()
    }

    private fun padRight(value: String, width: Int): String =
        value + " ".repeat((width - displayWidth(value)).coerceAtLeast(0))

    private fun displayWidth(value: String): Int = value.sumOf { if (it.code <= 0xFF) 1 else 2 }
}

/**
 * 領収書の金額配賦・発行履歴・印刷ジョブを追記専用で保存する。
 * 元売上、通常レシート、返品、同期データは更新・削除しない。
 */
internal class ReceiptVoucherStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val baseDatabase = RegisterDatabase(appContext)
    private val db = baseDatabase.writableDatabase
    private val batchSettings = ReceiptVoucherBatchSettingsV135(appContext)

    init {
        ensureSchema()
    }

    override fun close() = baseDatabase.close()

    fun maxBatchCopies(): Int = batchSettings.maxBatchReceiptCopies()

    fun availability(saleId: Long): ReceiptVoucherAvailability {
        val sale = baseDatabase.loadSaleDetail(saleId) ?: error("売上No.${saleId}が見つかりません")
        val allocated = longQuery(
            "SELECT COALESCE(SUM(amount), 0) FROM receipt_voucher_issuances WHERE sale_id = ?",
            arrayOf(saleId.toString()),
        )
        return ReceiptVoucherAvailability(
            saleTotal = sale.summary.totalAmount,
            allocatedAmount = allocated,
        )
    }

    fun issueBatch(request: ReceiptVoucherBatchRequest): ReceiptVoucherIssueResult {
        existingBatchResult(request.requestId.trim())?.let { return it.copy(idempotentReplay = true) }
        val sale = baseDatabase.loadSaleDetail(request.saleId) ?: error("売上No.${request.saleId}が見つかりません")
        val now = System.currentTimeMillis()
        val paperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext)
        val issuer = TaxInvoiceSettingsRegistry.current().issuer
        val documentPrintSetting = DocumentPrintSettingsStoreV136(appContext).load(
            DocumentPrintKindV136.RECEIPT_VOUCHER,
        )
        var result: ReceiptVoucherIssueResult? = null

        db.beginTransaction()
        try {
            val existingInsideTransaction = existingBatchResult(request.requestId.trim())
            if (existingInsideTransaction != null) {
                result = existingInsideTransaction.copy(idempotentReplay = true)
            } else {
                val allocated = longQuery(
                    "SELECT COALESCE(SUM(amount), 0) FROM receipt_voucher_issuances WHERE sale_id = ?",
                    arrayOf(request.saleId.toString()),
                )
                val plan = ReceiptVoucherPolicy.plan(
                    request,
                    ReceiptVoucherAvailability(sale.summary.totalAmount, allocated),
                    maxCopies = batchSettings.maxBatchReceiptCopies(),
                )
                val batchId = db.insertOrThrow(
                    "receipt_voucher_batches",
                    null,
                    ContentValues().apply {
                        put("request_id", plan.requestId)
                        put("sale_id", plan.saleId)
                        put("unit_amount", plan.unitAmount)
                        put("copy_count", plan.copies)
                        put("total_amount", plan.totalAmount)
                        put("addressee", plan.addressee)
                        put("purpose", plan.purpose)
                        put("operator_name", plan.operatorName)
                        put("status", "DRAFT")
                        putNull("committed_at")
                        put("created_at", now)
                    },
                )
                val issuanceIds = mutableListOf<Long>()
                val printJobIds = mutableListOf<Long>()
                for (zeroIndex in 0 until plan.copies) {
                    val sequence = zeroIndex + 1
                    val issuanceId = db.insertOrThrow(
                        "receipt_voucher_issuances",
                        null,
                        ContentValues().apply {
                            put("batch_id", batchId)
                            put("sale_id", plan.saleId)
                            put("sequence_no", sequence)
                            put("sequence_count", plan.copies)
                            put("amount", plan.unitAmount)
                            put("addressee", plan.addressee)
                            put("purpose", plan.purpose)
                            put("operator_name", plan.operatorName)
                            put("created_at", now)
                        },
                    )
                    val payload = ReceiptVoucherRenderer.render(
                        ReceiptVoucherDocumentData(
                            issuanceId = issuanceId,
                            saleId = plan.saleId,
                            sequenceNo = sequence,
                            sequenceCount = plan.copies,
                            amount = plan.unitAmount,
                            addressee = plan.addressee,
                            purpose = plan.purpose,
                            operatorName = plan.operatorName,
                            issuedAt = now,
                            issuer = issuer,
                            batchId = batchId,
                            supplementary = plan.copies > 1,
                        ),
                        ReceiptPaper.fromWidth(paperWidthMm),
                    )
                    issuanceIds += issuanceId
                    if (documentPrintSetting.autoPrintEnabled) {
                        val decoratedPayload = DocumentPrintSettingsPolicyV136.decorateText(
                            payload,
                            documentPrintSetting,
                        )
                        val copyCount = DocumentPrintSettingsPolicyV136.normalizeCopies(documentPrintSetting.copies)
                        val offsetBase = printJobIds.size
                        kotlin.repeat(copyCount) { copyIndex ->
                            printJobIds += insertDocumentPrintJob(
                                issuanceId,
                                paperWidthMm,
                                decoratedPayload,
                                now + offsetBase + copyIndex,
                            )
                        }
                    }
                }
                db.update(
                    "receipt_voucher_batches",
                    ContentValues().apply {
                        put("status", "COMMITTED")
                        put("committed_at", now)
                    },
                    "id = ? AND status = ?",
                    arrayOf(batchId.toString(), "DRAFT"),
                ).also { updated ->
                    check(updated == 1) { "領収書発行グループ RG-$batchId を確定できませんでした" }
                }
                db.insertOrThrow(
                    "operation_audit",
                    null,
                    ContentValues().apply {
                        put("event_type", "RECEIPT_VOUCHER_BATCH_COMMIT")
                        put("reference_id", batchId)
                        put(
                            "detail",
                            "sale=${plan.saleId} / ${plan.unitAmount}円×${plan.copies}枚 / total=${plan.totalAmount}円",
                        )
                        put("operator_name", plan.operatorName)
                        put("created_at", now)
                    },
                )
                result = ReceiptVoucherIssueResult(
                    batchId = batchId,
                    issuanceIds = issuanceIds,
                    printJobIds = printJobIds,
                    totalAmount = plan.totalAmount,
                    remainingAmount = sale.summary.totalAmount - allocated - plan.totalAmount,
                    idempotentReplay = false,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val completed = result ?: error("領収書発行を確定できませんでした")
        if (!completed.idempotentReplay && completed.printJobIds.isNotEmpty()) {
            AutomaticPrintScheduler.enqueueNow(appContext)
        }
        return completed
    }

    fun reprint(issuanceId: Long, operatorName: String): ReceiptVoucherReprintResult {
        val record = loadIssuance(issuanceId) ?: error("領収書No.R${issuanceId}が見つかりません")
        val actor = ReceiptVoucherPolicy.normalizeRequired(operatorName, "再発行担当者")
        val now = System.currentTimeMillis()
        val paperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext)
        val documentPrintSetting = DocumentPrintSettingsStoreV136(appContext).load(
            DocumentPrintKindV136.RECEIPT_VOUCHER,
        )
        val payload = ReceiptVoucherRenderer.render(
            ReceiptVoucherDocumentData(
                issuanceId = record.id,
                saleId = record.saleId,
                sequenceNo = record.sequenceNo,
                sequenceCount = record.sequenceCount,
                amount = record.amount,
                addressee = record.addressee,
                purpose = record.purpose,
                operatorName = record.operatorName,
                issuedAt = record.createdAt,
                issuer = TaxInvoiceSettingsRegistry.current().issuer,
                batchId = record.batchId,
                supplementary = record.sequenceCount > 1,
                reprintedAt = now,
                reprintedBy = actor,
            ),
            ReceiptPaper.fromWidth(paperWidthMm),
        )
        val decoratedPayload = DocumentPrintSettingsPolicyV136.decorateText(payload, documentPrintSetting)
        var result: ReceiptVoucherReprintResult? = null
        db.beginTransaction()
        try {
            val printJobIds = buildList {
                kotlin.repeat(DocumentPrintSettingsPolicyV136.normalizeCopies(documentPrintSetting.copies)) { copyIndex ->
                    add(insertDocumentPrintJob(record.id, paperWidthMm, decoratedPayload, now + copyIndex))
                }
            }
            val printJobId = printJobIds.first()
            val eventId = db.insertOrThrow(
                "receipt_voucher_reprints",
                null,
                ContentValues().apply {
                    put("issuance_id", record.id)
                    put("operator_name", actor)
                    put("print_job_id", printJobId)
                    put("created_at", now)
                },
            )
            result = ReceiptVoucherReprintResult(record.id, eventId, printJobId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AutomaticPrintScheduler.enqueueNow(appContext)
        return result ?: error("領収書再発行を確定できませんでした")
    }

    fun listForSale(saleId: Long): List<ReceiptVoucherRecord> = db.query(
        "receipt_voucher_issuances",
        ISSUANCE_COLUMNS,
        "sale_id = ?",
        arrayOf(saleId.toString()),
        null,
        null,
        "created_at ASC, id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toReceiptVoucherRecord())
        }
    }

    fun loadIssuance(id: Long): ReceiptVoucherRecord? = db.query(
        "receipt_voucher_issuances",
        ISSUANCE_COLUMNS,
        "id = ?",
        arrayOf(id.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.toReceiptVoucherRecord() }

    private fun android.database.Cursor.toReceiptVoucherRecord() = ReceiptVoucherRecord(
        id = getLong(0),
        batchId = getLong(1),
        saleId = getLong(2),
        sequenceNo = getInt(3),
        sequenceCount = getInt(4),
        amount = getLong(5),
        addressee = getString(6),
        purpose = getString(7),
        operatorName = getString(8),
        createdAt = getLong(9),
    )

    private fun existingBatchResult(requestId: String): ReceiptVoucherIssueResult? {
        if (requestId.isBlank()) return null
        val batch = db.rawQuery(
            "SELECT id, total_amount, sale_id FROM receipt_voucher_batches WHERE request_id = ? LIMIT 1",
            arrayOf(requestId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
        }
        val ids = db.rawQuery(
            "SELECT id FROM receipt_voucher_issuances WHERE batch_id = ? ORDER BY sequence_no ASC, id ASC",
            arrayOf(batch.first.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        val remaining = availability(batch.third).remainingAmount
        return ReceiptVoucherIssueResult(
            batchId = batch.first,
            issuanceIds = ids,
            printJobIds = emptyList(),
            totalAmount = batch.second,
            remainingAmount = remaining,
            idempotentReplay = true,
        )
    }

    private fun insertDocumentPrintJob(
        issuanceId: Long,
        paperWidthMm: Int,
        payload: String,
        now: Long,
    ): Long = db.insertOrThrow(
        "document_print_jobs",
        null,
        ContentValues().apply {
            put("document_type", OperationDocumentType.RECEIPT_VOUCHER.name)
            put("reference_id", issuanceId)
            put("paper_width_mm", if (paperWidthMm >= 80) 80 else 58)
            put("status", PrintJobStatus.PENDING.name)
            put("attempt_count", 0)
            putNull("last_error")
            put("payload_text", payload)
            put("created_at", now)
            put("updated_at", now)
        },
    )

    private fun longQuery(sql: String, args: Array<String>): Long = db.rawQuery(sql, args).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }

    private fun ensureSchema() {
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
            CREATE TABLE IF NOT EXISTS receipt_voucher_batches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id TEXT NOT NULL UNIQUE,
                sale_id INTEGER NOT NULL,
                unit_amount INTEGER NOT NULL,
                copy_count INTEGER NOT NULL,
                total_amount INTEGER NOT NULL,
                addressee TEXT NOT NULL,
                purpose TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'COMMITTED',
                committed_at INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS receipt_voucher_issuances (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                batch_id INTEGER NOT NULL,
                sale_id INTEGER NOT NULL,
                sequence_no INTEGER NOT NULL,
                sequence_count INTEGER NOT NULL,
                amount INTEGER NOT NULL,
                addressee TEXT NOT NULL,
                purpose TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(batch_id, sequence_no)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS receipt_voucher_reprints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                issuance_id INTEGER NOT NULL,
                operator_name TEXT NOT NULL,
                print_job_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        ensureBatchLifecycleSchema()
        OperationAuditSchemaV136.ensure(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipt_voucher_sale ON receipt_voucher_issuances(sale_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipt_voucher_reprints ON receipt_voucher_reprints(issuance_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_jobs_status ON document_print_jobs(status, created_at)")
    }

    private fun ensureBatchLifecycleSchema() {
        val columns = db.rawQuery("PRAGMA table_info(receipt_voucher_batches)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        if ("status" !in columns) {
            db.execSQL("ALTER TABLE receipt_voucher_batches ADD COLUMN status TEXT NOT NULL DEFAULT 'COMMITTED'")
        }
        if ("committed_at" !in columns) {
            db.execSQL("ALTER TABLE receipt_voucher_batches ADD COLUMN committed_at INTEGER")
        }
        db.execSQL(
            "UPDATE receipt_voucher_batches SET status = 'COMMITTED' WHERE status IS NULL OR status = ''",
        )
        db.execSQL(
            "UPDATE receipt_voucher_batches SET committed_at = created_at WHERE status = 'COMMITTED' AND committed_at IS NULL",
        )
    }

    private companion object {
        val ISSUANCE_COLUMNS = arrayOf(
            "id",
            "batch_id",
            "sale_id",
            "sequence_no",
            "sequence_count",
            "amount",
            "addressee",
            "purpose",
            "operator_name",
            "created_at",
        )
    }
}
