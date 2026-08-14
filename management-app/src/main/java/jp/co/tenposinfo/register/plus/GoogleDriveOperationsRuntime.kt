package jp.co.tenposinfo.register.plus

import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

data class GoogleDriveSchedulerSnapshot(
    val periodicStates: List<String> = emptyList(),
    val startupStates: List<String> = emptyList(),
    val manualStates: List<String> = emptyList(),
) {
    val periodicScheduled: Boolean
        get() = periodicStates.any(::isActiveWorkState)

    // v1.21: startup/manual are compatibility labels over the same immediate unique-work chain.
    val startupScheduled: Boolean
        get() = startupStates.any(::isActiveWorkState)

    val manualScheduled: Boolean
        get() = manualStates.any(::isActiveWorkState)

    companion object {
        private fun isActiveWorkState(value: String): Boolean =
            value in setOf("ENQUEUED", "RUNNING", "BLOCKED")
    }
}

object GoogleDriveSchedulerInspector {
    private const val PERIODIC_NAME = "tsuguregi-plus-drive-api-sync-periodic"
    private const val IMMEDIATE_NAME = "tsuguregi-plus-drive-api-sync-immediate"

    fun inspect(context: Context): GoogleDriveSchedulerSnapshot {
        val manager = WorkManager.getInstance(context.applicationContext)
        val immediateStates = states(manager, IMMEDIATE_NAME)
        return GoogleDriveSchedulerSnapshot(
            periodicStates = states(manager, PERIODIC_NAME),
            startupStates = immediateStates,
            manualStates = immediateStates,
        )
    }

    private fun states(manager: WorkManager, name: String): List<String> = runCatching {
        manager.getWorkInfosForUniqueWork(name)
            .get(5, TimeUnit.SECONDS)
            .map { it.state.name }
            .distinct()
    }.getOrDefault(emptyList())
}

object GoogleDriveRejectedRetryPolicy {
    fun canRetry(item: ImportRejectionSummary): Boolean {
        val source = item.sourceUri?.trim().orEmpty()
        return source.startsWith("gdrive://") || source.startsWith("content://")
    }
}

data class GoogleDriveRejectedRetryResult(
    val sourceName: String,
    val importedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val runId: Long,
)

class GoogleDriveRejectedRetryService(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = ManagementDatabase(appContext)
    private val repository = SalesJournalImportRepository(database)

    fun retry(rejectionId: Long): GoogleDriveRejectedRetryResult {
        val rejection = repository.rejection(rejectionId)
            ?: error("対象の隔離データが見つかりません")
        require(GoogleDriveRejectedRetryPolicy.canRetry(rejection)) {
            "この隔離データは元ファイルを安全に再取得できないため個別再試行できません"
        }
        val sourceUri = rejection.sourceUri.orEmpty()
        val bytes = when {
            sourceUri.startsWith("gdrive://") -> {
                val fileId = sourceUri.removePrefix("gdrive://").trim()
                require(fileId.isNotBlank()) { "Drive fileIdが不正です" }
                val token = GoogleDriveSyncAccessTokenProvider.acquire(appContext)
                GoogleDriveSyncRestClient(token).download(fileId)
            }

            sourceUri.startsWith("content://") -> {
                appContext.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { it.readBytes() }
                    ?: throw FileNotFoundException("元ファイルを再読込できません")
            }

            else -> error("再試行可能な元ファイルではありません")
        }
        require(bytes.isNotEmpty()) { "元ファイルが空です" }
        require(bytes.size <= SalesJournalImportContract.MAX_DOCUMENT_BYTES) {
            "元ファイルが20MiBを超えています"
        }
        val result = repository.importDocuments(
            listOf(
                SalesJournalImportDocument(
                    sourceName = rejection.sourceName,
                    sourceUri = sourceUri,
                    rawJson = bytes.toString(Charsets.UTF_8),
                ),
            ),
        )
        return GoogleDriveRejectedRetryResult(
            sourceName = rejection.sourceName,
            importedCount = result.importedCount,
            duplicateCount = result.duplicateCount,
            rejectedCount = result.rejectedCount,
            runId = result.runId,
        )
    }

    override fun close() {
        database.close()
    }
}
