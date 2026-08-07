from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 occurrence, found {count}")
    return text.replace(old, new, 1)


receipt = r'''package jp.co.tenposinfo.register

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
'''
write("app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt", receipt)

# Integrate the new document type into the existing document and unified print queues.
advanced_path = "app/src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt"
advanced = read(advanced_path)
advanced = replace_once(
    advanced,
    'enum class OperationDocumentType(val displayName: String) {\n    REVERSAL_RECEIPT("返品・取消レシート"),\n    SETTLEMENT_REPORT("点検・精算票"),\n}',
    'enum class OperationDocumentType(val displayName: String) {\n    REVERSAL_RECEIPT("返品・取消レシート"),\n    SETTLEMENT_REPORT("点検・精算票"),\n    RECEIPT_VOUCHER("領収書"),\n}',
    "OperationDocumentType receipt voucher",
)
write(advanced_path, advanced)

unified_path = "app/src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt"
unified = read(unified_path)
unified = replace_once(
    unified,
    'enum class UnifiedPrintJobType(val displayName: String) {\n    SALE_RECEIPT("売上レシート"),\n    REVERSAL_RECEIPT("返品・取消レシート"),\n    SETTLEMENT_REPORT("点検・精算票"),\n}',
    'enum class UnifiedPrintJobType(val displayName: String) {\n    SALE_RECEIPT("売上レシート"),\n    REVERSAL_RECEIPT("返品・取消レシート"),\n    SETTLEMENT_REPORT("点検・精算票"),\n    RECEIPT_VOUCHER("領収書"),\n}',
    "UnifiedPrintJobType receipt voucher",
)
unified = replace_once(
    unified,
    'enum class UnifiedPrintTypeFilter(val displayName: String) {\n    ALL("全種別"),\n    SALE("売上"),\n    REVERSAL("返品・取消"),\n    SETTLEMENT("点検・精算"),\n}',
    'enum class UnifiedPrintTypeFilter(val displayName: String) {\n    ALL("全種別"),\n    SALE("売上"),\n    REVERSAL("返品・取消"),\n    SETTLEMENT("点検・精算"),\n    RECEIPT("領収書"),\n}',
    "UnifiedPrintTypeFilter receipt",
)
unified = replace_once(
    unified,
    '            UnifiedPrintTypeFilter.SETTLEMENT -> job.type == UnifiedPrintJobType.SETTLEMENT_REPORT\n',
    '            UnifiedPrintTypeFilter.SETTLEMENT -> job.type == UnifiedPrintJobType.SETTLEMENT_REPORT\n            UnifiedPrintTypeFilter.RECEIPT -> job.type == UnifiedPrintJobType.RECEIPT_VOUCHER\n',
    "filter receipt branch",
)
unified = replace_once(
    unified,
    '                    OperationDocumentType.REVERSAL_RECEIPT -> UnifiedPrintJobType.REVERSAL_RECEIPT\n                    OperationDocumentType.SETTLEMENT_REPORT -> UnifiedPrintJobType.SETTLEMENT_REPORT\n',
    '                    OperationDocumentType.REVERSAL_RECEIPT -> UnifiedPrintJobType.REVERSAL_RECEIPT\n                    OperationDocumentType.SETTLEMENT_REPORT -> UnifiedPrintJobType.SETTLEMENT_REPORT\n                    OperationDocumentType.RECEIPT_VOUCHER -> UnifiedPrintJobType.RECEIPT_VOUCHER\n',
    "document mapping receipt",
)
# All document-backed actions use AdvancedOperationsStore regardless of document subtype.
unified = unified.replace(
    '            UnifiedPrintJobType.SETTLEMENT_REPORT,\n            -> documentStore.',
    '            UnifiedPrintJobType.SETTLEMENT_REPORT,\n            UnifiedPrintJobType.RECEIPT_VOUCHER,\n            -> documentStore.',
)
write(unified_path, unified)

