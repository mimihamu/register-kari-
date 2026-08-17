from pathlib import Path

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, got {count}')
    return text.replace(old, new, 1)


# Register a dedicated operation document type so provisional slips are never confused with finalized receipts.
path = 'app/src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt'
text = read(path)
text = replace_once(
    text,
    '    REVERSAL_RECEIPT("返品・取消レシート"),\n    SETTLEMENT_REPORT("点検・精算票"),',
    '    REVERSAL_RECEIPT("返品・取消レシート"),\n    HELD_TICKET_PROVISIONAL("仮締め票"),\n    SETTLEMENT_REPORT("点検・精算票"),',
    'operation document type',
)
write(path, text)

# Unified queue integration: filtering, retry/discard and manual print all use the existing safe document path.
path = 'app/src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt'
text = read(path)
text = replace_once(
    text,
    '    SALE_RECEIPT("売上レシート"),\n    REVERSAL_RECEIPT("返品・取消レシート"),',
    '    SALE_RECEIPT("売上レシート"),\n    HELD_TICKET_PROVISIONAL("仮締め票"),\n    REVERSAL_RECEIPT("返品・取消レシート"),',
    'unified job type',
)
text = replace_once(
    text,
    '    SETTLEMENT("点検・精算"),\n    RECEIPT("領収書"),',
    '    SETTLEMENT("点検・精算"),\n    HELD_TICKET("仮締め票"),\n    RECEIPT("領収書"),',
    'unified filter type',
)
text = replace_once(
    text,
    '            UnifiedPrintTypeFilter.SETTLEMENT -> job.type == UnifiedPrintJobType.SETTLEMENT_REPORT\n            UnifiedPrintTypeFilter.RECEIPT -> job.type == UnifiedPrintJobType.RECEIPT_VOUCHER',
    '            UnifiedPrintTypeFilter.SETTLEMENT -> job.type == UnifiedPrintJobType.SETTLEMENT_REPORT\n            UnifiedPrintTypeFilter.HELD_TICKET -> job.type == UnifiedPrintJobType.HELD_TICKET_PROVISIONAL\n            UnifiedPrintTypeFilter.RECEIPT -> job.type == UnifiedPrintJobType.RECEIPT_VOUCHER',
    'unified filter mapping',
)
text = replace_once(
    text,
    '                    OperationDocumentType.REVERSAL_RECEIPT -> UnifiedPrintJobType.REVERSAL_RECEIPT\n                    OperationDocumentType.SETTLEMENT_REPORT -> UnifiedPrintJobType.SETTLEMENT_REPORT',
    '                    OperationDocumentType.REVERSAL_RECEIPT -> UnifiedPrintJobType.REVERSAL_RECEIPT\n                    OperationDocumentType.HELD_TICKET_PROVISIONAL -> UnifiedPrintJobType.HELD_TICKET_PROVISIONAL\n                    OperationDocumentType.SETTLEMENT_REPORT -> UnifiedPrintJobType.SETTLEMENT_REPORT',
    'document mapping',
)
# There are exactly three exhaustive document-action branches: retry, discard and print.
old_branch = '            UnifiedPrintJobType.REVERSAL_RECEIPT,\n            UnifiedPrintJobType.SETTLEMENT_REPORT,'
new_branch = '            UnifiedPrintJobType.REVERSAL_RECEIPT,\n            UnifiedPrintJobType.HELD_TICKET_PROVISIONAL,\n            UnifiedPrintJobType.SETTLEMENT_REPORT,'
if text.count(old_branch) != 2:
    raise SystemExit(f'unified top-level action branches: expected 2 matches, got {text.count(old_branch)}')
text = text.replace(old_branch, new_branch)
old_nested = '                    UnifiedPrintJobType.REVERSAL_RECEIPT,\n                    UnifiedPrintJobType.SETTLEMENT_REPORT,'
new_nested = '                    UnifiedPrintJobType.REVERSAL_RECEIPT,\n                    UnifiedPrintJobType.HELD_TICKET_PROVISIONAL,\n                    UnifiedPrintJobType.SETTLEMENT_REPORT,'
text = replace_once(text, old_nested, new_nested, 'unified nested print branch')
write(path, text)

