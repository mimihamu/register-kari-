package jp.co.tenposinfo.register

import android.content.Context

enum class UnifiedPrintJobType(val displayName: String) {
    SALE_RECEIPT("売上レシート"),
    REVERSAL_RECEIPT("返品・取消レシート"),
    SETTLEMENT_REPORT("点検・精算票"),
}

data class UnifiedPrintJob(
    val key: String,
    val sourceId: Long,
    val type: UnifiedPrintJobType,
    val referenceId: Long,
    val paperWidthMm: Int,
    val status: PrintJobStatus,
    val attemptCount: Int,
    val lastError: String?,
    val previewText: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class UnifiedPrintQueueSummary(
    val total: Int,
    val pending: Int,
    val retry: Int,
    val failed: Int,
    val completed: Int,
    val printing: Int,
) {
    companion object {
        fun from(jobs: List<UnifiedPrintJob>) = UnifiedPrintQueueSummary(
            total = jobs.size,
            pending = jobs.count { it.status == PrintJobStatus.PENDING },
            retry = jobs.count { it.status == PrintJobStatus.RETRY },
            failed = jobs.count { it.status == PrintJobStatus.FAILED },
            completed = jobs.count { it.status == PrintJobStatus.COMPLETED },
            printing = jobs.count { it.status == PrintJobStatus.PRINTING },
        )
    }
}

object PrinterPreflightPolicy {
    fun mayPrint(status: PrinterRealtimeStatus): Boolean = when (status.level) {
        PrinterStatusLevel.READY,
        PrinterStatusLevel.WARNING,
        -> true

        PrinterStatusLevel.OFFLINE,
        PrinterStatusLevel.ERROR,
        -> false
    }

    fun rejectionMessage(status: PrinterRealtimeStatus): String =
        "安全印刷を停止しました：${status.summary}（RAW ${status.rawHex}）"
}

class UnifiedPrintQueueController(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val salesDatabase = RegisterDatabase(applicationContext)
    private val documentStore = AdvancedOperationsStore(applicationContext)
    private val settingsStore = AdminSettingsStore(applicationContext)

    override fun close() {
        settingsStore.close()
        documentStore.close()
        salesDatabase.close()
    }

    fun loadConfiguration(): PrinterConfiguration = settingsStore.loadPrinterConfiguration()

    fun loadJobs(limitPerType: Int = 200): List<UnifiedPrintJob> {
        val saleJobs = salesDatabase.listPrintJobs(limitPerType).map { job ->
            val detail = salesDatabase.loadSaleDetail(job.saleId)
            val preview = detail?.let {
                val receipt = ReceiptFactory.fromSale(it, reprint = it.summary.printCount > 0)
                ReceiptRenderer.render(receipt, ReceiptPaper.fromWidth(job.paperWidthMm))
            } ?: "売上 No.${job.saleId} の明細が見つかりません"
            UnifiedPrintJob(
                key = "SALE:${job.id}",
                sourceId = job.id,
                type = UnifiedPrintJobType.SALE_RECEIPT,
                referenceId = job.saleId,
                paperWidthMm = job.paperWidthMm,
                status = job.status,
                attemptCount = job.attemptCount,
                lastError = job.lastError,
                previewText = preview,
                createdAt = job.createdAt,
                updatedAt = job.updatedAt,
            )
        }
        val documentJobs = documentStore.listDocumentPrintJobs(limitPerType).map { job ->
            UnifiedPrintJob(
                key = "DOCUMENT:${job.id}",
                sourceId = job.id,
                type = when (job.documentType) {
                    OperationDocumentType.REVERSAL_RECEIPT -> UnifiedPrintJobType.REVERSAL_RECEIPT
                    OperationDocumentType.SETTLEMENT_REPORT -> UnifiedPrintJobType.SETTLEMENT_REPORT
                },
                referenceId = job.referenceId,
                paperWidthMm = job.paperWidthMm,
                status = job.status,
                attemptCount = job.attemptCount,
                lastError = job.lastError,
                previewText = job.payloadText,
                createdAt = job.createdAt,
                updatedAt = job.updatedAt,
            )
        }
        return (saleJobs + documentJobs).sortedWith(
            compareByDescending<UnifiedPrintJob> { it.createdAt }.thenByDescending { it.sourceId },
        )
    }

    fun queryPrinterStatus(configuration: PrinterConfiguration = loadConfiguration()): Result<PrinterRealtimeStatus> =
        TcpPrinterStatusClient(configuration).query()

    fun retry(job: UnifiedPrintJob) {
        when (job.type) {
            UnifiedPrintJobType.SALE_RECEIPT -> salesDatabase.retryPrintJob(job.sourceId)
            UnifiedPrintJobType.REVERSAL_RECEIPT,
            UnifiedPrintJobType.SETTLEMENT_REPORT,
            -> documentStore.retryDocumentPrint(job.sourceId)
        }
    }

    fun print(job: UnifiedPrintJob, requireHealthyPrinter: Boolean): Result<String> {
        val configuration = loadConfiguration()
        require(configuration.usable) { "有効なプリンター接続設定がありません" }
        if (requireHealthyPrinter) {
            val status = queryPrinterStatus(configuration).getOrElse { error ->
                return Result.failure(
                    IllegalStateException(
                        "安全印刷前の状態確認に失敗しました：${error.message ?: error.javaClass.simpleName}",
                        error,
                    ),
                )
            }
            if (!PrinterPreflightPolicy.mayPrint(status)) {
                return Result.failure(IllegalStateException(PrinterPreflightPolicy.rejectionMessage(status)))
            }
        }
        PrinterConfigurationRegistry.reload(applicationContext)
        val gateway = TcpEscPosPrinterGateway(
            host = configuration.host,
            port = configuration.port,
            timeoutMillis = configuration.timeoutMillis,
        )
        return when (job.type) {
            UnifiedPrintJobType.SALE_RECEIPT -> printSaleJob(job, configuration, gateway)
            UnifiedPrintJobType.REVERSAL_RECEIPT,
            UnifiedPrintJobType.SETTLEMENT_REPORT,
            -> documentStore.processDocumentPrint(job.sourceId, gateway).map {
                "${job.type.displayName}を送信しました（Job.${job.sourceId}）"
            }
        }
    }

    private fun printSaleJob(
        unifiedJob: UnifiedPrintJob,
        configuration: PrinterConfiguration,
        gateway: PrinterGateway,
    ): Result<String> {
        val sourceJob = salesDatabase.listPrintJobs(500).firstOrNull { it.id == unifiedJob.sourceId }
            ?: return Result.failure(IllegalArgumentException("売上印刷ジョブが見つかりません"))
        val detail = salesDatabase.loadSaleDetail(sourceJob.saleId)
            ?: return Result.failure(IllegalArgumentException("売上データが見つかりません"))
        salesDatabase.markPrintStarted(sourceJob.id)
        val receipt = ReceiptFactory.fromSale(detail, reprint = detail.summary.printCount > 0)
        val payload = EscPosEncoder.encode(
            data = receipt,
            paper = ReceiptPaper.fromWidth(sourceJob.paperWidthMm),
            configuration = configuration,
        )
        val result = gateway.send(payload)
        result.onSuccess {
            salesDatabase.markPrintCompleted(sourceJob.id)
        }.onFailure { error ->
            val manualConfirmation = PrinterRetrySafety.classify(error) ==
                PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED
            salesDatabase.markPrintFailed(
                sourceJob.id,
                error.message ?: error.javaClass.simpleName,
                permanent = manualConfirmation,
            )
        }
        return result.map { "売上レシートを送信しました（Job.${sourceJob.id}）" }
    }
}