# Tests for the pure allocation and rendering contract plus source wiring.
test = r'''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class V058ReceiptVoucherFoundationTest {
    @Test
    fun banquetBatchUsesUnitAmountTimesCopiesWithoutExceedingSale() {
        val request = ReceiptVoucherBatchRequest(
            requestId = UUID.randomUUID().toString(),
            saleId = 10,
            unitAmount = 4_000,
            copies = 30,
            addressee = "株式会社テスト",
            purpose = "ご飲食代",
            operatorName = "担当A",
        )
        val plan = ReceiptVoucherPolicy.plan(
            request,
            ReceiptVoucherAvailability(saleTotal = 120_000, allocatedAmount = 0),
        )
        assertEquals(120_000L, plan.totalAmount)
        assertEquals(30, plan.copies)
    }

    @Test(expected = IllegalArgumentException::class)
    fun partialReceiptsCannotExceedRemainingSaleAmount() {
        ReceiptVoucherPolicy.plan(
            ReceiptVoucherBatchRequest(
                requestId = UUID.randomUUID().toString(),
                saleId = 1,
                unitAmount = 4_000,
                copies = 2,
                addressee = "テスト",
                purpose = "飲食代",
                operatorName = "担当",
            ),
            ReceiptVoucherAvailability(saleTotal = 10_000, allocatedAmount = 3_000),
        )
    }

    @Test
    fun rendererReferencesOriginalReceiptInsteadOfInventingPartialTaxAllocation() {
        val text = ReceiptVoucherRenderer.render(
            ReceiptVoucherDocumentData(
                issuanceId = 12,
                saleId = 99,
                sequenceNo = 1,
                sequenceCount = 30,
                amount = 4_000,
                addressee = "株式会社テスト",
                purpose = "ご飲食代",
                operatorName = "担当A",
                issuedAt = 1_700_000_000_000,
                issuer = InvoiceIssuerProfile(
                    storeName = "つぐ食堂",
                    registrationNumber = "T1234567890123",
                ),
            ),
            ReceiptPaper.MM80,
        )
        assertTrue(text.contains("【領収書】"))
        assertTrue(text.contains("元売上No.99"))
        assertTrue(text.contains("一括発行 1/30"))
        assertTrue(text.contains("税率別の取引内容・消費税額等は元売上レシート"))
        assertTrue(text.contains("登録番号 T1234567890123"))
        assertFalse(text.contains("【再発行】"))
    }

    @Test
    fun reprintIsVisiblyMarked() {
        val text = ReceiptVoucherRenderer.render(
            ReceiptVoucherDocumentData(
                issuanceId = 1,
                saleId = 2,
                sequenceNo = 1,
                sequenceCount = 1,
                amount = 5_000,
                addressee = "テスト株式会社 御中",
                purpose = "飲食代",
                operatorName = "担当A",
                issuedAt = 1_700_000_000_000,
                issuer = InvoiceIssuerProfile(storeName = "つぐ食堂"),
                reprintedAt = 1_700_000_100_000,
                reprintedBy = "責任者B",
            ),
            ReceiptPaper.MM58,
        )
        assertTrue(text.contains("【再発行】"))
        assertTrue(text.contains("再発行担当 責任者B"))
    }

    @Test
    fun sourceUsesImmutableHistoryAndExistingUnifiedDocumentQueue() {
        val root = File("..")
        val receipt = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val advanced = File("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt").readText()
        val unified = File("src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.58_RECEIPT_VOUCHER_FOUNDATION.md").readText()

        assertTrue(receipt.contains("CREATE TABLE IF NOT EXISTS receipt_voucher_batches"))
        assertTrue(receipt.contains("request_id TEXT NOT NULL UNIQUE"))
        assertTrue(receipt.contains("CREATE TABLE IF NOT EXISTS receipt_voucher_issuances"))
        assertTrue(receipt.contains("CREATE TABLE IF NOT EXISTS receipt_voucher_reprints"))
        assertTrue(receipt.contains("OperationDocumentType.RECEIPT_VOUCHER.name"))
        assertTrue(receipt.contains("db.beginTransaction()"))
        assertTrue(receipt.contains("existingBatchResult"))
        assertTrue(advanced.contains("RECEIPT_VOUCHER(\"領収書\")"))
        assertTrue(unified.contains("RECEIPT_VOUCHER(\"領収書\")"))
        assertTrue(unified.contains("UnifiedPrintTypeFilter.RECEIPT"))
        assertTrue(build.contains("versionCode = 88"))
        assertTrue(build.contains("versionName = \"0.58.0-dev.1\""))
        assertTrue(workflow.contains("V058ReceiptVoucherFoundationTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.58.0_dev1_receipt_voucher_foundation_debug.apk"))
        assertTrue(docs.contains("4,000円 × 30枚"))
        assertTrue(docs.contains("元売上レシート"))
    }
}
'''
write("app/src/test/java/jp/co/tenposinfo/register/V058ReceiptVoucherFoundationTest.kt", test)

# Version bump.
build_path = "app/build.gradle.kts"
build = read(build_path)
build = replace_once(build, "versionCode = 87", "versionCode = 88", "app versionCode")
build = replace_once(build, 'versionName = "0.57.0-dev.1"', 'versionName = "0.58.0-dev.1"', "app versionName")
write(build_path, build)

