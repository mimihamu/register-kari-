package jp.co.tenposinfo.register

import java.net.InetSocketAddress
import java.net.Socket
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    val invoiceAggregationBasis: InvoiceAggregationBasisV136 = InvoiceAggregationBasisV136.TAX_INCLUDED,
    val taxSnapshotLegacyFallback: Boolean = false,
)

enum class PrintJobStatus {
    PENDING,
    PRINTING, // legacy: v1.35 and earlier in-flight row
    SENDING,  // formal v2.5 §16.9: persisted before transport send
    COMPLETED,
    RETRY,
    FAILED,
    DISCARDED,
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
    val deliveryResult: PrintDeliveryResultV136? = null,
)

data class ReceiptData(
    val storeName: String,
    val storeAddress: String = "",
    val storePhone: String = "",
    val registrationNumber: String,
    val saleId: Long,
    val createdAt: Long,
    val operatorName: String,
    val items: List<CartItem>,
    val taxSummary: TaxSummary,
    val payments: List<PaymentAllocation>,
    val changeAmount: Long,
    val reprint: Boolean = false,
    val invoiceAggregationBasis: InvoiceAggregationBasisV136 = InvoiceAggregationBasisV136.TAX_INCLUDED,
    val documentCopies: Int = 1,
    val documentHeader: String = "",
    val documentFooter: String = ReceiptFooterMessagePolicyV136.DEFAULT_MESSAGE,
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
    private fun issuer(): InvoiceIssuerProfile = TaxInvoiceSettingsRegistry.current().issuer

    fun fromSale(detail: SaleDetailRecord, reprint: Boolean = false): ReceiptData {
        // NOTICE-001: loadSaleDetail() が復元した売上時発行者snapshotを最優先する。
        // snapshot導入前のlegacy売上だけは現在設定へフォールバックする。
        val issuer = SaleInvoiceIssuerSnapshotRegistryV136.forSale(detail.summary.id) ?: issuer()
        return ReceiptData(
            storeName = issuer.storeName,
            storeAddress = issuer.address,
            storePhone = issuer.phone,
            registrationNumber = issuer.registrationNumber,
            saleId = detail.summary.id,
            createdAt = detail.summary.createdAt,
            operatorName = detail.summary.operatorName,
            items = detail.items,
            taxSummary = detail.taxSummary,
            payments = detail.payments,
            changeAmount = detail.summary.changeAmount,
            reprint = reprint,
            invoiceAggregationBasis = detail.invoiceAggregationBasis,
        )
    }

    fun fromCurrentSale(
        saleId: Long,
        createdAt: Long,
        operatorName: String,
        items: List<CartItem>,
        payments: List<PaymentAllocation>,
        changeAmount: Long,
    ): ReceiptData {
        val settings = TaxInvoiceSettingsRegistry.current()
        val issuer = settings.issuer
        return ReceiptData(
            storeName = issuer.storeName,
            storeAddress = issuer.address,
            storePhone = issuer.phone,
            registrationNumber = issuer.registrationNumber,
            saleId = saleId,
            createdAt = createdAt,
            operatorName = operatorName,
            items = items,
            taxSummary = TaxEngine.calculate(items),
            payments = payments,
            changeAmount = changeAmount,
            invoiceAggregationBasis = settings.invoiceAggregationBasis,
        )
    }
}

/**
 * 58mm／80mmで共通の構造化データを使用する。
 * 実運用の幅はプリンター設定から決定し、各印刷操作では選択させない。
 */
object ReceiptRenderer {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun render(data: ReceiptData, paper: ReceiptPaper): String {
        val width = paper.charsPerLine
        val lines = mutableListOf<String>()
        if (data.reprint) lines += center("【再発行】", width)
        data.documentHeader.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { lines += fit(it, width) }
        lines += center(data.storeName, width)
        if (data.storeAddress.isNotBlank()) lines += center(data.storeAddress, width)
        if (data.storePhone.isNotBlank()) lines += center("TEL ${data.storePhone}", width)
        lines += center("領収書／レシート", width)
        lines += separator(width, '=')
        lines += "No.${ReceiptNumberV136.format(data.saleId)}  ${formatDate(data.createdAt)}"
        lines += "担当 ${data.operatorName}"
        lines += separator(width, '-')

