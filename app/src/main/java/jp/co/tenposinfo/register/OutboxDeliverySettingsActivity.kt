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

private val OdsNavy = Color(0xFF173F6B)
private val OdsBlue = Color(0xFF1976B9)
private val OdsGreen = Color(0xFF2E7D32)
private val OdsOrange = Color(0xFFEF6C00)
private val OdsRed = Color(0xFFC62828)
private val OdsBackground = Color(0xFFF4F7FA)

class OutboxDeliverySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                OutboxDeliverySettingsScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun OutboxDeliverySettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { OutboxDeliverySettingsStore(appContext) }
    val statusStore = remember { OutboxDeliveryStatusStore(appContext) }
    val coordinator = remember { OutboxExternalDeliveryCoordinator(appContext) }
    val initial = remember { store.load() }
    var persistedTreeUri by remember { mutableStateOf(initial.treeUri) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var treeUriText by remember { mutableStateOf(initial.treeUri) }
    var destinationLabel by remember { mutableStateOf(initial.destinationLabel) }
    var unmeteredOnly by remember { mutableStateOf(initial.unmeteredNetworkOnly) }
    var failureNotifications by remember { mutableStateOf(initial.failureNotificationsEnabled) }
    var status by remember { mutableStateOf(statusStore.load()) }
    var counts by remember { mutableStateOf(coordinator.currentCounts()) }
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
            message = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                require(ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, uri)) {
                    "送信先への永続書込権限を取得できません"
                }
                treeUriText = uri.toString()
                destinationLabel = ExternalBackupDocumentProvider.displayName(appContext, uri)
                enabled = true
                "送信先を選択しました。設定保存で確定してください。"
            }.getOrElse { "送信先エラー: ${it.message}" }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissionRevision++
    }

    fun currentDraft(reportError: Boolean): OutboxDeliverySettings? = runCatching {
        OutboxDeliverySettingsPolicy.validated(
            OutboxDeliverySettings(
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

    fun releasePreviousPermissionAfterSave(saved: OutboxDeliverySettings) {
        val previous = persistedTreeUri
        if (previous != null && previous != saved.treeUri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags)
            }
        }
        persistedTreeUri = saved.treeUri
    }

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

    fun refreshState(text: String? = null) {
        status = statusStore.load()
        counts = coordinator.currentCounts()
        if (text != null) message = text
    }

    val destinationUri = treeUriText?.let(Uri::parse)
    val destinationPermission = destinationUri?.let {
        ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, it)
    } == true

    Surface(Modifier.fillMaxSize(), color = OdsBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(OdsNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("売上ジャーナル 外部自動送信", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("SQLiteを正本として維持", color = Color.White)
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compact = maxWidth < 980.dp
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(if (compact) 14.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("送信先", color = OdsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                destinationLabel ?: "未選択",
                                color = if (destinationPermission) OdsGreen else OdsRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text(
                                when {
                                    treeUriText == null -> "Google Drive・USB・端末フォルダから送信先を選択してください。"
                                    destinationPermission -> "永続書込権限：有効"
                                    else -> "永続書込権限：失効。送信先を選び直してください。"
                                },
                                color = if (destinationPermission) OdsGreen else OdsRed,
                            )
                            treeUriText?.let { Text(it, color = Color.DarkGray, fontSize = 12.sp) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { folderLauncher.launch(destinationUri) },
                                    modifier = Modifier.weight(1f).height(50.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = OdsBlue),
                                ) { Text(if (treeUriText == null) "送信先を選択" else "送信先を変更") }
                                OutlinedButton(
                                    onClick = {
                                        enabled = false
                                        treeUriText = null
                                        destinationLabel = null
                                        message = "送信先を解除しました。設定保存で確定してください。"
                                    },
                                    enabled = treeUriText != null,
                                    modifier = Modifier.weight(1f).height(50.dp),
                                ) { Text("送信先を解除") }
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("自動送信", color = OdsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(checked = enabled, onCheckedChange = { enabled = it })
                                Text(if (enabled) "外部自動送信：ON" else "外部自動送信：OFF", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "売上確定後に生成したJSONを、フォルダー名／営業日／種別-番号.jsonの階層で保存します。外部送信に失敗しても売上、印刷、精算は巻き戻しません。",
                                color = Color.DarkGray,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(checked = unmeteredOnly, onCheckedChange = { unmeteredOnly = it })
                                Text(
                                    if (unmeteredOnly) "従量課金でないネットワーク接続時のみ送信" else "ネットワーク条件を指定しない",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                "Google Driveを選ぶ場合はWi-Fi等で同期可能な状態が必要です。USB・端末フォルダではネットワーク条件をOFFにしてください。",
                                color = Color.DarkGray,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("実行状態", color = OdsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "状態：${status.result.displayName}",
                                color = when (status.result) {
                                    OutboxDeliveryResultState.SUCCESS,
                                    OutboxDeliveryResultState.IDLE -> OdsGreen
                                    OutboxDeliveryResultState.RUNNING,
                                    OutboxDeliveryResultState.WAITING_UNMETERED -> OdsOrange
                                    OutboxDeliveryResultState.NEVER -> Color.DarkGray
                                    else -> OdsRed
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text("未送信・処理中：${counts.first}件")
                            Text("手動対応が必要：${counts.second}件")
                            Text("最終開始：${status.lastStartedAt?.let(::formatOutboxDeliveryTime) ?: "未実行"}")
                            Text("最終完了：${status.lastCompletedAt?.let(::formatOutboxDeliveryTime) ?: "未実行"}")
                            status.lastObjectKey?.let { Text("最終送信：$it", fontSize = 13.sp) }
                            status.lastError?.let { Text("エラー：$it", color = OdsRed, fontSize = 13.sp) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        val draft = currentDraft(reportError = true) ?: return@Button
                                        val saved = store.save(draft)
                                        releasePreviousPermissionAfterSave(saved)
                                        DriveOutboxScheduler.enqueueNow(appContext)
                                        refreshState("設定を保存し、送信処理を要求しました。")
                                    },
                                    enabled = enabled && destinationPermission,
                                    colors = ButtonDefaults.buttonColors(containerColor = OdsGreen),
                                ) { Text("今すぐ送信") }
                                OutlinedButton(
                                    onClick = {
                                        val count = coordinator.retryFailed()
                                        DriveOutboxScheduler.enqueueNow(appContext)
                                        refreshState("$count 件の失敗データを再試行へ戻しました。")
                                    },
                                    enabled = counts.second > 0,
                                ) { Text("失敗を再試行") }
                                OutlinedButton(onClick = { refreshState("実行状態を更新しました。") }) {
                                    Text("状態更新")
                                }
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("失敗通知", color = OdsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(checked = failureNotifications, onCheckedChange = { failureNotifications = it })
                                Text(
                                    if (failureNotifications) "送信失敗・権限失効を通知する" else "送信失敗通知：OFF",
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
                                    PrinterNotificationPermissionState.ENABLED -> OdsGreen
                                    PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> OdsOrange
                                    PrinterNotificationPermissionState.SYSTEM_DISABLED -> OdsRed
                                },
                                fontWeight = FontWeight.Bold,
                            )
                            when (notificationPermission) {
                                PrinterNotificationPermissionState.ENABLED -> OutlinedButton(onClick = ::openSystemNotificationSettings) {
                                    Text("Androidの通知設定を確認")
                                }
                                PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            openSystemNotificationSettings()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = OdsBlue),
                                ) { Text("通知を許可する") }
                                PrinterNotificationPermissionState.SYSTEM_DISABLED -> Button(
                                    onClick = ::openSystemNotificationSettings,
                                    colors = ButtonDefaults.buttonColors(containerColor = OdsRed),
                                ) { Text("Androidの通知設定を開く") }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(76.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                    Text("戻る", fontWeight = FontWeight.Bold)
                }
                message?.let {
                    Spacer(Modifier.width(18.dp))
                    Text(it, color = if (it.contains("エラー")) OdsRed else OdsGreen, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val draft = currentDraft(reportError = true) ?: return@Button
                        val saved = store.save(draft)
                        releasePreviousPermissionAfterSave(saved)
                        DriveOutboxScheduler.ensurePeriodic(appContext)
                        if (saved.enabled) DriveOutboxScheduler.enqueueNow(appContext)
                        refreshState("外部自動送信設定を保存しました。")
                    },
                    modifier = Modifier.width(260.dp).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = OdsBlue),
                ) { Text("設定を保存", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private fun formatOutboxDeliveryTime(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
