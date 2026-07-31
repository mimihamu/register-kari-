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

object AutomaticPrintPolicy {
    fun shouldRetry(configurationUsable: Boolean, attempted: Int, failures: Int): Boolean =
        configurationUsable && attempted > 0 && failures > 0
}

object AutomaticPrinterPreflightPolicy {
    fun mayContinue(enabled: Boolean, status: PrinterRealtimeStatus?): Boolean =
        !enabled || (status != null && PrinterPreflightPolicy.mayPrint(status))
}

/**
 * 設定済みTCPプリンターへ、売上レシートと業務帳票の待機ジョブを順番に送信する。
 * 印刷前状態確認が有効な場合はDLE EOT診断を実行し、OFFLINE／ERROR／通信失敗時は
 * ジョブをPRINTINGへ変更せずにWorkManagerの再試行へ回す。
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

        val preflightAllowed = PrinterMonitoringStore(applicationContext).use { monitoringStore ->
            val runtime = monitoringStore.loadSettings()
            if (!runtime.preflightEnabled) {
                true
            } else {
                val result = TcpPrinterStatusClient(configuration).query()
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

        val gateway = TcpEscPosPrinterGateway(
            host = configuration.host,
            port = configuration.port,
            timeoutMillis = configuration.timeoutMillis,
        )
        var attempted = 0
        var failures = 0

        val database = RegisterDatabase(applicationContext)
        try {
            val processor = PrintQueueProcessor(database, gateway)
            var index = 0
            while (index < MAX_JOBS_PER_RUN && database.nextPrintableJob() != null) {
                attempted++
                if (!processor.processNext()) {
                    failures++
                    break
                }
                index++
            }
        } finally {
            database.close()
        }

        val operations = AdvancedOperationsStore(applicationContext)
        try {
            val jobs = operations.listDocumentPrintJobs(500)
            var processed = 0
            for (job in jobs) {
                if (processed >= MAX_JOBS_PER_RUN) break
                if (job.status != PrintJobStatus.PENDING && job.status != PrintJobStatus.RETRY) continue
                attempted++
                processed++
                if (operations.processDocumentPrint(job.id, gateway).isFailure) failures++
            }
        } finally {
            operations.close()
        }

        return if (AutomaticPrintPolicy.shouldRetry(configuration.usable, attempted, failures)) {
            androidx.work.ListenableWorker.Result.retry()
        } else {
            androidx.work.ListenableWorker.Result.success()
        }
    }

    private companion object {
        const val MAX_JOBS_PER_RUN = 20
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
