package jp.co.tenposinfo.register

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReversalDocumentData(
    val reversalId: Long,
    val originalSaleId: Long,
    val type: ReversalType,
    val createdAt: Long,
    val operatorName: String,
    val reason: String,
    val items: List<CartItem>,
    val taxSummary: TaxSummary,
    val refundPayments: List<PaymentTotal>,
    val issuer: InvoiceIssuerProfile = TaxInvoiceSettingsRegistry.current().issuer,
)

data class SettlementDocumentData(
    val reportId: Long,
    val businessDate: String,
    val type: SettlementReportType,
    val createdAt: Long,
    val operatorName: String,
    val salesGross: Long,
    val reversalGross: Long,
    val netSales: Long,
    val openingCash: Long,
    val cashIn: Long,
    val cashOut: Long,
    val expectedCash: Long,
    val actualCash: Long,
    val variance: Long,
    val transactionCount: Int,
    val reversalCount: Int,
    val pendingPrints: Int,
    val heldTickets: Int,
    val paymentTotals: List<PaymentTotal>,
    val businessSessionId: Long = 0L,
    val snapshotVersion: Int = SettlementSnapshotSchemaV027.SNAPSHOT_VERSION,
    val reprintedAt: Long? = null,
    val reprintedBy: String? = null,
)

object OperationDocumentRenderer {
    private val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun renderReversal(data: ReversalDocumentData, paper: ReceiptPaper): String {
        val width = paper.charsPerLine
        val lines = mutableListOf<String>()
        lines += center(data.issuer.storeName, width)
        if (data.issuer.address.isNotBlank()) lines += center(data.issuer.address, width)
        if (data.issuer.phone.isNotBlank()) lines += center(data.issuer.phone, width)
        lines += center(if (data.type == ReversalType.CANCEL) "【取消レシート】" else "【返品レシート】", width)
        lines += separator(width, '=')
        lines += "処理No.${data.reversalId}  元売上No.${data.originalSaleId}"
        lines += formatDate(data.createdAt)
        lines += "担当 ${data.operatorName}"
        lines += fit("理由 ${data.reason}", width)
        lines += separator(width, '-')
        data.items.forEach { item ->
            lines += fit("${item.product.name} [${item.product.taxSymbol}]", width)
            lines += amountLine("${item.quantity} × ${yen(item.unitPrice)}", "-${yen(item.baseAmount)}", width)
            if (item.discountAmount > 0) {
                lines += amountLine("  元値引配賦", yen(item.discountAmount), width)
            }
        }
        lines += separator(width, '-')
        data.taxSummary.buckets.forEach { bucket ->
            val label = if (bucket.taxable) "${bucket.ratePercent}%対象 消費税" else "非課税対象"
            val value = if (bucket.taxable) bucket.taxAmount else bucket.grossAmount
            lines += amountLine(label, "-${yen(value)}", width)
        }
        lines += separator(width, '=')
        lines += amountLine("返金合計", "-${yen(data.taxSummary.grossAmount)}", width)
        lines += separator(width, '-')
        data.refundPayments.forEach { payment ->
            lines += amountLine("${paymentLabel(payment.method)}返金", yen(payment.amount), width)
        }
        lines += separator(width, '-')
        lines += "※は軽減税率対象商品です"
        if (data.issuer.registrationNumber.isNotBlank()) {
            lines += "登録番号 ${data.issuer.registrationNumber}"
        }
        return lines.joinToString("\n")
    }

    fun renderSettlement(data: SettlementDocumentData, paper: ReceiptPaper): String {
        val width = paper.charsPerLine
        val lines = mutableListOf<String>()
        val issuer = TaxInvoiceSettingsRegistry.current().issuer
        lines += center(issuer.storeName, width)
        if (issuer.address.isNotBlank()) lines += center(issuer.address, width)
        if (issuer.phone.isNotBlank()) lines += center(issuer.phone, width)
        lines += center(if (data.type == SettlementReportType.Z_SETTLEMENT) "【Z精算票】" else "【X点検票】", width)
        if (data.reprintedAt != null) lines += center("【再印字】", width)
        lines += separator(width, '=')
        lines += "営業日 ${data.businessDate}"
        if (data.businessSessionId > 0) lines += "営業セッション No.${data.businessSessionId}"
        lines += "レポートNo.${data.reportId}"
        lines += "発行 ${formatDate(data.createdAt)}"
        lines += "担当 ${data.operatorName}"
        data.reprintedAt?.let { lines += "再印字 ${formatDate(it)}" }
        data.reprintedBy?.takeIf(String::isNotBlank)?.let { lines += "再印字担当 $it" }
        lines += separator(width, '-')
        lines += amountLine("売上総額", yen(data.salesGross), width)
        lines += amountLine("返品・取消", "-${yen(data.reversalGross)}", width)
        lines += amountLine("純売上", yen(data.netSales), width)
        lines += amountLine("開始釣銭", yen(data.openingCash), width)
        lines += amountLine("入金", yen(data.cashIn), width)
        lines += amountLine("出金", "-${yen(data.cashOut)}", width)
        lines += separator(width, '-')
        data.paymentTotals.forEach { payment ->
            lines += amountLine(paymentLabel(payment.method), yen(payment.amount), width)
        }
        lines += separator(width, '-')
        lines += amountLine("現金理論", yen(data.expectedCash), width)
        lines += amountLine("現金実査", yen(data.actualCash), width)
        lines += amountLine("現金過不足", signedYen(data.variance), width)
        lines += separator(width, '-')
        lines += amountLine("売上件数", "${data.transactionCount}件", width)
        lines += amountLine("返品取消件数", "${data.reversalCount}件", width)
        lines += amountLine("未印刷", "${data.pendingPrints}件", width)
        lines += amountLine("未会計伝票", "${data.heldTickets}件", width)
        lines += separator(width, '=')
        if (data.type == SettlementReportType.Z_SETTLEMENT) {
            lines += center("営業日を精算しました", width)
        } else {
            lines += center("営業日は継続中です", width)
        }
        return lines.joinToString("\n")
    }

    private fun formatDate(value: Long): String = Instant.ofEpochMilli(value)
        .atZone(ZoneId.systemDefault())
        .format(formatter)

    private fun paymentLabel(method: String): String = runCatching {
        PaymentMethod.valueOf(method).displayName
    }.getOrElse { if (method == "OTHER") "その他" else method }

    private fun yen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)

    private fun signedYen(value: Long): String = when {
        value > 0 -> "+${yen(value)}"
        value < 0 -> "-${yen(-value)}"
        else -> yen(0)
    }

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

object TextEscPosEncoder {
    fun encode(
        text: String,
        configuration: PrinterConfiguration = PrinterConfigurationRegistry.current() ?: PrinterConfiguration(),
    ): ByteArray = PrinterCommandEncoder.encodeText(
        text = text,
        configuration = configuration,
        openDrawer = false,
        appendCut = true,
    )
}
