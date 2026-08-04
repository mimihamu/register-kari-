package jp.co.tenposinfo.register

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EbsNavy = Color(0xFF173F6B)
private val EbsBlue = Color(0xFF1976B9)
private val EbsGreen = Color(0xFF2E7D32)
private val EbsOrange = Color(0xFFEF6C00)
private val EbsRed = Color(0xFFC62828)
private val EbsBackground = Color(0xFFF4F7FA)

class ExternalBackupSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                ExternalBackupSettingsScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun ExternalBackupSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { ExternalBackupSettingsStore(appContext) }
    val statusStore = remember { ExternalBackupStatusStore(appContext) }
    val initial = remember { store.load() }
    var persistedTreeUri by remember { mutableStateOf(initial.treeUri) }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var treeUriText by remember { mutableStateOf(initial.treeUri) }
    var destinationLabel by remember { mutableStateOf(initial.destinationLabel) }
    var unmeteredOnly by remember { mutableStateOf(initial.unmeteredNetworkOnly) }
    var failureNotifications by remember { mutableStateOf(initial.failureNotificationsEnabled) }
    var status by remember { mutableStateOf(statusStore.load()) }
    var message by remember { mutableStateOf<String?>(null) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val notificationPermission = remember(permissionRevision) {
        PrinterNotificationPermissionStatus.read(appContext)
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val result = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                require(ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, uri)) {
                    "保存先への永続書込権限を取得できません"
                }
                treeUriText = uri.toString()
                destinationLabel = ExternalBackupDocumentProvider.displayName(appContext, uri)
                enabled = true
                "保存先を選択しました。［設定を保存］で確定してください。"
            }.getOrElse { "保存先エラー: ${it.message}" }
            message = result
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissionRevision++
    }

    fun currentDraft(reportError: Boolean): ExternalBackupSettings? = runCatching {
        ExternalBackupSettingsPolicy.validated(
            ExternalBackupSettings(
                enabled = enabled,
                treeUri = treeUriText,
                destinationLabel = destinationLabel,
                unmeteredNetworkOnly = unmeteredOnly,
                failureNotificationsEnabled = failureNotifications,
            ),
        )
    }.onFailure {
        if (reportError) message = it.message
    }.getOrNull()

    fun openSystemNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    fun clearDestination() {
        enabled = false
        treeUriText = null
        destinationLabel = null
        message = "保存先を解除しました。［設定を保存］で確定してください。"
    }

    fun releasePreviousPermissionAfterSave(saved: ExternalBackupSettings) {
        val previous = persistedTreeUri
        if (previous != null && previous != saved.treeUri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags)
            }
        }
        persistedTreeUri = saved.treeUri
    }

    val destinationUri = treeUriText?.let(Uri::parse)
    val destinationPermission = destinationUri?.let {
        ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, it)
    } == true

    Surface(Modifier.fillMaxSize(), color = EbsBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(EbsNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("外部自動バックアップ設定", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("内部バックアップを正本として維持", color = Color.White)
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compact = maxWidth < 980.dp
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(if (compact) 14.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("保存先", color = EbsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                destinationLabel ?: "未選択",
                                color = if (destinationPermission) EbsGreen else EbsRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text(
                                when {
                                    treeUriText == null -> "Google Drive・USB・端末フォルダから保存先を選択してください。"
                                    destinationPermission -> "永続書込権限：有効"
                                    else -> "永続書込権限：失効。保存先を選び直してください。"
                                },
                                color = if (destinationPermission) EbsGreen else EbsRed,
                            )
                            treeUriText?.let { Text(it, color = Color.DarkGray, fontSize = 12.sp) }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Button(
                                    onClick = { folderLauncher.launch(destinationUri) },
                                    modifier = Modifier.weight(1f).height(50.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = EbsBlue),
                                ) { Text(if (treeUriText == null) "保存先を選択" else "保存先を変更") }
                                OutlinedButton(
                                    onClick = ::clearDestination,
                                    enabled = treeUriText != null,
                                    modifier = Modifier.weight(1f).height(50.dp),
                                ) { Text("保存先を解除") }
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("自動保存", color = EbsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = {
                                        enabled = it
                                        message = null
                                    },
                                )
                                Text(
                                    if (enabled) "外部自動保存：ON" else "外部自動保存：OFF",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                "Z精算後・定期・手動で作成された内部バックアップを検出し、選択した外部フォルダへ複製します。失敗しても内部バックアップ、売上、Z精算結果は変更しません。",
                                color = Color.DarkGray,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(
                                    checked = unmeteredOnly,
                                    onCheckedChange = { unmeteredOnly = it },
                                )
                                Text(
                                    if (unmeteredOnly) "従量課金でないネットワーク接続時のみ実行" else "ネットワーク条件を指定しない",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                "Google Driveを保存先にする場合は、Wi-Fi等で通信可能な状態が必要です。USB・端末フォルダではネットワーク不要です。",
                                color = Color.DarkGray,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("実行状態", color = EbsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "状態：${status.result.displayName}",
                                color = when (status.result) {
                                    ExternalBackupMirrorResultState.SUCCESS,
                                    ExternalBackupMirrorResultState.IDLE -> EbsGreen
                                    ExternalBackupMirrorResultState.RUNNING -> EbsOrange
                                    ExternalBackupMirrorResultState.NEVER -> Color.DarkGray
                                    else -> EbsRed
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text("外部保存待ち：${status.pendingCount}件")
                            Text("最終開始：${status.lastStartedAt?.let(::formatExternalBackupTime) ?: "未実行"}")
                            Text("最終完了：${status.lastCompletedAt?.let(::formatExternalBackupTime) ?: "未実行"}")
                            status.lastFileName?.let { Text("最終ファイル：$it", fontSize = 13.sp) }
                            status.lastError?.let { Text("エラー：$it", color = EbsRed, fontSize = 13.sp) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        val draft = currentDraft(reportError = true) ?: return@Button
                                        val saved = store.save(draft)
                                        releasePreviousPermissionAfterSave(saved)
                                        ExternalBackupScheduler.apply(appContext, replaceExisting = true)
                                        if (saved.enabled && saved.treeUri != null) {
                                            message = "設定を保存し、外部自動保存を要求しました。"
                                        }
                                    },
                                    enabled = enabled && destinationPermission,
                                    colors = ButtonDefaults.buttonColors(containerColor = EbsGreen),
                                ) { Text("今すぐ外部保存") }
                                OutlinedButton(
                                    onClick = {
                                        status = statusStore.load()
                                        message = "実行状態を更新しました。"
                                    },
                                ) { Text("状態更新") }
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("失敗通知", color = EbsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(
                                    checked = failureNotifications,
                                    onCheckedChange = { failureNotifications = it },
                                )
                                Text(
                                    if (failureNotifications) "外部保存失敗・権限失効を通知する" else "外部保存失敗通知：OFF",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            val permissionText = when (notificationPermission) {
                                PrinterNotificationPermissionState.ENABLED -> "Android通知：利用可能"
                                PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> "Android通知：権限の許可が必要"
                                PrinterNotificationPermissionState.SYSTEM_DISABLED -> "Android通知：端末設定で無効"
                            }
                            Text(
                                permissionText,
                                color = when (notificationPermission) {
                                    PrinterNotificationPermissionState.ENABLED -> EbsGreen
                                    PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> EbsOrange
                                    PrinterNotificationPermissionState.SYSTEM_DISABLED -> EbsRed
                                },
                                fontWeight = FontWeight.Bold,
                            )
                            when (notificationPermission) {
                                PrinterNotificationPermissionState.ENABLED -> OutlinedButton(
                                    onClick = ::openSystemNotificationSettings,
                                ) { Text("Androidの通知設定を確認") }
                                PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            openSystemNotificationSettings()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = EbsBlue),
                                ) { Text("通知を許可する") }
                                PrinterNotificationPermissionState.SYSTEM_DISABLED -> Button(
                                    onClick = ::openSystemNotificationSettings,
                                    colors = ButtonDefaults.buttonColors(containerColor = EbsRed),
                                ) { Text("Androidの通知設定を開く") }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(76.dp).background(Color.White)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                ) { Text("戻る", fontWeight = FontWeight.Bold) }
                message?.let {
                    Spacer(Modifier.width(18.dp))
                    Text(
                        it,
                        color = if (it.startsWith("保存") || it.contains("要求") || it.contains("更新")) EbsGreen else EbsRed,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val saved = currentDraft(reportError = true)?.let(store::save) ?: return@Button
                        releasePreviousPermissionAfterSave(saved)
                        ExternalBackupScheduler.apply(appContext, replaceExisting = true)
                        if (!saved.enabled) {
                            ExternalBackupFailureNotificationCoordinator.clear(appContext)
                        }
                        val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                        AutoBackupAudit.record(
                            appContext,
                            "DATA_BACKUP_EXTERNAL_SETTINGS_UPDATED",
                            "enabled=${saved.enabled} / destination=${saved.destinationLabel.orEmpty()} / unmetered=${saved.unmeteredNetworkOnly} / notify=${saved.failureNotificationsEnabled}",
                            actor,
                        )
                        status = statusStore.load()
                        message = if (saved.enabled) {
                            "保存しました。外部バックアップを確認します。"
                        } else {
                            "保存しました。外部自動保存はOFFです。"
                        }
                    },
                    modifier = Modifier.width(260.dp).height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EbsBlue),
                ) { Text("設定を保存", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private fun formatExternalBackupTime(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