# Functional renderer + queue writer. No sales row/payment/finalized receipt is created.
path = 'app/src/main/java/jp/co/tenposinfo/register/HeldTicketProvisionalPrintV135.kt'
if (ROOT / path).exists():
    raise SystemExit(f'{path} already exists')
write(path, '''package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class HeldTicketProvisionalPrintResultV135(
    val jobId: Long,
    val ticketId: Long,
    val previewText: String,
)

/** REP-001 residual: 保留伝票の会計前確認用仮締め票。売上確定データは生成しない。 */
internal object HeldTicketProvisionalReceiptRendererV135 {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun render(ticket: HeldTicket, items: List<CartItem>, paper: ReceiptPaper): String {
        require(items.isNotEmpty()) { "仮締め対象の伝票に明細がありません" }
        val width = paper.charsPerLine
        val summary = TaxEngine.calculate(items)
        val issuer = TaxInvoiceSettingsRegistry.current().issuer
        val lines = mutableListOf<String>()
        lines += center(issuer.storeName, width)
        lines += center("【仮締め票】", width)
        lines += separator(width, '=')
        lines += fit("伝票 ${ticket.name}  No.${ticket.id}", width)
        lines += fit("保留 ${formatDate(ticket.createdAt)}", width)
        lines += fit("担当 ${ticket.operatorName}", width)
        lines += if (ticket.guestCount > 0) "客数 ${ticket.guestCount}名" else "客数 未設定"
        lines += separator(width, '-')

        items.forEach { item ->
            lines += fit("${item.product.name} [${item.product.taxSymbol}]", width)
            lines += amountLine("${item.quantity} × ${yen(item.unitPrice)}", yen(item.amountBeforeDiscount), width)
            if (item.discountAmount > 0) {
                lines += amountLine("  値引", "-${yen(item.discountAmount)}", width)
            }
            if (item.note.isNotBlank()) lines += fit("  ※${item.note}", width)
        }

        lines += separator(width, '-')
        summary.buckets.forEach { bucket ->
            if (bucket.taxable) {
                lines += amountLine("${bucket.ratePercent}%対象額（税込）", yen(bucket.grossAmount), width)
                lines += amountLine("  消費税等", yen(bucket.taxAmount), width)
            } else {
                lines += amountLine("非課税対象額", yen(bucket.grossAmount), width)
            }
        }
        lines += separator(width, '=')
        lines += amountLine("合計", yen(summary.grossAmount), width)
        lines += separator(width, '-')
        lines += fit("※会計前の確認用です。売上確定ではありません。", width)
        lines += "※は軽減税率対象商品です"
        return lines.joinToString("\\n")
    }

    private fun formatDate(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(dateFormatter)

    private fun amountLine(label: String, amount: String, width: Int): String {
        val amountWidth = displayWidth(amount)
        val labelWidth = (width - amountWidth - 1).coerceAtLeast(1)
        return padRight(fit(label, labelWidth), labelWidth) + " " + amount
    }

    private fun separator(width: Int, char: Char): String = char.toString().repeat(width)

    private fun center(value: String, width: Int): String {
        val fitted = fit(value, width)
        val left = ((width - displayWidth(fitted)) / 2).coerceAtLeast(0)
        return " ".repeat(left) + fitted
    }

    private fun fit(value: String, width: Int): String {
        val out = StringBuilder()
        var used = 0
        for (char in value) {
            val charWidth = if (char.code <= 0xFF) 1 else 2
            if (used + charWidth > width) break
            out.append(char)
            used += charWidth
        }
        return out.toString()
    }

    private fun padRight(value: String, width: Int): String =
        value + " ".repeat((width - displayWidth(value)).coerceAtLeast(0))

    private fun displayWidth(value: String): Int = value.sumOf { if (it.code <= 0xFF) 1 else 2 }

    private fun yen(amount: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(amount)
}

/**
 * 仮締め票を既存 document_print_jobs へ登録する。
 * AutomaticPrintWorker / UnifiedPrintQueue の送信結果不明時停止・再試行制御をそのまま利用する。
 */
internal class HeldTicketProvisionalPrintServiceV135(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val schemaStore = AdvancedOperationsStore(appContext)
    private val database = RegisterDatabase(appContext)
    private val db: SQLiteDatabase = database.writableDatabase

    override fun close() {
        database.close()
        schemaStore.close()
    }

    fun enqueue(ticketId: Long, actor: String): HeldTicketProvisionalPrintResultV135 {
        require(ticketId > 0L) { "保留伝票No.が不正です" }
        require(actor.isNotBlank()) { "担当者が必要です" }
        val ticket = database.listHeldTickets().firstOrNull { it.id == ticketId }
            ?: error("仮締め対象の保留伝票が見つかりません")
        val items = database.loadHeldTicket(ticketId)
        require(items.isNotEmpty()) { "仮締め対象の伝票に明細がありません" }
        val paperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext)
        val now = System.currentTimeMillis()
        val payload = HeldTicketProvisionalReceiptRendererV135.render(
            ticket = ticket,
            items = items,
            paper = ReceiptPaper.fromWidth(paperWidthMm),
        )

        db.beginTransaction()
        val jobId = try {
            val id = db.insertOrThrow(
                "document_print_jobs",
                null,
                ContentValues().apply {
                    put("document_type", OperationDocumentType.HELD_TICKET_PROVISIONAL.name)
                    put("reference_id", ticket.id)
                    put("paper_width_mm", paperWidthMm)
                    put("status", PrintJobStatus.PENDING.name)
                    put("attempt_count", 0)
                    putNull("last_error")
                    put("payload_text", payload)
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
            db.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", "HELD_TICKET_PROVISIONAL_PRINT_QUEUED")
                    put("reference_id", ticket.id)
                    put("detail", "${ticket.name} / 客数 ${if (ticket.guestCount > 0) "${ticket.guestCount}名" else "未設定"} / Job.$id")
                    put("operator_name", actor.trim())
                    put("created_at", now)
                },
            )
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
        return HeldTicketProvisionalPrintResultV135(jobId, ticket.id, payload)
    }
}
''')