# Keep every historical cumulative test, but advance only the current-version expectations.
replacements = {
    "versionCode = 87": "versionCode = 88",
    'versionName = \\\"0.57.0-dev.1\\\"': 'versionName = \\\"0.58.0-dev.1\\\"',
    "TSUGUREGI_v0.57.0_dev1_held_ticket_operations_ui_debug.apk": "TSUGUREGI_v0.58.0_dev1_receipt_voucher_foundation_debug.apk",
    "TSUGUREGI-v0.57.0-dev1-held-ticket-operations-ui-apks": "TSUGUREGI-v0.58.0-dev1-receipt-voucher-foundation-apks",
}
for base in [ROOT / "app/src/test", ROOT / "management-app/src/test"]:
    for path in base.rglob("*.kt"):
        if path.name == "V058ReceiptVoucherFoundationTest.kt":
            continue
        original = path.read_text(encoding="utf-8")
        updated = original
        for old, new in replacements.items():
            updated = updated.replace(old, new)
        if updated != original:
            path.write_text(updated, encoding="utf-8")

# Final cumulative workflow is based on v0.57; the temporary apply workflow is never retained.
workflow_path = ".github/workflows/build-apk.yml"
workflow = subprocess.check_output(
    ["git", "show", "origin/develop/v0.57:.github/workflows/build-apk.yml"],
    text=True,
)
workflow = workflow.replace("Verify cumulative v0.14-v0.57 sources", "Verify cumulative v0.14-v0.58 sources")
workflow = workflow.replace("versionCode = 87", "versionCode = 88")
workflow = workflow.replace('versionName = "0.57.0-dev.1"', 'versionName = "0.58.0-dev.1"')
workflow = workflow.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V057HeldTicketOperationsUiTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V057HeldTicketOperationsUiTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V058ReceiptVoucherFoundationTest.kt\n",
)
workflow = workflow.replace(
    "          test -s docs/V0.57_RELEASE_NOTES.md\n",
    "          test -s docs/V0.57_RELEASE_NOTES.md\n"
    "          test -s docs/V0.58_RECEIPT_VOUCHER_FOUNDATION.md\n"
    "          test -s docs/V0.58_RELEASE_NOTES.md\n",
)
workflow = workflow.replace(
    "          test -s app/src/main/java/jp/co/tenposinfo/register/HeldTicketOperationsUi.kt\n",
    "          test -s app/src/main/java/jp/co/tenposinfo/register/HeldTicketOperationsUi.kt\n"
    "          test -s app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt\n"
    "          grep -q 'RECEIPT_VOUCHER' app/src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt\n"
    "          grep -q 'RECEIPT_VOUCHER' app/src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt\n"
    "          grep -q 'receipt_voucher_batches' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt\n",
)
workflow = workflow.replace(
    "TSUGUREGI_v0.57.0_dev1_held_ticket_operations_ui_debug.apk",
    "TSUGUREGI_v0.58.0_dev1_receipt_voucher_foundation_debug.apk",
)
workflow = workflow.replace(
    "TSUGUREGI-v0.57.0-dev1-held-ticket-operations-ui-apks",
    "TSUGUREGI-v0.58.0-dev1-receipt-voucher-foundation-apks",
)
workflow = workflow.replace("REGISTER_VERSION_NAME=0.57.0-dev.1", "REGISTER_VERSION_NAME=0.58.0-dev.1")
workflow = workflow.replace("REGISTER_VERSION_CODE=87", "REGISTER_VERSION_CODE=88")
workflow = workflow.replace(
    "          HELD_TICKET_SPLIT_QUANTITY_UI=true\n",
    "          HELD_TICKET_SPLIT_QUANTITY_UI=true\n"
    "          RECEIPT_VOUCHER_FOUNDATION=true\n"
    "          RECEIPT_VOUCHER_PARTIAL_ALLOCATION=true\n"
    "          RECEIPT_VOUCHER_BATCH_ISSUE=true\n"
    "          RECEIPT_VOUCHER_REPRINT_HISTORY=true\n"
    "          RECEIPT_VOUCHER_UNIFIED_PRINT_QUEUE=true\n"
    "          RECEIPT_VOUCHER_UI=false\n",
)
write(workflow_path, workflow)

