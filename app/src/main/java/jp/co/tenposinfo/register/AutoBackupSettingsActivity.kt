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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val AbsNavy = Color(0xFF173F6B)
private val AbsBlue = Color(0xFF1976B9)
private val AbsGreen = Color(0xFF2E7D32)
private val AbsOrange = Color(0xFFEF6C00)
private val AbsRed = Color(0xFFC62828)
private val AbsBackground = Color(0xFFF4F7FA)

class AutoBackupSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                AutoBackupSettingsScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun AutoBackupSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { AutoBackupSettingsStore(appContext) }
    val initial = remember { store.load() }
    var periodicEnabled by remember { mutableStateOf(initial.periodicEnabled) }
    var cadence by remember { mutableStateOf(initial.cadence) }
    var preferredHourText by remember { mutableStateOf(initial.preferredHour.toString()) }
    var preferredWeekday by remember { mutableIntStateOf(initial.preferredWeekday) }
    var settlementAutoBackupEnabled by remember { mutableStateOf(initial.settlementAutoBackupEnabled) }
    var zRetentionText by remember { mutableStateOf(initial.zRetentionBusinessDays.toString()) }
    var monthlyRetentionText by remember { mutableStateOf(initial.monthlyRetentionMonths.toString()) }
    var retentionGenerationsText by remember { mutableStateOf(initial.retentionGenerations.toString()) }
    var failureNotificationsEnabled by remember { mutableStateOf(initial.failureNotificationsEnabled) }
    var message by remember { mutableStateOf<String?>(null) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val notificationPermission = remember(permissionRevision) {
        PrinterNotificationPermissionStatus.read(appContext)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissionRevision++
    }

    fun draft(reportError: Boolean): AutoBackupSettings? {
        val hour = preferredHourText.toIntOrNull()
        val zDays = zRetentionText.toIntOrNull()
        val months = monthlyRetentionText.toIntOrNull()
        val retentionGenerations = retentionGenerationsText.toIntOrNull()
        return runCatching {
            AutoBackupSettingsPolicy.validated(
                AutoBackupSettings(
                    periodicEnabled = true,
                    cadence = PeriodicBackupCadence.DAILY,
                    preferredHour = hour ?: error("実行時刻を入力してください"),
                    retentionGenerations = retentionGenerations ?: error("保持世代を入力してください"),
                    zRetentionBusinessDays = zDays ?: error("Z精算保持営業日を入力してください"),
                    monthlyRetentionMonths = months ?: error("定期保持月数を入力してください"),
                    failureNotificationsEnabled = failureNotificationsEnabled,
                    preferredWeekday = preferredWeekday,
                    settlementAutoBackupEnabled = true,
                ),
            )
        }.onFailure { if (reportError) message = it.message }.getOrNull()
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

    val preview = draft(reportError = false)?.takeIf { it.periodicEnabled }?.let {
        AutoBackupSettingsPolicy.nextRunMillis(
            nowMillis = System.currentTimeMillis(),
            preferredHour = it.preferredHour,
            zoneId = ZoneId.systemDefault(),
            cadence = it.cadence,
            preferredWeekday = it.preferredWeekday,
        )
    }

    Surface(Modifier.fillMaxSize(), color = AbsBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(AbsNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("定期バックアップ・通知設定", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("毎日＋Z精算後は常時有効", color = Color.White)
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compact = maxWidth < 980.dp
                val contentModifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(if (compact) 14.dp else 24.dp)
                Column(contentModifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("定期バックアップ", color = AbsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("1日1回の暗号化バックアップは常時有効", color = AbsGreen, fontWeight = FontWeight.Bold)
                            Text("Z精算後バックアップは常時有効", color = AbsGreen, fontWeight = FontWeight.Bold)
                            Text(
                                "端末の空き容量とバッテリー残量が安全な時にWorkManagerが実行します。指定時刻は目安で、端末停止中は次回起動後に遅れて実行されます。",
                                color = Color.DarkGray,
                            )
                            Text(
                                "BKP-001に従い、毎日の定期バックアップとZ精算確定後バックアップは停止できません。指定曜日設定は互換情報として保持しますが、毎日バックアップを置き換えません。",
                                color = Color.DarkGray,
                            )
                            OutlinedTextField(
                                value = preferredHourText,
                                onValueChange = { preferredHourText = it.filter(Char::isDigit).take(2); message = null },
                                label = { Text("実行時刻（0～23時）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "次回予定（目安）：${formatBackupSchedule(preview ?: System.currentTimeMillis())}",
                                color = AbsGreen,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("保存世代", color = AbsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("自動バックアップは7～365世代で保持し、初期値は30世代です。容量閾値を超えた場合は古い成功世代から削除します。手動作成・復元予約中は保護します。")
                            OutlinedTextField(
                                value = retentionGenerationsText,
                                onValueChange = { retentionGenerationsText = it.filter(Char::isDigit).take(3); message = null },
                                label = { Text("バックアップ保持世代（7～365）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("失敗通知", color = AbsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(
                                    checked = failureNotificationsEnabled,
                                    onCheckedChange = { failureNotificationsEnabled = it; message = null },
                                )
                                Text(if (failureNotificationsEnabled) "失敗・容量不足をAndroid通知する" else "失敗通知：OFF", fontWeight = FontWeight.Bold)
                            }
                            val permissionText = when (notificationPermission) {
                                PrinterNotificationPermissionState.ENABLED -> "Android通知：利用可能"
                                PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> "Android通知：権限の許可が必要"
                                PrinterNotificationPermissionState.SYSTEM_DISABLED -> "Android通知：端末設定で無効"
                            }
                            val permissionColor = when (notificationPermission) {
                                PrinterNotificationPermissionState.ENABLED -> AbsGreen
                                PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> AbsOrange
                                PrinterNotificationPermissionState.SYSTEM_DISABLED -> AbsRed
                            }
                            Text(permissionText, color = permissionColor, fontWeight = FontWeight.Bold)
                            when (notificationPermission) {
                                PrinterNotificationPermissionState.ENABLED -> OutlinedButton(onClick = ::openSystemNotificationSettings) {
                                    Text("Androidの通知設定を確認")
                                }
                                PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            openSystemNotificationSettings()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AbsBlue),
                                ) { Text("通知を許可する") }
                                PrinterNotificationPermissionState.SYSTEM_DISABLED -> Button(
                                    onClick = ::openSystemNotificationSettings,
                                    colors = ButtonDefaults.buttonColors(containerColor = AbsRed),
                                ) { Text("Androidの通知設定を開く") }
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("外部自動保存", color = AbsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Google Drive・USB・端末フォルダへ内部バックアップを自動複製します。外部保存に失敗しても内部バックアップとZ精算結果は維持します。")
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(context, ExternalBackupSettingsActivity::class.java)) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                            ) { Text("外部自動保存設定を開く", fontWeight = FontWeight.Bold) }
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
                    Text(it, color = if (it.startsWith("保存")) AbsGreen else AbsRed, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val settings = draft(reportError = true) ?: return@Button
                        val saved = store.save(settings)
                        val schedule = AutoBackupPeriodicScheduler.apply(appContext, replaceExisting = true)
                        val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                        AutoBackupAudit.record(
                            appContext,
                            "DATA_BACKUP_SETTINGS_UPDATED",
                            "periodic=${saved.periodicEnabled} / cadence=${saved.cadence.name} / hour=${saved.preferredHour} / weekday=${saved.preferredWeekday} / settlementAutoBackup=${saved.settlementAutoBackupEnabled} / retention=${saved.retentionGenerations} / zDays=${saved.zRetentionBusinessDays} / months=${saved.monthlyRetentionMonths} / notify=${saved.failureNotificationsEnabled}",
                            actor,
                        )
                        if (!saved.failureNotificationsEnabled) {
                            AutoBackupFailureNotificationCoordinator.clear(appContext)
                        }
                        message = schedule.nextScheduledAt?.let {
                            "保存しました。次回予定 ${formatBackupSchedule(it)}"
                        } ?: "保存しました。毎日バックアップを再登録しました。"
                    },
                    modifier = Modifier.width(260.dp).height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AbsBlue),
                ) { Text("設定を保存", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private fun formatBackupSchedule(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(value))