# Ticket list exposes a provisional-print action in compact and regular layouts.
path = 'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt'
text = read(path)
text = replace_once(
    text,
    '    onMerge: (HeldTicket, HeldTicket) -> Unit,\n    onSplit: (HeldTicket) -> Unit,\n    onBack: () -> Unit,',
    '    onMerge: (HeldTicket, HeldTicket) -> Unit,\n    onSplit: (HeldTicket) -> Unit,\n    onPrint: (HeldTicket) -> Unit,\n    onBack: () -> Unit,',
    'TicketListScreen callback signature',
)
call_old = '''                onSplit = { ticket ->
                    selectedHeldTicketId = ticket.id
                    ticketMessage = null
                    screen = AppScreen.TICKET_SPLIT
                },
                onBack = { screen = AppScreen.SALES },'''
call_new = '''                onSplit = { ticket ->
                    selectedHeldTicketId = ticket.id
                    ticketMessage = null
                    screen = AppScreen.TICKET_SPLIT
                },
                onPrint = { ticket ->
                    runCatching {
                        val service = HeldTicketProvisionalPrintServiceV135(context.applicationContext)
                        try {
                            service.enqueue(ticket.id, operatorName)
                        } finally {
                            service.close()
                        }
                    }.onSuccess { result ->
                        ticketMessage = "${ticket.name}の仮締め票を印刷キューへ登録しました（Job.${result.jobId}）"
                        runCatching { AutomaticPrintScheduler.enqueueNow(context.applicationContext) }
                    }.onFailure { error ->
                        ticketMessage = error.message ?: "仮締め票を登録できませんでした"
                    }
                },
                onBack = { screen = AppScreen.SALES },'''
text = replace_once(text, call_old, call_new, 'TicketListScreen callback call')
compact_old = '''                              BlueButton(
                                  if (currentCartCount > 0) "退避して呼出" else "呼出",
                                  { onLoad(ticket) },
                                  Modifier.weight(1.25f).height(42.dp),
                              )'''
