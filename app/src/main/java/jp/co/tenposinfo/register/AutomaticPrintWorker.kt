package jp.co.tenposinfo.register

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object AutomaticPrintPolicy {
    fun shouldRetry(configurationUsable: Boolean, attempted: Int, failures: Int): Boolean =
        configurationUsable && attempted > 0 && failures > 0
}

/**
 * 設定済みTCPプリンターへ、売上レシートと業務帳票の待機ジョブを順番に送信する。
 * プリンター未設定時は成功扱いで終了し、販売処理とキューを止めない。
 */
class AutomaticPrintWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val configuration = AdminSettingsStore(applicationContext).use { it.loadPrinterConfiguration() }
        if (!configuration.usable) return Result.success()

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
            repeat(MAX_JOBS_PER_RUN) {
                if (database.nextPrintableJob() == null) return@repeat
                attempted++
                if (!processor.processNext()) {
                    failures++
                    return@repeat
                }
            }
        } finally {
            database.close()
        }

        val operations = AdvancedOperationsStore(applicationContext)
        try {
            operations.listDocumentPrintJobs(500)
                .asSequence()
                .filter { it.status == PrintJobStatus.PENDING || it.status == PrintJobStatus.RETRY }
                .take(MAX_JOBS_PER_RUN)
                .forEach { job ->
                    attempted++
                    if (operations.processDocumentPrint(job.id, gateway).isFailure) failures++
                }
        } finally {
            operations.close()
        }

        return if (AutomaticPrintPolicy.shouldRetry(configuration.usable, attempted, failures)) {
            Result.retry()
        } else {
            Result.success()
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
