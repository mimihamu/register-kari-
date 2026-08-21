package jp.co.tenposinfo.register

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * 売上レシートと業務帳票を同じ運用画面で扱うための統合印刷キュー。
 * 用紙幅は各ジョブ作成時のプリンター設定スナップショットであり、画面から変更しない。
 */
enum class UnifiedPrintJobType(val displayName: String) {
    SALE_RECEIPT("売上レシート"),
    HELD_TICKET_PROVISIONAL("仮締め票"),
    REVERSAL_RECEIPT("返品・取消レシート"),
    SETTLEMENT_REPORT("点検・精算票"),
    RECEIPT_VOUCHER("領収書"),
}

enum class UnifiedPrintFailureCategory(
    val displayName: String,
    val operatorGuidance: String,
) {
    NONE("なし", "エラーは記録されていません"),
    CONNECTION("接続不可", "プリンターの電源、LAN、IPアドレス、ポートを確認してください"),
    TIMEOUT("応答待ち超過", "プリンター状態とネットワーク混雑を確認してください"),
    PAPER("用紙", "用紙切れ、紙詰まり、ロール紙の向きを確認してください"),
    COVER("カバー", "プリンターカバーを閉じてください"),
    CUTTER("カッター", "カッター周辺の紙詰まりを確認してください"),
    DELIVERY_UNKNOWN("送信結果不明", "二重印刷防止のため、紙が出ていないことを目視確認してください"),
    INTERRUPTED("処理中断", "アプリ終了前に紙が出ていないことを確認してください"),
    CONFIGURATION("設定不備", "プリンター設定を確認してください"),
    OTHER("その他", "詳細メッセージを確認し、必要に応じて保守担当へ連絡してください"),
}

object UnifiedPrintFailureClassifier {
    fun classify(error: String?): UnifiedPrintFailureCategory {
        val text = error?.trim().orEmpty()
        if (text.isBlank()) return UnifiedPrintFailureCategory.NONE
        val lower = text.lowercase()
        return when {
            text.contains(DocumentPrintFailurePolicy.UNKNOWN_DELIVERY_PREFIX) ||
                lower.contains("unknown delivery") ||
                lower.contains("write_started") ||
                lower.contains("flushed") -> UnifiedPrintFailureCategory.DELIVERY_UNKNOWN

            text.contains(InterruptedPrintRecoveryPolicy.ERROR_MESSAGE) ||
                text.contains("途中で終了") || lower.contains("interrupted") -> UnifiedPrintFailureCategory.INTERRUPTED

            text.contains("用紙") || text.contains("紙切") || text.contains("紙詰") ||
                lower.contains("paper") -> UnifiedPrintFailureCategory.PAPER

            text.contains("カバー") || lower.contains("cover") -> UnifiedPrintFailureCategory.COVER
            text.contains("カッター") || lower.contains("cutter") -> UnifiedPrintFailureCategory.CUTTER
            text.contains("タイムアウト") || text.contains("応答待ち") ||
                lower.contains("timeout") || lower.contains("timed out") -> UnifiedPrintFailureCategory.TIMEOUT

            text.contains("未設定") || text.contains("設定がありません") ||
                lower.contains("configuration") -> UnifiedPrintFailureCategory.CONFIGURATION

            text.contains("接続") || text.contains("ネットワーク") ||
                lower.contains("connection") || lower.contains("refused") ||
                lower.contains("unreachable") || lower.contains("broken pipe") -> UnifiedPrintFailureCategory.CONNECTION

            else -> UnifiedPrintFailureCategory.OTHER
        }
    }
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
) {
    val failureCategory: UnifiedPrintFailureCategory
        get() = UnifiedPrintFailureClassifier.classify(lastError)
}