        data.items.forEach { item ->
            val symbol = ReceiptTaxSymbolV136.fromProduct(item.product)
            lines.addAll(ReceiptLineWrapV136.wrap("${item.product.name} [$symbol]", width))
            val amount = item.baseAmount
            lines += amountLine("${item.quantity} × ${yen(item.unitPrice)}", yen(amount), width)
            if (item.discountAmount > 0) {
                lines += amountLine("  値引", "-${yen(item.discountAmount)}", width)
            }
            if (item.note.isNotBlank()) lines.addAll(ReceiptLineWrapV136.wrap("  ※${item.note}", width))
        }

        lines += separator(width, '-')
        lines += amountLine("税抜金額等", yen(data.taxSummary.netAmount), width)
        data.taxSummary.buckets.forEach { bucket ->
            if (bucket.taxable) {
                val taxableAmount = when (data.invoiceAggregationBasis) {
                    InvoiceAggregationBasisV136.TAX_INCLUDED -> bucket.grossAmount
                    InvoiceAggregationBasisV136.TAX_EXCLUDED -> bucket.netAmount
                }
                val basisLabel = when (data.invoiceAggregationBasis) {
                    InvoiceAggregationBasisV136.TAX_INCLUDED -> "税込"
                    InvoiceAggregationBasisV136.TAX_EXCLUDED -> "税抜"
                }
                lines += amountLine("${bucket.ratePercent}%対象額（$basisLabel）", yen(taxableAmount), width)
                lines += amountLine("  消費税等", yen(bucket.taxAmount), width)
            } else {
                lines += amountLine("非課税対象額", yen(bucket.grossAmount), width)
            }
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
        if (data.registrationNumber.isNotBlank()) {
            lines += fit("登録番号 ${data.registrationNumber}", width)
        }
        lines += "※は軽減税率対象商品です"
        lines += "内/外は内税・外税区分です"
        lines.addAll(ReceiptFooterMessagePolicyV136.renderLines(data.documentFooter, paper))
        if (data.reprint) lines += center("【再発行】", width)
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
    fun encode(
        data: ReceiptData,
        configuration: PrinterConfiguration = PrinterConfigurationRegistry.current() ?: PrinterConfiguration(),
    ): ByteArray {
        val openDrawer = configuration.drawerEnabled &&
            configuration.drawerOpenOnCashSale &&
            !data.reprint &&
            data.payments.any { it.method == PaymentMethod.CASH }
        val copies = DocumentPrintSettingsPolicyV136.normalizeCopies(data.documentCopies)
        return (0 until copies).fold(ByteArray(0)) { payload, copyIndex ->
            payload + PrinterCommandEncoder.encodeText(
                text = ReceiptRenderer.render(data, PrinterPaperSettingPolicy.paper(configuration)),
                configuration = configuration,
                openDrawer = openDrawer && copyIndex == 0,
                appendCut = true,
            )
        }
    }
}

interface PrinterGateway {
    fun send(payload: ByteArray): Result<Unit>
}

/**
 * TCP 9100送信で例外が発生した時点。
 * WRITE_STARTED以降はプリンターが一部または全部を受信した可能性があるため、
 * 自動再試行すると二重印刷になる危険がある。
 */
enum class PrinterDeliveryPhase {
    CONNECTING,
    CONNECTED,
    WRITE_STARTED,
    FLUSHED,
}

enum class PrinterFailureDisposition {
    SAFE_TO_RETRY,
    MANUAL_CONFIRMATION_REQUIRED,
}

class PrinterTransportException(
    val phase: PrinterDeliveryPhase,
    cause: Throwable,
) : RuntimeException(
    when (PrinterRetrySafety.classify(phase)) {
        PrinterFailureDisposition.SAFE_TO_RETRY ->
            "プリンターへ送信できませんでした（${phase.name}）: ${cause.message ?: cause.javaClass.simpleName}"

        PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED ->
            "送信結果が不明です。重複印刷防止のため自動再試行しません（${phase.name}）: ${cause.message ?: cause.javaClass.simpleName}"
    },
    cause,
)

object PrinterRetrySafety {
    fun classify(phase: PrinterDeliveryPhase): PrinterFailureDisposition = when (phase) {
        PrinterDeliveryPhase.CONNECTING,
        PrinterDeliveryPhase.CONNECTED,
        -> PrinterFailureDisposition.SAFE_TO_RETRY

        PrinterDeliveryPhase.WRITE_STARTED,
        PrinterDeliveryPhase.FLUSHED,
        -> PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED
    }