compact_new = '''                              OutlinedButton(
                                  onClick = { onPrint(ticket) },
                                  modifier = Modifier.weight(0.9f).height(42.dp),
                              ) { Text("仮締め", fontSize = 12.sp, maxLines = 1) }
                              BlueButton(
                                  if (currentCartCount > 0) "退避して呼出" else "呼出",
                                  { onLoad(ticket) },
                                  Modifier.weight(1.25f).height(42.dp),
                              )'''
text = replace_once(text, compact_old, compact_new, 'compact provisional button')
wide_old = '''                          BlueButton(
                              if (currentCartCount > 0) "退避して呼出" else "呼出",
                              { onLoad(ticket) },
                              Modifier.width(if (currentCartCount > 0) 145.dp else 105.dp),
                          )'''
wide_new = '''                          OutlinedButton(
                              onClick = { onPrint(ticket) },
                              modifier = Modifier.width(92.dp),
                          ) { Text("仮締め") }
                          Spacer(Modifier.width(8.dp))
                          BlueButton(
                              if (currentCartCount > 0) "退避して呼出" else "呼出",
                              { onLoad(ticket) },
                              Modifier.width(if (currentCartCount > 0) 145.dp else 105.dp),
                          )'''
text = replace_once(text, wide_old, wide_new, 'wide provisional button')
write(path, text)

# Focused renderer/queue integration regression tests.
path = 'app/src/test/java/jp/co/tenposinfo/register/V135HeldTicketProvisionalPrintTest.kt'
if (ROOT / path).exists():
    raise SystemExit(f'{path} already exists')
write(path, '''package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V135HeldTicketProvisionalPrintTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun source(name: String): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/$name",
    ).readText()

    @Test
    fun provisionalRendererPrintsGuestCountAndNeverClaimsFinalization() {
        val product = Product("P1", "テスト商品", 1_100L, TaxCategory.INCLUDED_10, 1)
        val ticket = HeldTicket(
            id = 10L,
            name = "宴会A",
            operatorName = "担当A",
            createdAt = 1_700_000_000_000L,
            itemCount = 1,
            totalAmount = 1_100L,
            guestCount = 6,
        )
        val rendered = HeldTicketProvisionalReceiptRendererV135.render(
            ticket,
            listOf(CartItem(product = product, quantity = 1)),
            ReceiptPaper.MM80,
        )
        assertTrue(rendered.contains("【仮締め票】"))
        assertTrue(rendered.contains("客数 6名"))
        assertTrue(rendered.contains("合計"))
        assertTrue(rendered.contains("売上確定ではありません"))
        assertFalse(rendered.contains("領収書／レシート"))
    }

    @Test
    fun provisionalSlipUsesDedicatedDocumentTypeAndUnifiedSafeQueue() {
        val service = source("HeldTicketProvisionalPrintV135.kt")
        val queue = source("UnifiedPrintQueue.kt")
        val worker = source("AutomaticPrintWorker.kt")
        assertTrue(service.contains("OperationDocumentType.HELD_TICKET_PROVISIONAL"))
        assertTrue(service.contains("document_print_jobs"))
        assertTrue(service.contains("HELD_TICKET_PROVISIONAL_PRINT_QUEUED"))
        assertTrue(queue.contains("UnifiedPrintJobType.HELD_TICKET_PROVISIONAL"))
        assertTrue(worker.contains("operations.processDocumentPrint"))
        assertFalse(service.contains("saveSale("))
    }

    @Test
    fun ticketListOffersProvisionalPrintAction() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("onPrint: (HeldTicket) -> Unit"))
        assertTrue(main.contains("HeldTicketProvisionalPrintServiceV135"))
        assertTrue(main.contains("Text(\"仮締め\""))
    }
}
''')

# Close the documentation residual only after code/tests are added.
path = 'docs/v1.35-bizday-residual-gap.md'
text = read(path)
text = replace_once(
    text,
    '- Held-ticket provisional-slip printing remains a separate residual item and is not marked complete by this change.',
    '- Held-ticket provisional slips now print guest count, item/tax/total details, explicitly state that the sale is not finalized, and run through the existing safe document print queue.',
    'docs provisional status',
)
write(path, text)

print('provisional patch applied')