data class UnifiedPrintQueueSummary(
    val total: Int,
    val pending: Int,
    val retry: Int,
    val failed: Int,
    val completed: Int,
    val printing: Int,
    val discarded: Int,
) {
    val actionRequired: Int get() = failed + retry
    val active: Int get() = pending + retry + failed + printing

    companion object {
        fun from(jobs: List<UnifiedPrintJob>) = UnifiedPrintQueueSummary(
            total = jobs.size,
            pending = jobs.count { it.status == PrintJobStatus.PENDING },
            retry = jobs.count { it.status == PrintJobStatus.RETRY },
            failed = jobs.count { it.status == PrintJobStatus.FAILED },
            completed = jobs.count { it.status == PrintJobStatus.COMPLETED },
            printing = jobs.count { it.status == PrintJobStatus.PRINTING },
            discarded = jobs.count { it.status == PrintJobStatus.DISCARDED },
        )
    }
}

enum class UnifiedPrintStatusFilter(val displayName: String) {
    ACTIVE("未完了"),
    ACTION_REQUIRED("要対応"),
    ALL("すべて"),
    COMPLETED("完了"),
    DISCARDED("破棄済み"),
}

enum class UnifiedPrintTypeFilter(val displayName: String) {
    ALL("全種別"),
    SALE("売上"),
    REVERSAL("返品・取消"),
    SETTLEMENT("点検・精算"),
    HELD_TICKET("仮締め票"),
    RECEIPT("領収書"),
}

enum class UnifiedPrintTimeFilter(val displayName: String) {
    ALL("全期間"),
    TODAY("本日"),
    LAST_7_DAYS("7日以内"),
    LAST_30_DAYS("30日以内"),
}

enum class UnifiedPrintAttemptFilter(val displayName: String) {
    ALL("全試行回数"),
    ZERO("未試行"),
    ONE_OR_TWO("1～2回"),
    THREE_OR_MORE("3回以上"),
}

data class UnifiedPrintQueueCriteria(
    val status: UnifiedPrintStatusFilter = UnifiedPrintStatusFilter.ACTIVE,
    val type: UnifiedPrintTypeFilter = UnifiedPrintTypeFilter.ALL,
    val time: UnifiedPrintTimeFilter = UnifiedPrintTimeFilter.ALL,
    val attempts: UnifiedPrintAttemptFilter = UnifiedPrintAttemptFilter.ALL,
    val query: String = "",
)

object UnifiedPrintQueueFilterPolicy {
    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

