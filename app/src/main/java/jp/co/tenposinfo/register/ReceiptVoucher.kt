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
    const val MAX_COPIES = 200
    const val MAX_TEXT_LENGTH = 80

    fun plan(
        request: ReceiptVoucherBatchRequest,
        availability: ReceiptVoucherAvailability,
    ): ReceiptVoucherPlan {
        val requestId = request.requestId.trim()
        require(runCatching { UUID.fromString(requestId) }.isSuccess) { "発行要求IDが不正です" }
        require(request.saleId > 0) { "売上番号が不正です" }
        require(availability.saleTotal > 0) { "売上金額が0円のため領収書を発行できません" }
        require(availability.allocatedAmount in 0..availability.saleTotal) { "領収済み金額が売上金額を超えています" }
        require(request.unitAmount > 0) { "領収金額は1円以上で入力してください" }
        require(request.copies in 1..MAX_COPIES) { "発行枚数は1～${MAX_COPIES}枚で指定してください" }
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
            addressee = normalizeRequired(request.addressee, "宛名"),
            purpose = normalizeRequired(request.purpose, "但し書き"),
            operatorName = normalizeRequired(request.operatorName, "担当者"),
        )
    }

    fun normalizeRequired(raw: String, label: String): String {
        val normalized = raw
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
            .take(MAX_TEXT_LENGTH)
        require(normalized.isNotBlank()) { "$labelを入力してください" }
        return normalized
    }

    fun addresseeForPrint(value: String): String = when {
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
    val reprintedAt: Long? = null,
    val reprintedBy: String? = null,
)

internal object ReceiptVoucherRenderer {
    private val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun render(data: ReceiptVoucherDocumentData, paper: ReceiptPaper): String {
        val width = paper.charsPerLine
        val lines = mutableListOf<String>()
        lines += center(data.issuer.storeName, width)
        if (data.issuer.address.isNotBlank()) lines += center(data.issuer.address, width)
        if (data.issuer.phone.isNotBlank()) lines += center(data.issuer.phone, width)
        lines += center("【領収書】", width)
        if (data.reprintedAt != null) lines += center("【再発行】", width)
        lines += separator(width, '=')
        lines += fit("領収書No.R${data.issuanceId} / 元売上No.${data.saleId}", width)
        if (data.sequenceCount > 1) {
            lines += fit("一括発行 ${data.sequenceNo}/${data.sequenceCount}", width)
        }
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
        lines += fit("元売上レシート No.${data.saleId} と関連する領収書です", width)
        lines += fit("税率別の取引内容・消費税額等は元売上レシートを参照してください", width)
        lines += fit("発行担当 ${data.operatorName}", width)
        if (data.issuer.registrationNumber.isNotBlank()) {
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
            if (used + charWidth > width) return@forEach
            result.append(char)
            used += charWidth
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

    init {
        ensureSchema()
    }

    override fun close() = baseDatabase.close()

    fun availability(saleId: Long): ReceiptVoucherAvailability {
        val sale = baseDatabase.loadSaleDetail(saleId) ?: error("売上No.$saleIdが見つかりません")
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
        var result: ReceiptVoucherIssueResult? = null

        db.beginTransaction()
        try {
            existingBatchResult(request.requestId.trim())?.let {
                result = it.copy(idempotentReplay = true)
                db.setTransactionSuccessful()
                return@try
            }
            val allocated = longQuery(
                "SELECT COALESCE(SUM(amount), 0) FROM receipt_voucher_issuances WHERE sale_id = ?",
                arrayOf(request.saleId.toString()),
            )
            val plan = ReceiptVoucherPolicy.plan(
                request,
                ReceiptVoucherAvailability(sale.summary.totalAmount, allocated),
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
                    put("created_at", now)
                },
            )
            val issuanceIds = mutableListOf<Long>()
            val printJobIds = mutableListOf<Long>()
            repeat(plan.copies) { zeroIndex ->
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
                    ),
                    ReceiptPaper.fromWidth(paperWidthMm),
                )
                val printJobId = insertDocumentPrintJob(issuanceId, paperWidthMm, payload, now)
                issuanceIds += issuanceId
                printJobIds += printJobId
            }
            result = ReceiptVoucherIssueResult(
                batchId = batchId,
                issuanceIds = issuanceIds,
                printJobIds = printJobIds,
                totalAmount = plan.totalAmount,
                remainingAmount = sale.summary.totalAmount - allocated - plan.totalAmount,
                idempotentReplay = false,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val completed = result ?: error("領収書発行を確定できませんでした")
        if (!completed.idempotentReplay) AutomaticPrintScheduler.enqueueNow(appContext)
        return completed
    }

    fun reprint(issuanceId: Long, operatorName: String): ReceiptVoucherReprintResult {
        val record = loadIssuance(issuanceId) ?: error("領収書No.R$issuanceIdが見つかりません")
        val actor = ReceiptVoucherPolicy.normalizeRequired(operatorName, "再発行担当者")
        val now = System.currentTimeMillis()
        val paperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext)
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
                reprintedAt = now,
                reprintedBy = actor,
            ),
            ReceiptPaper.fromWidth(paperWidthMm),
        )
        var result: ReceiptVoucherReprintResult? = null
        db.beginTransaction()
        try {
            val printJobId = insertDocumentPrintJob(record.id, paperWidthMm, payload, now)
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
            while (cursor.moveToNext()) add(
                ReceiptVoucherRecord(
                    id = cursor.getLong(0),
                    batchId = cursor.getLong(1),
                    saleId = cursor.getLong(2),
                    sequenceNo = cursor.getInt(3),
                    sequenceCount = cursor.getInt(4),
                    amount = cursor.getLong(5),
                    addressee = cursor.getString(6),
                    purpose = cursor.getString(7),
                    operatorName = cursor.getString(8),
                    createdAt = cursor.getLong(9),
                ),
            )
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
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ReceiptVoucherRecord(
            id = cursor.getLong(0),
            batchId = cursor.getLong(1),
            saleId = cursor.getLong(2),
            sequenceNo = cursor.getInt(3),
            sequenceCount = cursor.getInt(4),
            amount = cursor.getLong(5),
            addressee = cursor.getString(6),
            purpose = cursor.getString(7),
            operatorName = cursor.getString(8),
            createdAt = cursor.getLong(9),
        )
    }

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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipt_voucher_sale ON receipt_voucher_issuances(sale_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipt_voucher_reprints ON receipt_voucher_reprints(issuance_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_jobs_status ON document_print_jobs(status, created_at)")
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
