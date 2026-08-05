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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val context = LocalContext.current
    val inspector = remember(context) {
        DriveConnectionInspector(context, context.contentResolver)
    }
    val syncPreferences = remember(context) { DriveSyncPreferences(context) }
    var diagnosisRequest by remember { mutableIntStateOf(0) }
    var connection by remember {
        mutableStateOf(
            DriveConnectionUiState(
                autoImportOnLaunch = syncPreferences.autoImportOnLaunch(),
            ),
        )
    }
    val current = state.value
    val registration = current.importFolder.registration

    LaunchedEffect(registration?.treeUri, diagnosisRequest) {
        val autoImportEnabled = syncPreferences.autoImportOnLaunch()
        if (registration == null) {
            connection = DriveConnectionUiState(
                autoImportOnLaunch = autoImportEnabled,
            )
            return@LaunchedEffect
        }
        connection = connection.copy(
            status = DriveConnectionStatus.CHECKING,
            detail = "登録フォルダの接続を確認しています",
            autoImportOnLaunch = autoImportEnabled,
        )
        val inspected = withContext(Dispatchers.IO) {
            inspector.inspect(registration)
        }.copy(autoImportOnLaunch = autoImportEnabled)
        connection = inspected

        val now = System.currentTimeMillis()
        if (
            DriveConnectionPolicy.shouldAutoImport(
                enabled = autoImportEnabled,
                status = inspected.status,
                lastStartedAt = syncPreferences.lastAutoImportStartedAt(),
                now = now,
            )
        ) {
            syncPreferences.markAutoImportStartedAt(now)
            onImportRegisteredFolder(false)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ImportFolderBar(
            folderState = current.importFolder,
            connection = connection,
            importing = current.importing,
            onChooseFolder = { folderLauncher.launch(null) },
            onImportChanged = { onImportRegisteredFolder(false) },
            onForceRescan = { onImportRegisteredFolder(true) },
            onDiagnose = { diagnosisRequest += 1 },
            onAutoImportChanged = { enabled ->
                syncPreferences.setAutoImportOnLaunch(enabled)
                connection = connection.copy(autoImportOnLaunch = enabled)
                if (enabled) diagnosisRequest += 1
            },
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
    connection: DriveConnectionUiState,
    importing: Boolean,
    onChooseFolder: () -> Unit,
    onImportChanged: () -> Unit,
    onForceRescan: () -> Unit,
    onDiagnose: () -> Unit,
    onAutoImportChanged: (Boolean) -> Unit,
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
                verticalAlignment = Alignment.CenterVertically,
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
                        text = connectionStatusText(connection, folderState),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = connectionStatusColor(connection.status),
                    )
                }
                if (registration == null) {
                    Button(
                        onClick = onChooseFolder,
                        enabled = !importing && !folderState.scanning,
                    ) {
                        Text("Drive／フォルダ登録")
                    }
                } else {
                    Button(
                        onClick = onImportChanged,
                        enabled = !importing &&
                            !folderState.scanning &&
                            connection.status == DriveConnectionStatus.READY,
                    ) {
                        Text(if (folderState.scanning) "確認中…" else "差分取込")
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "閉じる" else "設定")
                    }
                }
            }

            if (expanded && registration != null) {
                ConnectionSummary(connection)
                FolderSummary(folderState)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("起動時に差分取込", fontWeight = FontWeight.SemiBold)
                        Text(
                            "アプリを開いたとき、10分以上空いていれば自動確認します",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(
                        checked = connection.autoImportOnLaunch,
                        onCheckedChange = onAutoImportChanged,
                        enabled = !importing && !folderState.scanning,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDiagnose,
                        enabled = !importing && !folderState.scanning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("接続診断")
                    }
                    OutlinedButton(
                        onClick = onForceRescan,
                        enabled = !importing &&
                            !folderState.scanning &&
                            connection.status == DriveConnectionStatus.READY,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("全件再確認")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onChooseFolder,
                        enabled = !importing && !folderState.scanning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (connection.status == DriveConnectionStatus.READY) "フォルダ変更" else "再接続")
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
private fun ConnectionSummary(connection: DriveConnectionUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = "提供元：${connection.providerName ?: "確認前"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = if (connection.persistedReadPermission) {
                "永続読取権限：有効"
            } else {
                "永続読取権限：無効"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = connection.detail,
            style = MaterialTheme.typography.bodySmall,
            color = connectionStatusColor(connection.status),
        )
        if (connection.isGoogleDrive) {
            Text(
                text = "Google Driveアプリにログインし、Drive内の『つぐレジ』フォルダを選択してください。",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        connection.checkedAt?.let {
            Text(
                text = "診断 ${formatFolderDateTime(it)}",
                style = MaterialTheme.typography.labelSmall,
            )
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

private fun connectionStatusText(
    connection: DriveConnectionUiState,
    folderState: ImportFolderUiState,
): String {
    if (folderState.scanning) return "登録フォルダを確認しています"
    return when (connection.status) {
        DriveConnectionStatus.NOT_REGISTERED -> "Google Driveまたは端末フォルダを登録してください"
        DriveConnectionStatus.CHECKING -> "接続を診断しています"
        DriveConnectionStatus.READY -> {
            val summary = folderState.lastSummary
            if (summary == null) {
                "${connection.providerName ?: "フォルダ"} 接続済み"
            } else {
                "${connection.providerName ?: "フォルダ"} 接続済み・最終${formatFolderDateTime(summary.scannedAt)}"
            }
        }
        DriveConnectionStatus.PERMISSION_MISSING -> "権限が失効しています。再接続してください"
        DriveConnectionStatus.PROVIDER_UNAVAILABLE -> "提供元アプリを利用できません"
        DriveConnectionStatus.READ_FAILED -> "フォルダを読み取れません。接続診断を実行してください"
    }
}

@Composable
private fun connectionStatusColor(status: DriveConnectionStatus) = when (status) {
    DriveConnectionStatus.PERMISSION_MISSING,
    DriveConnectionStatus.PROVIDER_UNAVAILABLE,
    DriveConnectionStatus.READ_FAILED,
    -> MaterialTheme.colorScheme.error

    else -> MaterialTheme.colorScheme.onSecondaryContainer
}

private val folderDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM/dd HH:mm")

private fun formatFolderDateTime(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault())
    .format(folderDateTimeFormatter)
