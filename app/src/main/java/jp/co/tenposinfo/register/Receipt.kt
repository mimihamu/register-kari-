package jp.co.tenposinfo.register

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 売上一覧に表示する不変の集計スナップショット。
 */
data class SaleSummaryRecord(
    val id: Long,
    val operatorName: String,
    val paymentLabel: String,
    val totalAmount: Long,
    val taxAmount: Long,
    val changeAmount: Long,
    val createdAt: Long,
    val printCount: Int,
)

data class SaleDetailRecord(
    val summary: SaleSummaryRecord,
    val items: List<CartItem>,
    val payments: List<PaymentAllocation>,
    val taxSummary: TaxSummary,
)

enum class PrintJobStatus {
    PENDING,
    PRINTING,
    COMPLETED,
    RETRY,
    FAILED,
}

data class PrintJobRecord(
    val id: Long,
    val saleId: Long,
    val paperWidthMm: Int,
    val status: PrintJobStatus,
    val attemptCount: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ReceiptData(
    val storeName: String,
    val registrationNumber: String,
    val saleId: Long,
    val createdAt: Long,
    val operatorName: String,
    val items: List<CartItem>,
    val taxSummary: TaxSummary,
    val payments: List<PaymentAllocation>,
    val changeAmount: Long,
    val reprint: Boolean = false,
)

enum class ReceiptPaper(val widthMm: Int, val charsPerLine: Int) {
    MM58(58, 32),
    MM80(80, 48),
    ;

    companion object {
        fun fromWidth(widthMm: Int): ReceiptPaper = if (widthMm >= 80) MM80 else MM58
    }
}

object ReceiptFactory {
    fun fromSale(detail: SaleDetailRecord, reprint: Boolean = false): ReceiptData = ReceiptData(
        storeName = "サンプル居酒屋",
        registrationNumber = "T1234567890123",
        saleId = detail.summary.id,
        createdAt = detail.summary.createdAt,
        operatorName = detail.summary.operatorName,
        items = detail.items,
        taxSummary = detail.taxSummary,
        payments = detail.payments,
        changeAmount = detail.summary.changeAmount,
        reprint = reprint,
    )

    fun fromCurrentSale(
        saleId: Long,
        createdAt: Long,
        operatorName: String,
        items: List<CartItem>,
        payments: List<PaymentAllocation>,
        changeAmount: Long,
    ): ReceiptData = ReceiptData(
        storeName = "サンプル居酒屋",
        registrationNumber = "T1234567890123",
        saleId = saleId,
        createdAt = createdAt,
        operatorName = operatorName,
        items = items,
        taxSummary = TaxEngine.calculate(items),
        payments = payments,
        changeAmount = changeAmount,
    )
}

/**
 * 58mm／80mmで共通の構造化データを使用し、表示幅だけを切り替える。
 */
object ReceiptRenderer {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun render(data: ReceiptData, paper: ReceiptPaper): String {
        val width = paper.charsPerLine
        val lines = mutableListOf<String>()
        lines += center(data.storeName, width)
        if (data.reprint) lines += center("【再発行】", width)
        lines += center("領収書／レシート", width)
        lines += separator(width, '=')
        lines += "No.${data.saleId}  ${formatDate(data.createdAt)}"
        lines += "担当 ${data.operatorName}"
        lines += separator(width, '-')

        data.items.forEach { item ->
            val symbol = item.product.taxCategory.symbol
            lines += fit("${item.product.name} [$symbol]", width)
            val amount = item.baseAmount
            lines += amountLine("${item.quantity} × ${yen(item.unitPrice)}", yen(amount), width)
            if (item.discountAmount > 0) {
                lines += amountLine("  値引", "-${yen(item.discountAmount)}", width)
            }
            if (item.note.isNotBlank()) lines += fit("  ※${item.note}", width)
        }

        lines += separator(width, '-')
        lines += amountLine("小計（税抜）", yen(data.taxSummary.netAmount), width)
        data.taxSummary.buckets.forEach { bucket ->
            val label = if (bucket.taxable) {
                "${bucket.ratePercent}%対象 消費税"
            } else {
                "非課税対象"
            }
            val value = if (bucket.taxable) yen(bucket.taxAmount) else yen(bucket.grossAmount)
            lines += amountLine(label, value, width)
        }
        lines += separator(width, '=')
        lines += amountLine("合計", yen(data.taxSummary.grossAmount), width)
        lines += separator(width, '-')
        data.payments.forEach { payment ->
            lines += amountLine(payment.method.displayName, yen(payment.receivedAmount), width)
            if (payment.receivedAmount != payment.appliedAmount) {
                lines += amountLine("  充当", yen(payment.appliedAmount), width)
            }
        }
        if (data.changeAmount > 0) lines += amountLine("お釣り", yen(data.changeAmount), width)
        lines += separator(width, '-')
        lines += fit("登録番号 ${data.registrationNumber}", width)
        lines += "※は軽減税率対象商品です"
        lines += "内/外は内税・外税区分です"
        lines += center("ありがとうございました", width)
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

object EscPosEncoder {
    private val ms932: Charset = Charset.forName("MS932")

    fun encode(data: ReceiptData, paper: ReceiptPaper): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x1B, 0x40)) // ESC @ initialize
        output.write(byteArrayOf(0x1B, 0x74, 0x01)) // code table: CP932 compatible setting
        output.write(byteArrayOf(0x1B, 0x61, 0x00)) // left align
        output.write(ReceiptRenderer.render(data, paper).toByteArray(ms932))
        output.write(byteArrayOf(0x0A, 0x0A, 0x0A))
        output.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // partial cut
        return output.toByteArray()
    }
}

interface PrinterGateway {
    fun send(payload: ByteArray): Result<Unit>
}

class TcpEscPosPrinterGateway(
    private val host: String,
    private val port: Int = 9100,
    private val timeoutMillis: Int = 5_000,
) : PrinterGateway {
    override fun send(payload: ByteArray): Result<Unit> = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            socket.getOutputStream().use { stream ->
                stream.write(payload)
                stream.flush()
            }
        }
    }
}

class MemoryPrinterGateway : PrinterGateway {
    val sentPayloads = mutableListOf<ByteArray>()

    override fun send(payload: ByteArray): Result<Unit> = runCatching {
        sentPayloads += payload.copyOf()
    }
}

class PrintQueueProcessor(
    private val database: RegisterDatabase,
    private val gateway: PrinterGateway,
) {
    fun processNext(): Boolean {
        val job = database.nextPrintableJob() ?: return false
        val detail = database.loadSaleDetail(job.saleId)
        if (detail == null) {
            database.markPrintFailed(job.id, "売上データが見つかりません", permanent = true)
            return false
        }
        database.markPrintStarted(job.id)
        val receipt = ReceiptFactory.fromSale(detail, reprint = detail.summary.printCount > 0)
        val result = gateway.send(EscPosEncoder.encode(receipt, ReceiptPaper.fromWidth(job.paperWidthMm)))
        result.onSuccess {
            database.markPrintCompleted(job.id)
        }.onFailure {
            database.markPrintFailed(job.id, it.message ?: it.javaClass.simpleName, permanent = false)
        }
        return result.isSuccess
    }
}
