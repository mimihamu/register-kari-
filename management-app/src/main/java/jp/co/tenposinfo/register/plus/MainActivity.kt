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
    val importFolder: ImportFolderUiState = ImportFolderUiState(),
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

private data class FolderImportOperation(
    val scan: ImportFolderScanResult,
    val batch: ImportBatchResult?,
)

class MainActivity : ComponentActivity() {
    private val database by lazy { ManagementDatabase(this) }
    private val repository by lazy { SalesJournalImportRepository(database) }
    private val folderRepository by lazy { FolderImportRepository(database) }
    private val folderPreferences by lazy { ImportFolderPreferences(this) }
    private val documentSource by lazy { SalesJournalDocumentSource(contentResolver) }
    private val uiState = mutableStateOf(ManagementUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState.value = uiState.value.copy(
            importFolder = ImportFolderUiState(
                registration = folderPreferences.registration(),
                lastSummary = folderPreferences.lastSummary(),
            ),
        )
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TsuguRegiPlusFolderSyncScreen(
                        state = uiState,
                        onImport = ::importUris,
                        onRefresh = ::refresh,
                        onReportFilterChanged = ::changeReportFilter,
                        onRegisterImportFolder = ::registerImportFolder,
                        onImportRegisteredFolder = ::importRegisteredFolder,
                        onClearImportFolder = ::clearImportFolder,
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
                uris.map { uri ->
                    documentSource.readSingle(
                        uri = uri,
                        fallbackName = queryDisplayName(uri)
                            ?: uri.lastPathSegment
                            ?: "名称不明.json",
                    )
                }
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.importDocuments(documents)
                }
            }
            applyImportResult(
                result = result,
                successMessage = { batch ->
                    "取込完了：新規${batch.importedCount}件／重複${batch.duplicateCount}件／隔離${batch.rejectedCount}件"
                },
            )
        }
    }

    private fun registerImportFolder(uri: Uri?) {
        if (uri == null || uiState.value.importing) return
        lifecycleScope.launch {
            uiState.value = uiState.value.copy(
                importFolder = uiState.value.importFolder.copy(
                    scanning = true,
                    errorMessage = null,
                ),
                message = "取込フォルダを登録しています",
            )
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    documentSource.persistFolderPermission(uri)
                    val registration = ImportFolderRegistration(
                        treeUri = uri.toString(),
                        displayName = documentSource.folderDisplayName(uri),
                    )
                    folderPreferences.saveRegistration(registration)
                    registration
                }
            }
            uiState.value = result.fold(
                onSuccess = { registration ->
                    uiState.value.copy(
                        importFolder = ImportFolderUiState(
                            registration = registration,
                            lastSummary = folderPreferences.lastSummary(),
                        ),
                        message = "取込フォルダ「${registration.displayName}」を登録しました",
                    )
                },
                onFailure = { error ->
                    uiState.value.copy(
                        importFolder = uiState.value.importFolder.copy(
                            scanning = false,
                            errorMessage = error.message ?: "フォルダを登録できませんでした",
                        ),
                        message = "取込フォルダを登録できませんでした",
                    )
                },
            )
        }
    }

    private fun clearImportFolder() {
        val registration = uiState.value.importFolder.registration ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                documentSource.releaseFolderPermission(Uri.parse(registration.treeUri))
                folderRepository.clearTreeHistory(registration.treeUri)
                folderPreferences.clearRegistration()
            }
            uiState.value = uiState.value.copy(
                importFolder = ImportFolderUiState(
                    lastSummary = folderPreferences.lastSummary(),
                ),
                message = "取込フォルダの登録を解除しました",
            )
        }
    }

    private fun importRegisteredFolder(forceRescan: Boolean) {
        val registration = uiState.value.importFolder.registration ?: return
        if (uiState.value.importing || uiState.value.importFolder.scanning) return

        lifecycleScope.launch {
            uiState.value = uiState.value.copy(
                importing = true,
                importFolder = uiState.value.importFolder.copy(
                    scanning = true,
                    errorMessage = null,
                ),
                message = if (forceRescan) {
                    "登録フォルダを全件再確認しています"
                } else {
                    "登録フォルダの変更分を確認しています"
                },
            )

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val known = folderRepository.knownFingerprints(registration.treeUri)
                    val scan = documentSource.scanFolder(
                        treeUri = Uri.parse(registration.treeUri),
                        knownFingerprints = known,
                        forceRescan = forceRescan,
                    )
                    val batch = if (scan.documents.isEmpty()) {
                        null
                    } else {
                        repository.importDocuments(scan.documents)
                    }
                    folderRepository.recordProcessedFiles(
                        treeUri = registration.treeUri,
                        files = scan.processedFiles,
                    )
                    folderPreferences.saveLastSummary(scan.summary)
                    FolderImportOperation(scan, batch)
                }
            }

            val currentFilter = uiState.value.reportFilter
            val snapshot = withContext(Dispatchers.IO) {
                buildSnapshot(
                    requestedFilter = currentFilter,
                    chooseLatestBusinessDate = false,
                )
            }
            uiState.value = result.fold(
                onSuccess = { operation ->
                    val batch = operation.batch
                    uiState.value.copy(
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
                        importFolder = uiState.value.importFolder.copy(
                            scanning = false,
                            lastSummary = operation.scan.summary,
                            errorMessage = null,
                        ),
                        lastBatch = batch ?: uiState.value.lastBatch,
                        message = if (batch == null) {
                            "差分はありませんでした（JSON ${operation.scan.summary.discoveredJsonCount}件確認）"
                        } else {
                            "フォルダ取込完了：変更${operation.scan.summary.changedJsonCount}件／新規${batch.importedCount}件／重複${batch.duplicateCount}件／隔離${batch.rejectedCount}件"
                        },
                    )
                },
                onFailure = { error ->
                    uiState.value.copy(
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
                        importFolder = uiState.value.importFolder.copy(
                            scanning = false,
                            errorMessage = error.message ?: "フォルダ取込に失敗しました",
                        ),
                        message = "フォルダ取込に失敗しました：${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private suspend fun applyImportResult(
        result: Result<ImportBatchResult>,
        successMessage: (ImportBatchResult) -> String,
    ) {
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
                onSuccess = successMessage,
                onFailure = { "取込処理に失敗しました：${it.message ?: it.javaClass.simpleName}" },
            ),
        )
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

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}