    fun classify(error: Throwable): PrinterFailureDisposition {
        var current: Throwable? = error
        while (current != null) {
            if (current is PrinterTransportException) return classify(current.phase)
            if (current is PrinterDeliveryConfirmationExceptionV136) {
                return PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED
            }
            current = current.cause
        }
        return PrinterFailureDisposition.SAFE_TO_RETRY
    }
}

class TcpEscPosPrinterGateway(
    private val host: String,
    private val port: Int = 9100,
    private val timeoutMillis: Int = 5_000,
) : PrinterGateway {
    override fun send(payload: ByteArray): Result<Unit> = runCatching {
        PrinterEndpointSendGate.withPermit(
            host = host,
            port = port,
            waitMillis = timeoutMillis.toLong(),
        ) {
            sendExclusive(payload)
        }
    }

    private fun sendExclusive(payload: ByteArray) {
        var phase = PrinterDeliveryPhase.CONNECTING
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                socket.soTimeout = timeoutMillis
                phase = PrinterDeliveryPhase.CONNECTED
                socket.getOutputStream().use { stream ->
                    phase = PrinterDeliveryPhase.WRITE_STARTED
                    stream.write(payload)
                    stream.flush()
                    phase = PrinterDeliveryPhase.FLUSHED
                }
            }
        } catch (error: Throwable) {
            if (error is PrinterTransportException) throw error
            throw PrinterTransportException(phase, error)
        }
    }
}

/**
 * 自動テストでは従来どおりメモリーへ保持する。
 * Android実行時に有効なプリンター設定がある場合は、別スレッドで実機にも送信する。
 */
class MemoryPrinterGateway : PrinterGateway {
    val sentPayloads = mutableListOf<ByteArray>()

    override fun send(payload: ByteArray): Result<Unit> = runCatching {
        sentPayloads += payload.copyOf()
        val configuration = PrinterConfigurationRegistry.current()
        if (configuration?.usable == true) {
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Unit> {
                    TcpEscPosPrinterGateway(
                        host = configuration.host,
                        port = configuration.port,
                        timeoutMillis = configuration.timeoutMillis,
                    ).send(payload).getOrThrow()
                }
                future.get((configuration.timeoutMillis + 2_000).toLong(), TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}

class PrintQueueProcessor(
    private val database: RegisterDatabase,
    private val gateway: PrinterGateway,
    private val saleReceiptSetting: DocumentPrintSettingV136 = DocumentPrintSettingV136(footer = ReceiptFooterMessagePolicyV136.DEFAULT_MESSAGE),
    private val printerConfiguration: PrinterConfiguration? = null,
) {
    fun processNext(): Boolean {
        val job = database.claimNextPrintableJob() ?: return false
        val detail = database.loadSaleDetail(job.saleId)
        if (detail == null) {
            database.markPrintFailed(job.id, "売上データが見つかりません", permanent = true)
            return false
        }
        val receipt = ReceiptFactory.fromSale(
            detail,
            reprint = ReceiptReprintPolicyV136.isReprint(
                jobCreatedAt = job.createdAt,
                saleCreatedAt = detail.summary.createdAt,
                completedPrintCount = detail.summary.printCount,
            ),
        )
        val configuredSnapshot = (printerConfiguration ?: PrinterConfigurationRegistry.current() ?: PrinterConfiguration()).copy(
            paperWidthMm = job.paperWidthMm,
        )
        val configuredReceipt = DocumentPrintSettingsPolicyV136.applyToReceipt(receipt, saleReceiptSetting)
        val sendResult = gateway.send(EscPosEncoder.encode(configuredReceipt, configuredSnapshot))
        if (sendResult.isFailure) {
            val error = sendResult.exceptionOrNull() ?: IllegalStateException("印刷送信に失敗しました")
            val manualConfirmation = PrinterRetrySafety.classify(error) ==
                PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED
            database.markPrintFailed(
                job.id,
                error.message ?: error.javaClass.simpleName,
                permanent = manualConfirmation,
            )
            return false
        }
        val confirmation = printerConfiguration?.let {
            PrintDeliveryConfirmationPolicyV136.confirm(configuredSnapshot)
        } ?: Result.success(PrintDeliveryResultV136.ACCEPTED)
        confirmation.onSuccess { deliveryResult ->
            database.markPrintCompleted(job.id, deliveryResult)
        }.onFailure { error ->
            database.markPrintFailed(
                job.id,
                error.message ?: error.javaClass.simpleName,
                permanent = true,
            )
        }
        return confirmation.isSuccess
    }
}
