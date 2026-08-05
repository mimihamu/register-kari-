package jp.co.tenposinfo.register.plus

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TsuguRegiPlusFolderSyncScreen(
    state: State<ManagementUiState>,
    onImport: (List<Uri>) -> Unit,
    onRefresh: () -> Unit,
    onReportFilterChanged: (SalesReportFilter) -> Unit,
    onRegisterImportFolder: (Uri?) -> Unit,
    onImportRegisteredFolder: (Boolean) -> Unit,
    onClearImportFolder: () -> Unit,
) {
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
        onRegisterImportFolder,
    )
    val current = state.value
    Column(modifier = Modifier.fillMaxSize()) {
        ImportFolderBar(
            folderState = current.importFolder,
            importing = current.importing,
            onChooseFolder = { folderLauncher.launch(null) },
            onImportChanged = { onImportRegisteredFolder(false) },
            onForceRescan = { onImportRegisteredFolder(true) },
            onClearFolder = onClearImportFolder,
        )
        Box(modifier = Modifier.weight(1f)) {
            TsuguRegiPlusMobileScreen(
                state = state,
                onImport = onImport,
                onRefresh = onRefresh,
                onReportFilterChanged = onReportFilterChanged,
            )
        }
    }
}

@Composable
private fun ImportFolderBar(
    folderState: ImportFolderUiState,
    importing: Boolean,
    onChooseFolder: () -> Unit,
    onImportChanged: () -> Unit,
    onForceRescan: () -> Unit,
    onClearFolder: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val registration = folderState.registration
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (registration == null) {
                            "取込フォルダ未登録"
                        } else {
                            registration.displayName
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = folderStatusText(folderState),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (registration == null) {
                    Button(
                        onClick = onChooseFolder,
                        enabled = !importing && !folderState.scanning,
                    ) {
                        Text("登録")
                    }
                } else {
                    Button(
                        onClick = onImportChanged,
                        enabled = !importing && !folderState.scanning,
                    ) {
                        Text(if (folderState.scanning) "確認中…" else "差分取込")
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "閉じる" else "設定")
                    }
                }
            }

            if (expanded && registration != null) {
                FolderSummary(folderState)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onForceRescan,
                        enabled = !importing && !folderState.scanning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("全件再確認")
                    }
                    OutlinedButton(
                        onClick = onChooseFolder,
                        enabled = !importing && !folderState.scanning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("変更")
                    }
                    TextButton(
                        onClick = onClearFolder,
                        enabled = !importing && !folderState.scanning,
                    ) {
                        Text("解除")
                    }
                }
            }

            folderState.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FolderSummary(folderState: ImportFolderUiState) {
    val summary = folderState.lastSummary
    if (summary == null) {
        Text(
            text = "まだフォルダ取込を実行していません。",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "最終確認 ${formatFolderDateTime(summary.scannedAt)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "JSON ${summary.discoveredJsonCount}件／変更 ${summary.changedJsonCount}件／未変更 ${summary.unchangedJsonCount}件",
            style = MaterialTheme.typography.bodySmall,
        )
        if (summary.skippedNonJsonCount > 0 || summary.readErrorCount > 0) {
            Text(
                text = "対象外 ${summary.skippedNonJsonCount}件／読込エラー ${summary.readErrorCount}件",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (summary.forceRescan) {
            Text(
                text = "前回は全件再確認を実行しました。",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun folderStatusText(folderState: ImportFolderUiState): String {
    if (folderState.scanning) return "登録フォルダを確認しています"
    val summary = folderState.lastSummary ?: return "登録すると変更分を一括取込できます"
    return "最終確認 ${formatFolderDateTime(summary.scannedAt)}・変更${summary.changedJsonCount}件"
}

private val folderDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM/dd HH:mm")

private fun formatFolderDateTime(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault())
    .format(folderDateTimeFormatter)
