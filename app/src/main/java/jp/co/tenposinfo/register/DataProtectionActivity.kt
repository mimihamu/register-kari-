package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DpNavy = Color(0xFF173F6B)
private val DpBlue = Color(0xFF1976B9)
private val DpGreen = Color(0xFF2E7D32)
private val DpDanger = Color(0xFFC62828)
private val DpBackground = Color(0xFFF4F7FA)
private val DpSelected = Color(0xFFEAF3FA)

class DataProtectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DataProtectionScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun DataProtectionScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val manager = remember { DataProtectionManager(appContext) }
    val autoStatusStore = remember { AutoBackupStatusStore(appContext) }
    val autoSettingsStore = remember { AutoBackupSettingsStore(appContext) }
    val metadataStore = remember { AutoBackupMetadataStore(appContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val responsive = rememberRegisterResponsiveMetrics()
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    var report by remember { mutableStateOf<DataProtectionReport?>(null) }
    var backups by remember { mutableStateOf<List<BackupRecord>>(emptyList()) }
    var metadataByFile by remember { mutableStateOf<Map<String, AutoBackupMetadata>>(emptyMap()) }
    var autoStatus by remember { mutableStateOf(autoStatusStore.load()) }
    var autoSettings by remember { mutableStateOf(autoSettingsStore.load()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(manager.pendingRestoreStatus()) }
    var rollbackInventory by remember { mutableStateOf(RestoreRollbackInventoryV086(0, null)) }
    var pin by remember { mutableStateOf("") }
    var backupPassphrase by remember { mutableStateOf("") }
    var backupPassphraseConfirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("診断を実行してください") }
    var busy by remember { mutableStateOf(false) }

    fun runTask(task: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            message = runCatching { task() }.getOrElse { "エラー: ${it.message}" }
            backups = withContext(Dispatchers.IO) { manager.listBackups() }
            metadataByFile = withContext(Dispatchers.IO) { metadataStore.readAll() }
            autoStatus = autoStatusStore.load()
            autoSettings = autoSettingsStore.load()
            pending = manager.pendingRestoreStatus()
            rollbackInventory = withContext(Dispatchers.IO) { RestoreRollbackSafetyV086.inventory(appContext) }
            busy = false
        }
    }

    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val fileName = pendingExport
        pendingExport = null
        if (uri != null && fileName != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val chars = backupPassphrase.toCharArray()
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        manager.exportBackup(fileName, output, actor, chars)
                    } ?: error("保存先を開けません")
                }
                withContext(Dispatchers.IO) { metadataStore.registerExport(result) }
                "外部保存完了: portable暗号化済み / ${result.fileName} / ${result.bytesWritten} bytes"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val chars = backupPassphrase.toCharArray()
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val imported = withContext(Dispatchers.IO) {
                    val record = context.contentResolver.openInputStream(uri)?.use { input ->
                        manager.importBackup(input, actor, chars)
                    } ?: error("取込ファイルを開けません")
                    metadataStore.registerManualBackup(manager.verifyBackup(record.fileName))
                    record
                }
                selected = imported.fileName
                "外部バックアップ取込完了: パスフレーズ復号検証済み / ${imported.fileName}"
            }
        }
    }

    LaunchedEffect(Unit) {
        backups = withContext(Dispatchers.IO) { manager.listBackups() }
        metadataByFile = withContext(Dispatchers.IO) { metadataStore.readAll() }
        rollbackInventory = withContext(Dispatchers.IO) { RestoreRollbackSafetyV086.inventory(appContext) }
        autoStatus = autoStatusStore.load()
        autoSettings = autoSettingsStore.load()
        report = withContext(Dispatchers.IO) { manager.diagnose() }
        message = if (report?.healthy == true) "DB整合性は正常です" else "DB整合性エラーを確認してください"
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autoStatus = autoStatusStore.load()
                autoSettings = autoSettingsStore.load()
                scope.launch {
                    rollbackInventory = withContext(Dispatchers.IO) { RestoreRollbackSafetyV086.inventory(appContext) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val deletionCandidates = remember(backups, metadataByFile, pending, autoSettings) {
        AutoBackupRetentionPolicy.selectDeletionCandidates(
            entries = backups.map { backup ->
                val metadata = metadataByFile[backup.fileName]
                BackupRetentionEntry(
                    fileName = backup.fileName,
                    createdAt = backup.createdAt,
                    valid = backup.valid,
                    reason = metadata?.reason,
                    businessDate = metadata?.businessDate,
                    state = metadata?.state ?: AutoBackupFileState.READY,
                    pendingRestore = pending.backupFileName == backup.fileName,
                )
            },
            zBusinessDays = autoSettings.zRetentionBusinessDays,
            monthlyMonths = autoSettings.monthlyRetentionMonths,
        )
    }

    Surface(Modifier.fillMaxSize(), color = DpBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(responsive.headerHeightDp.dp)
                    .background(DpNavy)
                    .padding(horizontal = responsive.screenPaddingDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("SCR-767", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(if (responsive.isCompact) 10.dp else 18.dp))
                Text(
                    "データ保全・バックアップ・復元",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = if (responsive.isCompact) 18.sp else 25.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text("v${BuildConfig.VERSION_NAME}", color = Color.White, maxLines = 1)
            }
            Row(
                Modifier.weight(1f).padding(responsive.screenPaddingDp.dp),
                horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
            ) {
                Card(
                    Modifier.width(if (responsive.isCompact) 360.dp else 470.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(responsive.cardPaddingDp.dp)
                            .then(if (responsive.isCompact) Modifier.verticalScroll(leftScroll) else Modifier),
                    ) {
                        Text("整合性診断", fontSize = if (responsive.isCompact) 19.sp else 22.sp, fontWeight = FontWeight.Bold, color = DpNavy)
                        Spacer(Modifier.height(8.dp))
                        val current = report
                        Text(
                            when { current == null -> "未診断"; current.healthy -> "正常"; else -> "要確認" },
                            color = if (current?.healthy == true) DpGreen else DpDanger,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (responsive.isCompact) 18.sp else 20.sp,
                        )
                        if (current != null) {
                            Text("SQLite: ${if (current.sqliteIntegrityOk) "OK" else "NG"} / 外部キー違反 ${current.foreignKeyViolationCount}件")
                            Text("テーブル ${current.tableCounts.size}件 / 診断 ${formatTime(current.checkedAt)}")
                            Spacer(Modifier.height(8.dp))
                            Text("復元前ブロッカー", fontWeight = FontWeight.Bold)
                            val reasons = DataRestorePolicy.reasons(current.restoreBlockers)
                            Text(if (reasons.isEmpty()) "なし" else reasons.joinToString("\n"), color = if (reasons.isEmpty()) DpGreen else DpDanger)
                            if (current.issues.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                LazyColumn(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = if (responsive.isCompact) 72.dp else 120.dp),
                                ) {
                                    items(current.issues) { issue ->
                                        Text(
                                            "${issue.code}: ${issue.message}${if (issue.count > 0) " (${issue.count})" else ""}",
                                            color = if (issue.severity == IntegritySeverity.ERROR) DpDanger else Color.DarkGray,
                                            modifier = Modifier.padding(vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(12.dp))
                        }

                        AppUpdateDiagnosticsPanelV090(appContext)

                        Text("自動バックアップ", fontWeight = FontWeight.Bold, color = DpNavy)
                        Text(
                            "Z精算後: 常時有効 / 定期: ${if (autoSettings.periodicEnabled) "${autoSettings.cadence.displayName} ${autoSettings.preferredHour}時台" else "OFF"}",
                            color = DpNavy,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("最終結果: ${autoStatus.lastResult.displayName}", color = if (autoStatus.lastResult == AutoBackupResultState.FAILED || autoStatus.lastResult == AutoBackupResultState.SKIPPED_LOW_STORAGE) DpDanger else DpGreen)
                        Text("最終実行: ${autoStatus.lastCompletedAt?.let(::formatTime) ?: "未実行"}", fontSize = 13.sp)
                        Text("次回定期予定: ${autoStatus.nextScheduledAt?.let(::formatTime) ?: if (autoSettings.periodicEnabled) "再登録待ち" else "OFF"}", fontSize = 13.sp)
                        Text("保持: Z精算 ${autoSettings.zRetentionBusinessDays}営業日 / 定期 ${autoSettings.monthlyRetentionMonths}か月", fontSize = 13.sp)
                        autoStatus.lastReason?.let { Text("作成理由: ${it.displayName}", fontSize = 13.sp) }
                        autoStatus.lastRetentionResult?.let { Text("自動整理: $it", fontSize = 13.sp) }
                        autoStatus.lastError?.let { Text("エラー詳細: $it", color = DpDanger, fontSize = 13.sp) }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    runTask {
                                        report = withContext(Dispatchers.IO) { manager.diagnose() }
                                        if (report?.healthy == true) "DB整合性は正常です" else "DB整合性エラーがあります"
                                    }
                                },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = DpBlue),
                            ) { Text("再診断") }
                            Button(
                                onClick = {
                                    runTask {
                                        val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                                        val backup = withContext(Dispatchers.IO) {
                                            val record = manager.createBackup(actor)
                                            metadataStore.registerManualBackup(manager.verifyBackup(record.fileName))
                                            record
                                        }
                                        "手動バックアップ作成: ${backup.fileName}"
                                    }
                                },
                                enabled = !busy && current?.healthy == true,
                                colors = ButtonDefaults.buttonColors(containerColor = DpGreen),
                            ) { Text("通常バックアップ") }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                runTask {
                                    val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                                    withContext(Dispatchers.IO) { AutoBackupScheduler.enqueueManualNow(appContext, actor) }
                                    "自動バックアップを要求しました。完了結果はこの画面で確認できます。"
                                }
                            },
                            enabled = !busy && current?.healthy == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("自動バックアップを今すぐ実行") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(context, AutoBackupSettingsActivity::class.java)) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("定期・保存世代・失敗通知を設定") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(context, ExternalBackupSettingsActivity::class.java)) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Google Drive・USBへの外部自動保存を設定") }
                    }
                }
                Card(
                    Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(responsive.cardPaddingDp.dp)
                            .then(if (responsive.isCompact) Modifier.verticalScroll(rightScroll) else Modifier),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("バックアップ一覧", fontSize = if (responsive.isCompact) 19.sp else 22.sp, fontWeight = FontWeight.Bold, color = DpNavy)
                            Spacer(Modifier.weight(1f))
                            Text("${backups.size}件")
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            if (responsive.isCompact) {
                                Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 150.dp)
                            } else {
                                Modifier.weight(1f)
                            },
                        ) {
                            items(backups, key = { it.fileName }) { backup ->
                                val metadata = metadataByFile[backup.fileName]
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (selected == backup.fileName) DpSelected else Color.Transparent)
                                        .clickable { selected = backup.fileName }
                                        .padding(10.dp),
                                ) {
                                    Text(backup.fileName, fontWeight = FontWeight.Bold)
                                    Text("${backup.sizeBytes} bytes / ${formatTime(backup.createdAt)} / ${backup.appVersion ?: "不明"}")
                                    Text(
                                        "作成理由: ${metadata?.reason?.displayName ?: "手動・外部取込"} / ${if (backup.fileName in deletionCandidates) "削除候補" else "保持対象"}",
                                        color = if (backup.fileName in deletionCandidates) DpDanger else DpGreen,
                                        fontSize = 13.sp,
                                    )
                                    if (metadata != null) {
                                        Text("営業日: ${metadata.businessDate ?: "なし"} / セッション: ${metadata.businessSessionId ?: "なし"} / Z精算: ${metadata.settlementId ?: "なし"}", fontSize = 13.sp)
                                        Text("外部保存: ${if (metadata.exportedExternally) "済み" else "未保存"} / 最終検証: ${metadata.lastVerifiedAt?.let(::formatTime) ?: "未検証"} / 状態: ${metadata.state.name}", fontSize = 13.sp)
                                    }
                                    if (!backup.valid) Text(backup.error.orEmpty(), color = DpDanger)
                                }
                            }
                        }
                        if (pending.staged) {
                            Text("復元予約済み: ${pending.backupFileName}\nアプリを完全終了して再起動すると適用します。", color = DpDanger, fontWeight = FontWeight.Bold)
                        }
                        pending.lastResult?.let { Text(it, color = Color.DarkGray, fontSize = 13.sp) }
                        Spacer(Modifier.height(8.dp))
                        Text("復元前ロールバック", fontWeight = FontWeight.Bold, color = DpNavy)
                        when {
                            rollbackInventory.count == 0 -> Text("保管なし。まだ復元を適用していない端末では正常です。", color = Color.DarkGray, fontSize = 13.sp)
                            rollbackInventory.latestError != null -> {
                                Text("保管 ${rollbackInventory.count}件 / 最新ロールバック検証NG", color = DpDanger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(rollbackInventory.latestError.orEmpty(), color = DpDanger, fontSize = 12.sp)
                            }
                            rollbackInventory.latest != null -> {
                                val latestRollback = rollbackInventory.latest!!
                                Text("保管 ${rollbackInventory.count}件 / 最新ロールバック検証OK", color = DpGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("${latestRollback.file.name} / ${formatTime(latestRollback.createdAt)}", fontSize = 12.sp)
                                Text("${latestRollback.sizeBytes} bytes / SHA-256 ${latestRollback.sha256.take(16)}…", fontSize = 12.sp)
                                Text("ロールバックDBは自動削除しません。", color = Color.DarkGray, fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                runTask {
                                    val inventory = withContext(Dispatchers.IO) { RestoreRollbackSafetyV086.inventory(appContext) }
                                    rollbackInventory = inventory
                                    when {
                                        inventory.count == 0 -> "復元前ロールバックは保管されていません"
                                        inventory.latestError != null -> "ロールバック検証エラー: ${inventory.latestError}"
                                        else -> "最新ロールバックを再検証しました: ${inventory.latest?.file?.name}"
                                    }
                                }
                            },
                            enabled = !busy,
                        ) { Text("ロールバック再検証") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            backupPassphrase,
                            { backupPassphrase = it },
                            label = { Text("外部バックアップ用パスフレーズ") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            backupPassphraseConfirm,
                            { backupPassphraseConfirm = it },
                            label = { Text("パスフレーズ確認（外部保存時）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            when {
                                backupPassphrase.isEmpty() -> "外部保存・別端末取込にはパスフレーズが必要です。端末内には保存しません。"
                                backupPassphraseConfirm.isNotEmpty() && backupPassphrase != backupPassphraseConfirm -> "確認用パスフレーズが一致しません"
                                else -> "AES-256-GCM / PBKDF2-HMAC-SHA256 210,000回でportable鍵を保護します"
                            },
                            color = if (backupPassphraseConfirm.isNotEmpty() && backupPassphrase != backupPassphraseConfirm) DpDanger else Color.Gray,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val file = selected ?: return@Button
                                    pendingExport = file
                                    exportLauncher.launch(file)
                                },
                                enabled = !busy && selected != null && backupPassphrase.isNotEmpty() && backupPassphrase == backupPassphraseConfirm,
                                colors = ButtonDefaults.buttonColors(containerColor = DpBlue),
                            ) { Text("外部へ暗号化保存") }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed")) },
                                enabled = !busy && !pending.staged && backupPassphrase.isNotEmpty(),
                            ) { Text("外部から取込") }
                            Text(
                                "Google Drive・USB・端末フォルダをSAFで選択できます",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            pin,
                            { pin = it.filter(Char::isDigit).take(8) },
                            label = { Text("復元・取消用 責任者PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val file = selected ?: return@OutlinedButton
                                    runTask {
                                        val preflight = withContext(Dispatchers.IO) { manager.preflightRestore(file) }
                                        preflight.displayText()
                                    }
                                },
                                enabled = !busy && selected != null,
                            ) { Text("検証") }
                            Button(
                                onClick = {
                                    val file = selected ?: return@Button
                                    runTask {
                                        val staged = withContext(Dispatchers.IO) {
                                            RestoreReservationCoordinatorV116.stage(appContext, manager, file, pin)
                                        }
                                        pin = ""
                                        "復元予約: ${staged.backup.fileName}。アプリを完全終了して再起動してください。"
                                    }
                                },
                                enabled = !busy && selected != null && pin.length >= 4 && report?.restoreReady == true && !pending.staged,
                                colors = ButtonDefaults.buttonColors(containerColor = DpDanger),
                            ) { Text("次回起動時に復元") }
                            OutlinedButton(
                                onClick = {
                                    runTask {
                                        val actor = withContext(Dispatchers.IO) {
                                            RestoreReservationCoordinatorV116.cancel(appContext, manager, pin)
                                        }
                                        pin = ""
                                        "復元予約を取り消しました（$actor）"
                                    }
                                },
                                enabled = !busy && pending.staged && pin.length >= 4,
                            ) { Text("予約取消") }
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(responsive.bottomBarHeightDp.dp)
                    .background(Color.White)
                    .padding(horizontal = responsive.screenPaddingDp.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    color = if (message.startsWith("エラー")) DpDanger else DpNavy,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                Spacer(Modifier.width(responsive.panelGapDp.dp))
                OutlinedButton(
                    onClick = onClose,
                    enabled = !busy,
                    modifier = Modifier.width(if (responsive.isCompact) 160.dp else 220.dp).fillMaxHeight(),
                ) { Text("設定へ戻る", maxLines = 1) }
            }
        }
    }
}

private fun formatTime(value: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))