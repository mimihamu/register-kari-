package jp.co.tenposinfo.register

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AutomaticPrintPolicy {
    fun shouldRetry(
        configurationUsable: Boolean,
        attempted: Int,
        failures: Int,
        pendingAfterBatch: Boolean = false,
    ): Boolean = configurationUsable && (
        (attempted > 0 && failures > 0) || pendingAfterBatch
    )
}

object AutomaticPrinterPreflightPolicy {
    fun mayContinue(enabled: Boolean, status: PrinterRealtimeStatus?): Boolean =
        !enabled || (status != null && PrinterPreflightPolicy.mayPrint(status))

    fun mayRunStatusQuery(profile: PrinterProfile, enabled: Boolean): Boolean =
        !enabled || PrinterStatusCapabilityRegistry.forProfile(profile).automaticQueryAllowed
}

enum class AutomaticPrintCandidateSource {
    SALE_RECEIPT,
    DOCUMENT,
}

data class AutomaticPrintCandidate(
    val source: AutomaticPrintCandidateSource,
    val sourceId: Long,
    val createdAt: Long,
)

/**
 * 売上レシートと業務帳票を1本の送信順序として扱う。
 * 失敗後に別種別の帳票へ進まず、1回のWorker全体で送信量を制限する。
 */
object AutomaticPrintQueuePolicy {
    const val MAX_JOBS_PER_RUN = 20

    fun oldestCandidate(
        saleJob: PrintJobRecord?,
        documentJobs: List<DocumentPrintJobRecord>,
    ): AutomaticPrintCandidate? {
        val sale = saleJob?.takeIf(::isPrintable)?.let {
            AutomaticPrintCandidate(
                source = AutomaticPrintCandidateSource.SALE_RECEIPT,
                sourceId = it.id,
                createdAt = it.createdAt,
            )
        }
        val document = documentJobs.asSequence()
            .filter(::isPrintable)
            .minWithOrNull(compareBy<DocumentPrintJobRecord> { it.createdAt }.thenBy { it.id })
            ?.let {
                AutomaticPrintCandidate(
                    source = AutomaticPrintCandidateSource.DOCUMENT,
                    sourceId = it.id,
                    createdAt = it.createdAt,
                )
            }
        return listOfNotNull(sale, document)
            .minWithOrNull(
                compareBy<AutomaticPrintCandidate> { it.createdAt }
                    .thenBy { it.source.ordinal }
                    .thenBy { it.sourceId },
            )
    }

    fun shouldStopAfterAttempt(success: Boolean): Boolean = !success

    fun batchLimitReached(attempted: Int): Boolean = attempted >= MAX_JOBS_PER_RUN

    private fun isPrintable(job: PrintJobRecord): Boolean =
        job.status == PrintJobStatus.PENDING || job.status == PrintJobStatus.RETRY

    private fun isPrintable(job: DocumentPrintJobRecord): Boolean =
        job.status == PrintJobStatus.PENDING || job.status == PrintJobStatus.RETRY
}

/**
 * periodicとimmediateのWorkManager要求が重なっても、同一プロセス内では1本だけ送信処理を走らせる。
 * 手動印刷の既存安全確認とは独立した、自動Worker同士の重複実行防止ゲート。
 */
internal object AutomaticPrintWorkerRunGate {
    private val running = AtomicBoolean(false)

    fun tryAcquire(): Boolean = running.compareAndSet(false, true)

    fun release() {
        running.set(false)
    }
}

/**
 * 設定済みTCPプリンターへ、売上レシートと業務帳票の待機ジョブを古い順に送信する。
 * 印刷前状態確認が有効な場合は、メーカー仕様を確認済みの状態方式だけを使用する。
 * 未検証のSTAR／汎用互換方式ではジョブ状態を変更せず再試行待ちとし、
 * 自動印刷前診断を無効にするまで送信を開始しない。
 *
 * 1件でも送信に失敗したrunでは後続ジョブへ進まない。
 * これにより紙の排出結果を確認する前に別帳票が続けて送られることを防ぐ。
 *
 * v0.99では候補選択から状態遷移・TCP送信完了までをendpoint送信ゲート内で実行する。
 * 手動印刷と同じジョブを同時に選択して順番に二重送信する競合も防止する。
 */
class AutomaticPrintWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters,
) : androidx.work.Worker(appContext, params) {
    override fun doWork(): androidx.work.ListenableWorker.Result {
        val settingsStore = AdminSettingsStore(applicationContext)
        val configuration = try {
            settingsStore.loadPrinterConfiguration()
        } finally {
            settingsStore.close()
        }
        if (!configuration.usable) return androidx.work.ListenableWorker.Result.success()

        if (!AutomaticPrintWorkerRunGate.tryAcquire()) {
            return androidx.work.ListenableWorker.Result.retry()
        }
        return try {
            runSingleFlight(configuration)
        } finally {
            AutomaticPrintWorkerRunGate.release()
        }
    }

    private fun runSingleFlight(configuration: PrinterConfiguration): androidx.work.ListenableWorker.Result {
        val preflightAllowed = PrinterMonitoringStore(applicationContext).use { monitoringStore ->
            val runtime = monitoringStore.loadSettings()
            if (!runtime.preflightEnabled) {
                true
            } else if (!AutomaticPrinterPreflightPolicy.mayRunStatusQuery(configuration.profile, enabled = true)) {
                false
            } else {
                val result = TcpPrinterStatusClient(configuration).query(
                    purpose = PrinterStatusCheckPurpose.AUTOMATIC_PREFLIGHT,
                )
                result.fold(
                    onSuccess = { status ->
                        monitoringStore.recordStatus(configuration, status, "自動印刷")
                        AutomaticPrinterPreflightPolicy.mayContinue(true, status)
                    },
                    onFailure = { error ->
                        monitoringStore.recordFailure(configuration, error, "自動印刷")
                        false
                    },
                )
            }
        }
        if (!preflightAllowed) return androidx.work.ListenableWorker.Result.retry()

        var attempted = 0
        var failures = 0
        var pendingAfterBatch = false

        val database = RegisterDatabase(applicationContext)
        val operations = AdvancedOperationsStore(applicationContext)
        try {
            while (!AutomaticPrintQueuePolicy.batchLimitReached(attempted)) {
                val dispatch = runCatching {
                    PrinterEndpointSendGate.withPermit(
                        host = configuration.host,
                        port = configuration.port,
                        waitMillis = configuration.timeoutMillis.toLong(),
                    ) {
                        val candidate = AutomaticPrintQueuePolicy.oldestCandidate(
                            saleJob = database.nextPrintableJob(),
                            documentJobs = operations.listDocumentPrintJobs(500),
                        ) ?: return@withPermit null

                        val gateway = TcpEscPosPrinterGateway(
                            host = configuration.host,
                            port = configuration.port,
                            timeoutMillis = configuration.timeoutMillis,
                        )
                        val success = when (candidate.source) {
                            AutomaticPrintCandidateSource.SALE_RECEIPT ->
                                PrintQueueProcessor(database, gateway).processNext()
                            AutomaticPrintCandidateSource.DOCUMENT ->
                                operations.processDocumentPrint(candidate.sourceId, gateway).isSuccess
                        }
                        candidate to success
                    }
                }.getOrElse {
                    failures++
                    pendingAfterBatch = true
                    null
                }

                if (dispatch == null) break
                attempted++
                if (AutomaticPrintQueuePolicy.shouldStopAfterAttempt(dispatch.second)) {
                    failures++
                    break
                }
            }

            if (AutomaticPrintQueuePolicy.batchLimitReached(attempted) && failures == 0) {
                pendingAfterBatch = AutomaticPrintQueuePolicy.oldestCandidate(
                    saleJob = database.nextPrintableJob(),
                    documentJobs = operations.listDocumentPrintJobs(500),
                ) != null
            }
        } finally {
            operations.close()
            database.close()
        }

        return if (
            AutomaticPrintPolicy.shouldRetry(
                configurationUsable = configuration.usable,
                attempted = attempted,
                failures = failures,
                pendingAfterBatch = pendingAfterBatch,
            )
        ) {
            androidx.work.ListenableWorker.Result.retry()
        } else {
            androidx.work.ListenableWorker.Result.success()
        }
    }
}

object AutomaticPrintScheduler {
    private const val PERIODIC_WORK_NAME = "register-auto-print-periodic"
    private const val IMMEDIATE_WORK_NAME = "register-auto-print-immediate"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<AutomaticPrintWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        enqueueNow(context)
    }

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutomaticPrintWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