    fun filter(
        jobs: List<UnifiedPrintJob>,
        criteria: UnifiedPrintQueueCriteria,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<UnifiedPrintJob> = jobs.filter { matches(it, criteria, nowMillis) }

    fun matches(
        job: UnifiedPrintJob,
        criteria: UnifiedPrintQueueCriteria,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val statusMatches = when (criteria.status) {
            UnifiedPrintStatusFilter.ACTIVE -> job.status in setOf(
                PrintJobStatus.PENDING,
                PrintJobStatus.RETRY,
                PrintJobStatus.FAILED,
                PrintJobStatus.PRINTING,
            )
            UnifiedPrintStatusFilter.ACTION_REQUIRED -> job.status in setOf(
                PrintJobStatus.RETRY,
                PrintJobStatus.FAILED,
            )
            UnifiedPrintStatusFilter.ALL -> true
            UnifiedPrintStatusFilter.COMPLETED -> job.status == PrintJobStatus.COMPLETED
            UnifiedPrintStatusFilter.DISCARDED -> job.status == PrintJobStatus.DISCARDED
        }
        if (!statusMatches) return false

        val typeMatches = when (criteria.type) {
            UnifiedPrintTypeFilter.ALL -> true
            UnifiedPrintTypeFilter.SALE -> job.type == UnifiedPrintJobType.SALE_RECEIPT
            UnifiedPrintTypeFilter.REVERSAL -> job.type == UnifiedPrintJobType.REVERSAL_RECEIPT
            UnifiedPrintTypeFilter.SETTLEMENT -> job.type == UnifiedPrintJobType.SETTLEMENT_REPORT
            UnifiedPrintTypeFilter.HELD_TICKET -> job.type == UnifiedPrintJobType.HELD_TICKET_PROVISIONAL
            UnifiedPrintTypeFilter.RECEIPT -> job.type == UnifiedPrintJobType.RECEIPT_VOUCHER
        }
        if (!typeMatches) return false

        val earliest = when (criteria.time) {
            UnifiedPrintTimeFilter.ALL -> Long.MIN_VALUE
            UnifiedPrintTimeFilter.TODAY -> Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            UnifiedPrintTimeFilter.LAST_7_DAYS -> nowMillis - 7L * DAY_MILLIS
            UnifiedPrintTimeFilter.LAST_30_DAYS -> nowMillis - 30L * DAY_MILLIS
        }
        if (job.createdAt < earliest) return false

        val attemptsMatch = when (criteria.attempts) {
            UnifiedPrintAttemptFilter.ALL -> true
            UnifiedPrintAttemptFilter.ZERO -> job.attemptCount == 0
            UnifiedPrintAttemptFilter.ONE_OR_TWO -> job.attemptCount in 1..2
            UnifiedPrintAttemptFilter.THREE_OR_MORE -> job.attemptCount >= 3
        }
        if (!attemptsMatch) return false

        val cleanQuery = criteria.query.trim()
        if (cleanQuery.isEmpty()) return true
        val haystack = buildString {
            append(job.type.displayName).append(' ')
            append(job.status.name).append(' ')
            append(job.sourceId).append(' ')
            append(job.referenceId).append(' ')
            append(job.failureCategory.displayName).append(' ')
            append(job.lastError.orEmpty()).append(' ')
            append(job.previewText)
        }
        return haystack.contains(cleanQuery, ignoreCase = true)
    }
}

object UnifiedPrintJobActionPolicy {
    fun mayRetry(status: PrintJobStatus): Boolean = status in setOf(
        PrintJobStatus.PENDING,
        PrintJobStatus.RETRY,
        PrintJobStatus.FAILED,
    )

    fun mayPrint(status: PrintJobStatus): Boolean = mayRetry(status)

    fun mayDiscard(status: PrintJobStatus): Boolean = status in setOf(
        PrintJobStatus.PENDING,
        PrintJobStatus.RETRY,
        PrintJobStatus.FAILED,
    )
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
    private val monitoringStore = PrinterMonitoringStore(applicationContext)

    override fun close() {
        monitoringStore.close()
        settingsStore.close()
        documentStore.close()
        salesDatabase.close()
    }

    fun loadConfiguration(): PrinterConfiguration = settingsStore.loadPrinterConfiguration()

    fun loadRuntimeSettings(): PrinterRuntimeSettings = monitoringStore.loadSettings()

