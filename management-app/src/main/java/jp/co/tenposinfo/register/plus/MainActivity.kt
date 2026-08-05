package jp.co.tenposinfo.register.plus

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class ManagementUiState(
    val loading: Boolean = true,
    val importing: Boolean = false,
    val dashboard: ImportDashboard = ImportDashboard(
        totalImported = 0,
        totalRejected = 0,
        distinctStores = 0,
        latestImportedAt = null,
        eventTypeCounts = emptyMap(),
    ),
    val reportFilterOptions: SalesReportFilterOptions = SalesReportFilterOptions(),
    val reportFilter: SalesReportFilter = SalesReportFilter(),
    val reportFilterInitialized: Boolean = false,
    val salesReport: SalesReport = SalesReport.empty(),
    val recentImports: List<ImportedJournalSummary> = emptyList(),
    val recentRuns: List<ImportRunSummary> = emptyList(),
    val recentRejections: List<ImportRejectionSummary> = emptyList(),
    val lastBatch: ImportBatchResult? = null,
    val message: String? = null,
)

private data class ManagementSnapshot(
    val dashboard: ImportDashboard,
    val reportFilterOptions: SalesReportFilterOptions,
    val reportFilter: SalesReportFilter,
    val salesReport: SalesReport,
    val recentImports: List<ImportedJournalSummary>,
    val recentRuns: List<ImportRunSummary>,
    val recentRejections: List<ImportRejectionSummary>,
)

class MainActivity : ComponentActivity() {
    private val database by lazy { ManagementDatabase(this) }
    private val repository by lazy { SalesJournalImportRepository(database) }
    private val uiState = mutableStateOf(ManagementUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TsuguRegiPlusMobileScreen(
                        state = uiState,
                        onImport = ::importUris,
                        onRefresh = ::refresh,
                        onReportFilterChanged = ::changeReportFilter,
                    )
                }
            }
        }
        refresh()
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }

    private fun refresh() {
        val current = uiState.value
        loadSnapshot(
            requestedFilter = current.reportFilter,
            chooseLatestBusinessDate = !current.reportFilterInitialized,
        )
    }

    private fun changeReportFilter(filter: SalesReportFilter) {
        uiState.value = uiState.value.copy(
            loading = true,
            reportFilter = filter,
            reportFilterInitialized = true,
        )
        loadSnapshot(
            requestedFilter = filter,
            chooseLatestBusinessDate = false,
        )
    }

    private fun loadSnapshot(
        requestedFilter: SalesReportFilter,
        chooseLatestBusinessDate: Boolean,
    ) {
        lifecycleScope.launch {
            uiState.value = uiState.value.copy(loading = true)
            val snapshot = withContext(Dispatchers.IO) {
                buildSnapshot(requestedFilter, chooseLatestBusinessDate)
            }
            uiState.value = uiState.value.copy(
                loading = false,
                dashboard = snapshot.dashboard,
                reportFilterOptions = snapshot.reportFilterOptions,
                reportFilter = snapshot.reportFilter,
                reportFilterInitialized = true,
                salesReport = snapshot.salesReport,
                recentImports = snapshot.recentImports,
                recentRuns = snapshot.recentRuns,
                recentRejections = snapshot.recentRejections,
            )
        }
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || uiState.value.importing) return
        lifecycleScope.launch {
            uiState.value = uiState.value.copy(
                importing = true,
                message = "${uris.size}件を読み込んでいます",
            )
            val documents = withContext(Dispatchers.IO) {
                uris.map(::readDocument)
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.importDocuments(documents)
                }
            }
            val currentFilter = uiState.value.reportFilter
            val snapshot = withContext(Dispatchers.IO) {
                buildSnapshot(
                    requestedFilter = currentFilter,
                    chooseLatestBusinessDate = false,
                )
            }
            uiState.value = uiState.value.copy(
                loading = false,
                importing = false,
                dashboard = snapshot.dashboard,
                reportFilterOptions = snapshot.reportFilterOptions,
                reportFilter = snapshot.reportFilter,
                reportFilterInitialized = true,
                salesReport = snapshot.salesReport,
                recentImports = snapshot.recentImports,
                recentRuns = snapshot.recentRuns,
                recentRejections = snapshot.recentRejections,
                lastBatch = result.getOrNull(),
                message = result.fold(
                    onSuccess = {
                        "取込完了：新規${it.importedCount}件／重複${it.duplicateCount}件／隔離${it.rejectedCount}件"
                    },
                    onFailure = { "取込処理に失敗しました：${it.message ?: it.javaClass.simpleName}" },
                ),
            )
        }
    }

    private fun buildSnapshot(
        requestedFilter: SalesReportFilter,
        chooseLatestBusinessDate: Boolean,
    ): ManagementSnapshot {
        val options = repository.reportFilterOptions()
        val normalized = normalizeFilter(requestedFilter, options)
        val resolvedFilter = if (
            chooseLatestBusinessDate &&
            normalized.businessDate == null &&
            options.businessDates.isNotEmpty()
        ) {
            normalized.copy(businessDate = options.businessDates.first())
        } else {
            normalized
        }
        return ManagementSnapshot(
            dashboard = repository.dashboard(),
            reportFilterOptions = options,
            reportFilter = resolvedFilter,
            salesReport = repository.salesReport(resolvedFilter),
            recentImports = repository.recentImports(),
            recentRuns = repository.recentRuns(),
            recentRejections = repository.recentRejections(),
        )
    }

    private fun normalizeFilter(
        filter: SalesReportFilter,
        options: SalesReportFilterOptions,
    ): SalesReportFilter = filter.copy(
        businessDate = filter.businessDate?.takeIf(options.businessDates::contains),
        storeId = filter.storeId?.takeIf(options.storeIds::contains),
        terminalId = filter.terminalId?.takeIf(options.terminalIds::contains),
    )

    private fun readDocument(uri: Uri): SalesJournalImportDocument {
        val sourceName = queryDisplayName(uri)
            ?: uri.lastPathSegment
            ?: "名称不明.json"
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > SalesJournalImportContract.MAX_DOCUMENT_BYTES) {
                        throw DocumentTooLargeException()
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: error("入力ストリームを開けません")
            SalesJournalImportDocument(
                sourceName = sourceName,
                sourceUri = uri.toString(),
                rawJson = bytes.toString(Charsets.UTF_8),
            )
        } catch (_: DocumentTooLargeException) {
            SalesJournalImportDocument(
                sourceName = sourceName,
                sourceUri = uri.toString(),
                rawJson = null,
                loadErrorCode = ImportRejectionCode.DOCUMENT_TOO_LARGE,
                loadErrorMessage = "JSONファイルが20MiBを超えています",
            )
        } catch (error: Exception) {
            SalesJournalImportDocument(
                sourceName = sourceName,
                sourceUri = uri.toString(),
                rawJson = null,
                loadErrorCode = ImportRejectionCode.READ_ERROR,
                loadErrorMessage = error.message ?: "ファイルを読み込めませんでした",
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private class DocumentTooLargeException : IllegalArgumentException()
}
