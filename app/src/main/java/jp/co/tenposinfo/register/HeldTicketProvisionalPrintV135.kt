package jp.co.tenposinfo.register

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
        return lines.joinToString("\n")
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

    fun enqueueIfAutomatic(ticketId: Long, actor: String): HeldTicketProvisionalPrintResultV135? {
        val setting = DocumentPrintSettingsStoreV136(appContext).load(DocumentPrintKindV136.PROVISIONAL_RECEIPT)
        if (!setting.autoPrintEnabled) return null
        return enqueue(ticketId, actor)
    }

    fun enqueue(ticketId: Long, actor: String): HeldTicketProvisionalPrintResultV135 {
        require(ticketId > 0L) { "保留伝票No.が不正です" }
        require(actor.isNotBlank()) { "担当者が必要です" }
        val ticket = database.listHeldTickets().firstOrNull { it.id == ticketId }
            ?: error("仮締め対象の保留伝票が見つかりません")
        val items = database.loadHeldTicket(ticketId)
        require(items.isNotEmpty()) { "仮締め対象の伝票に明細がありません" }
        val paperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext)
        val documentPrintSetting = DocumentPrintSettingsStoreV136(appContext).load(
            DocumentPrintKindV136.PROVISIONAL_RECEIPT,
        )
        val now = System.currentTimeMillis()
        val payload = HeldTicketProvisionalReceiptRendererV135.render(
            ticket = ticket,
            items = items,
            paper = ReceiptPaper.fromWidth(paperWidthMm),
        )

        val decoratedPayload = DocumentPrintSettingsPolicyV136.decorateText(payload, documentPrintSetting)
        db.beginTransaction()
        val jobId = try {
            val jobIds = buildList {
                kotlin.repeat(DocumentPrintSettingsPolicyV136.normalizeCopies(documentPrintSetting.copies)) { copyIndex ->
                    add(
                        db.insertOrThrow(
                            "document_print_jobs",
                            null,
                            ContentValues().apply {
                                put("document_type", OperationDocumentType.HELD_TICKET_PROVISIONAL.name)
                                put("reference_id", ticket.id)
                                put("paper_width_mm", paperWidthMm)
                                put("status", PrintJobStatus.PENDING.name)
                                put("attempt_count", 0)
                                putNull("last_error")
                                put("payload_text", decoratedPayload)
                                put("created_at", now + copyIndex)
                                put("updated_at", now + copyIndex)
                            },
                        ),
                    )
                }
            }
            val id = jobIds.first()
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
        return HeldTicketProvisionalPrintResultV135(jobId, ticket.id, decoratedPayload)
    }
}