    fun loadJobs(limitPerType: Int = 500): List<UnifiedPrintJob> {
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
                    OperationDocumentType.HELD_TICKET_PROVISIONAL -> UnifiedPrintJobType.HELD_TICKET_PROVISIONAL
                    OperationDocumentType.SETTLEMENT_REPORT -> UnifiedPrintJobType.SETTLEMENT_REPORT
                    OperationDocumentType.RECEIPT_VOUCHER -> UnifiedPrintJobType.RECEIPT_VOUCHER
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
            compareByDescending<UnifiedPrintJob> { actionPriority(it.status) }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.sourceId },
        )
    }

    fun queryPrinterStatus(
        configuration: PrinterConfiguration = loadConfiguration(),
        checkedBy: String = "印刷キュー",
        purpose: PrinterStatusCheckPurpose = PrinterStatusCheckPurpose.MANUAL_DIAGNOSTIC,
        experimentalConfirmed: Boolean = false,
    ): Result<PrinterRealtimeStatus> {
        val result = TcpPrinterStatusClient(configuration).query(
            purpose = purpose,
            experimentalConfirmed = experimentalConfirmed,
        )
        result.onSuccess { monitoringStore.recordStatus(configuration, it, checkedBy) }
            .onFailure { monitoringStore.recordFailure(configuration, it, checkedBy) }
        return result
    }

    fun retry(job: UnifiedPrintJob, actor: String): Result<String> = runCatching {
        require(UnifiedPrintJobActionPolicy.mayRetry(job.status)) {
            when (job.status) {
                PrintJobStatus.COMPLETED -> "完了済みジョブは再送できません。再印字を登録してください"
                PrintJobStatus.DISCARDED -> "破棄済みジョブは再送できません"
                PrintJobStatus.PRINTING -> "印刷中のジョブは操作できません"
                else -> "このジョブは再送できません"
            }
        }
        when (job.type) {
            UnifiedPrintJobType.SALE_RECEIPT -> salesDatabase.retryPrintJob(job.sourceId)
            UnifiedPrintJobType.REVERSAL_RECEIPT,
            UnifiedPrintJobType.HELD_TICKET_PROVISIONAL,
            UnifiedPrintJobType.SETTLEMENT_REPORT,
            UnifiedPrintJobType.RECEIPT_VOUCHER,
            -> documentStore.retryDocumentPrint(job.sourceId)
        }
        settingsStore.recordOperationalAudit(
            eventType = "PRINT_JOB_RETRY_REQUESTED",
            referenceId = job.sourceId,
            detail = auditDetail(job, "再試行待ちへ戻した"),
            actor = actor,
        )
        "再試行待ちへ戻しました（Job.${job.sourceId}）"
    }

    fun discard(
        job: UnifiedPrintJob,
        managerPin: String,
        reason: String,
        actor: String,
    ): Result<String> = runCatching {
        require(UnifiedPrintJobActionPolicy.mayDiscard(job.status)) {
            when (job.status) {
                PrintJobStatus.COMPLETED -> "完了済みジョブは破棄できません"
                PrintJobStatus.DISCARDED -> "このジョブは既に破棄済みです"
                PrintJobStatus.PRINTING -> "印刷中のジョブは破棄できません"
                else -> "このジョブは破棄できません"
            }
        }
        require(reason.trim().length >= 4) { "破棄理由を4文字以上で入力してください" }
        val managerName = settingsStore.managerNameForPin(managerPin)
            ?: throw IllegalArgumentException("責任者PINが違います")
        val detail = auditDetail(job, "破棄理由=${reason.trim()} / 承認責任者=$managerName")
        when (job.type) {
            UnifiedPrintJobType.SALE_RECEIPT -> salesDatabase.discardPrintJob(
                jobId = job.sourceId,
                reason = reason,
                auditDetail = detail,
                actor = actor,
            )
            UnifiedPrintJobType.REVERSAL_RECEIPT,
            UnifiedPrintJobType.HELD_TICKET_PROVISIONAL,
            UnifiedPrintJobType.SETTLEMENT_REPORT,
            UnifiedPrintJobType.RECEIPT_VOUCHER,
            -> documentStore.discardDocumentPrint(
                jobId = job.sourceId,
                reason = reason,
                auditDetail = detail,
                actor = actor,
            )
        }
        "印刷ジョブを破棄しました（Job.${job.sourceId} / 承認：$managerName）"
    }

    fun print(
        job: UnifiedPrintJob,
        requireHealthyPrinter: Boolean,
        actor: String,
        managerPin: String = "",
    ): Result<String> {
        if (!UnifiedPrintJobActionPolicy.mayPrint(job.status)) {
            return Result.failure(IllegalStateException(printabilityError(job.status)))
        }
        val managerName = if (requireHealthyPrinter) null else {
            settingsStore.managerNameForPin(managerPin)
                ?: return Result.failure(IllegalArgumentException("強制印刷には正しい責任者PINが必要です"))
        }
        val configuration = loadConfiguration()
        if (!configuration.usable) {
            return auditedPrintFailure(
                job = job,
                actor = actor,
                forced = !requireHealthyPrinter,
                managerName = managerName,
                error = IllegalStateException("有効なプリンター接続設定がありません"),
            )
        }
        if (requireHealthyPrinter) {
            val status = queryPrinterStatus(
                configuration = configuration,
                checkedBy = "安全印刷",
                purpose = PrinterStatusCheckPurpose.SAFE_PRINT,
            ).getOrElse { error ->
                return auditedPrintFailure(
                    job = job,
                    actor = actor,
                    forced = false,
                    managerName = null,
                    error = IllegalStateException(
                        "安全印刷前の状態確認に失敗しました：${error.message ?: error.javaClass.simpleName}",
                        error,
                    ),
                )
            }
            if (!PrinterPreflightPolicy.mayPrint(status)) {
                return auditedPrintFailure(
                    job = job,
                    actor = actor,
                    forced = false,
                    managerName = null,
                    error = IllegalStateException(PrinterPreflightPolicy.rejectionMessage(status)),
                )
            }
        }
        PrinterConfigurationRegistry.reload(applicationContext)
        val result = runCatching {
            PrinterEndpointSendGate.withPermit(
                host = configuration.host,
                port = configuration.port,
                waitMillis = configuration.timeoutMillis.toLong(),
            ) {
                val gateway = TcpEscPosPrinterGateway(
                    host = configuration.host,
                    port = configuration.port,
                    timeoutMillis = configuration.timeoutMillis,
                )
                when (job.type) {
                    UnifiedPrintJobType.SALE_RECEIPT ->
                        printSaleJob(job, configuration, gateway).getOrThrow()

                    UnifiedPrintJobType.REVERSAL_RECEIPT,
                    UnifiedPrintJobType.HELD_TICKET_PROVISIONAL,
                    UnifiedPrintJobType.SETTLEMENT_REPORT,
                    UnifiedPrintJobType.RECEIPT_VOUCHER,
                    -> {
                        val current = documentStore.loadDocumentPrintJob(job.sourceId)
                            ?: throw IllegalArgumentException("業務帳票の印刷ジョブが見つかりません")
                        requireCurrentStatus(job.status, current.status)
                        if (!UnifiedPrintJobActionPolicy.mayPrint(current.status)) {
                            throw IllegalStateException(printabilityError(current.status))
                        }
                        documentStore.processDocumentPrint(job.sourceId, gateway).getOrThrow()
                        "${job.type.displayName}を送信しました（Job.${job.sourceId}）"
                    }
                }
            }
        }
        result.onSuccess {
            runCatching {
                settingsStore.recordOperationalAudit(
                    eventType = if (requireHealthyPrinter) "PRINT_JOB_MANUAL_SEND_SUCCEEDED" else "PRINT_JOB_FORCE_SEND_SUCCEEDED",
                    referenceId = job.sourceId,
                    detail = auditDetail(job, if (managerName == null) "安全印刷" else "強制印刷 / 承認責任者=$managerName"),
                    actor = actor,
                )
            }
        }.onFailure { error ->
            runCatching {
                settingsStore.recordOperationalAudit(
                    eventType = if (requireHealthyPrinter) "PRINT_JOB_MANUAL_SEND_FAILED" else "PRINT_JOB_FORCE_SEND_FAILED",
                    referenceId = job.sourceId,
                    detail = auditDetail(
                        job,
                        "${if (managerName == null) "安全印刷" else "強制印刷 / 承認責任者=$managerName"} / ${error.message ?: error.javaClass.simpleName}",
                    ),
                    actor = actor,
                )
            }
        }
        return result
    }

    private fun auditedPrintFailure(
        job: UnifiedPrintJob,
        actor: String,
        forced: Boolean,
        managerName: String?,
        error: Throwable,
    ): Result<String> {
        runCatching {
            settingsStore.recordOperationalAudit(
                eventType = if (forced) "PRINT_JOB_FORCE_SEND_FAILED" else "PRINT_JOB_MANUAL_SEND_FAILED",
                referenceId = job.sourceId,
                detail = auditDetail(
                    job,
                    "${if (forced) "強制印刷 / 承認責任者=${managerName.orEmpty()}" else "安全印刷"} / ${error.message ?: error.javaClass.simpleName}",
                ),
                actor = actor,
            )
        }
        return Result.failure(error)
    }

    private fun printSaleJob(
        unifiedJob: UnifiedPrintJob,
        configuration: PrinterConfiguration,
        gateway: PrinterGateway,
    ): Result<String> {
        val sourceJob = salesDatabase.loadPrintJob(unifiedJob.sourceId)
            ?: return Result.failure(IllegalArgumentException("売上印刷ジョブが見つかりません"))
        runCatching { requireCurrentStatus(unifiedJob.status, sourceJob.status) }
            .onFailure { return Result.failure(it) }
        if (!UnifiedPrintJobActionPolicy.mayPrint(sourceJob.status)) {
            return Result.failure(IllegalStateException(printabilityError(sourceJob.status)))
        }
        val claimed = salesDatabase.claimPrintJob(sourceJob.id)
            ?: return Result.failure(IllegalStateException("印刷ジョブの状態が変更されたため送信を開始できませんでした"))
        val detail = salesDatabase.loadSaleDetail(claimed.saleId)
        if (detail == null) {
            salesDatabase.markPrintFailed(claimed.id, "売上データが見つかりません", permanent = true)
            return Result.failure(IllegalArgumentException("売上データが見つかりません"))
        }
        val receipt = ReceiptFactory.fromSale(detail, reprint = detail.summary.printCount > 0)
        val payload = EscPosEncoder.encode(
            data = receipt,
            configuration = configuration.copy(paperWidthMm = claimed.paperWidthMm),
        )
        val result = gateway.send(payload)
        result.onSuccess {
            salesDatabase.markPrintCompleted(claimed.id)
        }.onFailure { error ->
            val manualConfirmation = PrinterRetrySafety.classify(error) ==
                PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED
            salesDatabase.markPrintFailed(
                claimed.id,
                error.message ?: error.javaClass.simpleName,
                permanent = manualConfirmation,
            )
        }
        return result.map { "売上レシートを送信しました（Job.${claimed.id}）" }
    }

    private fun requireCurrentStatus(expected: PrintJobStatus, current: PrintJobStatus) {
        if (expected != current) {
            throw IllegalStateException(
                "印刷ジョブの状態が変更されました（${expected.name}→${current.name}）。一覧を再読込してから操作してください",
            )
        }
    }

    private fun printabilityError(status: PrintJobStatus): String = when (status) {
        PrintJobStatus.COMPLETED -> "完了済みジョブは送信できません。再印字を登録してください"
        PrintJobStatus.DISCARDED -> "破棄済みジョブは送信できません"
        PrintJobStatus.PRINTING -> "印刷中のジョブは操作できません"
        else -> "このジョブは送信できません"
    }

    private fun auditDetail(job: UnifiedPrintJob, action: String): String =
        "${job.type.displayName} / Job.${job.sourceId} / 参照No.${job.referenceId} / " +
            "状態=${job.status.name} / 試行=${job.attemptCount} / 幅=${job.paperWidthMm}mm / $action"

    private fun actionPriority(status: PrintJobStatus): Int = when (status) {
        PrintJobStatus.FAILED -> 6
        PrintJobStatus.RETRY -> 5
        PrintJobStatus.PRINTING -> 4
        PrintJobStatus.PENDING -> 3
        PrintJobStatus.COMPLETED -> 2
        PrintJobStatus.DISCARDED -> 1
    }
}