write(
    "docs/V0.58_RECEIPT_VOUCHER_FOUNDATION.md",
    '''# v0.58 領収書発行・一部領収 安全基盤

## 目的

既存の売上レシート、58/80mm印刷、再印字、統合印刷キューを作り直さず、専用の「領収書」運用を追加する。

v0.58では、UIを接続する前に、二重発行防止・一部領収の残額管理・宴会複数枚発行・再発行履歴・既存印刷キュー接続をDB/ドメイン層で固定する。

## 発行単位

- 売上No.に紐付けて発行する
- 宛名、但し書き、1枚あたり金額、枚数、担当者を保存する
- 1枚から最大200枚までのバッチ発行に対応する
- 例: **4,000円 × 30枚 = 120,000円**
- 発行合計が売上の発行可能残額を超える場合は拒否する
- 一部領収を複数回行っても、累計発行額が元売上金額を超えない

## 二重発行防止

発行要求ごとにUUID `request_id` を持つ。

- `receipt_voucher_batches.request_id` はUNIQUE
- 同一要求IDの再実行は新しい領収書・印刷ジョブを作成しない
- 同じ結果を冪等に返す
- バッチ本体、各領収書、各印刷ジョブを同一SQLiteトランザクションで保存する

## 保存履歴

追記専用テーブル:

- `receipt_voucher_batches`
- `receipt_voucher_issuances`
- `receipt_voucher_reprints`

元売上、通常レシート、Sales Journal、Drive JSON、同期fingerprint、SENT Outbox、隔離履歴は上書き・削除しない。

## 再発行

- 元の領収書履歴は変更しない
- 再発行ごとに`receipt_voucher_reprints`へ履歴を追加する
- 印字には`【再発行】`、再発行日時、担当者を表示する
- 再発行は一部領収の発行済み金額へ二重加算しない

## 印字

既存`document_print_jobs`と統合印刷キューへ新種別 `RECEIPT_VOUCHER` を追加する。

- 既存プリンター設定の58mm/80mm幅を使用
- PENDING/PRINTING/COMPLETED/RETRY/FAILED/DISCARDED運用を継承
- WorkManager自動印刷、手動再試行、安全印刷、責任者破棄の既存運用を継承

領収書には次を表示する。

- 領収書No.
- 元売上No.
- 宛名
- 領収金額
- 但し書き
- 発行日時・担当者
- 複数枚時の連番（例 1/30）
- 店舗情報・登録番号
- 再発行時の再発行表示

## インボイスとの関係

一部領収額へ税率別税額を独自按分しない。

領収書に元売上No.を明示し、税率別の取引内容・消費税額等は既存の**元売上レシート**へ関連付ける。通常レシート側の既存インボイス計算・税率別端数処理を変更しない。

## v0.58テスト

`V058ReceiptVoucherFoundationTest`

- 4,000円 × 30枚の合計計算
- 発行可能残額超過の拒否
- 元売上No.参照を含む領収書レンダリング
- 再発行表示
- UUID一意要求、追記専用履歴、統合印刷キュー接続のソース検査

## UI

v0.58では発行安全基盤までを確定し、日常操作画面は次の累積版で接続する。

## 実機確認

以下はCIでは確認できないため、実機確認済みとは扱わない。

- 実SQLiteでの30枚以上の一括発行
- 実プリンターでの連続印刷
- 58mm/80mmの実印字レイアウト
- 印刷途中の紙切れ・LAN切断・電源断
- 実機でのv0.57→v0.58上書き更新
''',
)

write(
    "docs/V0.58_RELEASE_NOTES.md",
    '''# つぐレジ v0.58 リリースノート

## バージョン

- つぐレジ: `0.58.0-dev.1` / versionCode `88`
- つぐレジ＋: `0.14.0-dev.1` / versionCode `14`（機能変更なし）
- つぐレジ CD: `0.14.0-dev.1` / versionCode `7`（機能変更なし）

## 主な変更

### 領収書発行安全基盤

- 売上No.に紐付く領収書履歴を追加
- 宛名・但し書き・金額・枚数・担当者を追記専用で保存
- 一部領収の発行済み金額と発行可能残額を管理
- 発行合計が売上金額を超える操作を拒否
- UUID要求IDで同一操作の二重実行を防止
- バッチ・領収書・印刷ジョブを単一SQLiteトランザクションで確定

### 宴会複数枚

`1枚あたり金額 × 枚数`で一括発行する基盤を追加。

例: `4,000円 × 30枚 = 120,000円`

最大200枚。各領収書は独立した領収書No.と連番を持つ。

### 再発行

- 元履歴を変更せず再発行イベントを追記
- `【再発行】`を明示
- 再発行は発行済み金額へ再加算しない

### 統合印刷キュー

既存業務帳票種別へ`RECEIPT_VOUCHER`を追加し、既存の自動印刷・再試行・安全印刷・破棄運用をそのまま利用する。

### インボイス計算

一部領収書へ税額を独自按分しない。元売上No.を明記して既存の元売上レシートへ関連付け、通常レシート側の税率別端数処理を変更しない。

## UI

v0.58は安全基盤まで。日常の領収書発行画面は次累積版で接続する。

## 実機確認

CI成功は実機確認を意味しない。30枚連続発行、58/80mm実印字、紙切れ・通信断、上書き更新は実機未確認として扱う。
''',
)

print("v0.58 patch applied")
