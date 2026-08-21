package jp.co.tenposinfo.register.plus

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier

/**
 * v1.35 PERIOD-001 entry point.
 *
 * MainActivity owns MutableState, so this overload is more specific than the legacy State overload.
 * The legacy folder-sync/mobile screen remains unchanged and is delegated to with a State cast.
 */
@Composable
fun TsuguRegiPlusFolderSyncScreen(
    state: MutableState<ManagementUiState>,
    onImport: (List<Uri>) -> Unit,
    onRefresh: () -> Unit,
    onReportFilterChanged: (SalesReportFilter) -> Unit,
    onRegisterImportFolder: (Uri?) -> Unit,
    onImportRegisteredFolder: (Boolean) -> Unit,
    onClearImportFolder: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SalesReportPeriodFilterBarV135(
            options = state.value.reportFilterOptions,
            filter = state.value.reportFilter,
            onFilterChanged = onReportFilterChanged,
        )
        Box(modifier = Modifier.weight(1f)) {
            TsuguRegiPlusFolderSyncScreen(
                state = state as State<ManagementUiState>,
                onImport = onImport,
                onRefresh = onRefresh,
                onReportFilterChanged = onReportFilterChanged,
                onRegisterImportFolder = onRegisterImportFolder,
                onImportRegisteredFolder = onImportRegisteredFolder,
                onClearImportFolder = onClearImportFolder,
            )
        }
    }
}
