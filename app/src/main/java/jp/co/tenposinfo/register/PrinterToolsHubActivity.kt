package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PhNavy = Color(0xFF173F6B)
private val PhBlue = Color(0xFF1976B9)
private val PhGreen = Color(0xFF2E7D32)
private val PhOrange = Color(0xFFEF6C00)
private val PhRed = Color(0xFFC62828)
private val PhPurple = Color(0xFF6A4C93)
private val PhIndigo = Color(0xFF3949AB)
private val PhTeal = Color(0xFF00796B)
private val PhBackground = Color(0xFFF4F7FA)
private val PhBorder = Color(0xFFD5DEE7)

class PrinterToolsHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PrinterToolsHubScreen(
                    onOpenDiagnostics = { startActivity(Intent(this, PrinterStatusActivity::class.java)) },
                    onOpenProbe = { startActivity(Intent(this, PrinterStatusLabActivity::class.java)) },
                    onOpenAnalysis = { startActivity(Intent(this, PrinterStatusAnalysisActivity::class.java)) },
                    onOpenNotification = { startActivity(Intent(this, PrinterNotificationSettingsActivity::class.java)) },
                    onOpenSoakTest = { startActivity(Intent(this, PrinterSoakTestActivity::class.java)) },
                    onOpenHistory = { startActivity(Intent(this, PrinterSoakTestHistoryActivity::class.java)) },
                    onOpenQueue = { startActivity(Intent(this, UnifiedPrintQueueActivity::class.java)) },
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun PrinterToolsHubScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onOpenNotification: () -> Unit,
    onOpenSoakTest: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenQueue: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val settingsStore = remember { AdminSettingsStore(context.applicationContext) }
    val resultStore = remember { PrinterSoakTestResultStore(context.applicationContext) }
    val maintenance = remember { PrinterSoakTestMaintenance(context.applicationContext) }
    var unlocked by remember { mutableStateOf(false) }
    var actor by remember { mutableStateOf("責任者") }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            maintenance.close()
            resultStore.close()
            settingsStore.close()
        }
    }

    Surface(Modifier.fillMaxSize(), color = PhBackground) {
        if (!unlocked) {
            Column(Modifier.fillMaxSize()) {
                PrinterHubHeader("プリンター運用", "責任者認証")
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PrinterHubPanel(Modifier.width(540.dp).height(430.dp)) {
                        Text("プリンター運用を開く", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = PhNavy)
                        Spacer(Modifier.height(8.dp))
                        Text("状態診断、条件別RAW採取、応答分析、通知、連続試験、履歴、印刷キューを管理します。", color = Color.DarkGray)
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                            label = { Text("責任者PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (message != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(message.orEmpty(), color = PhRed, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(56.dp)) { Text("閉じる") }
                            Button(
                                onClick = {
                                    val manager = settingsStore.managerNameForPin(pin)
                                    if (manager == null) {
                                        message = "責任者PINが違います"
                                    } else {
                                        actor = manager
                                        pin = ""
                                        message = null
                                        unlocked = true
                                    }
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PhBlue),
                            ) { Text("認証", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        } else {
            val printer = remember(revision) { settingsStore.loadPrinterConfiguration() }
            val capability = remember(revision) { PrinterStatusCapabilityRegistry.forProfile(printer.profile) }
            val notification = remember(revision) { PrinterNotificationPermissionStatus.read(context) }
            val recent = remember(revision) { resultStore.listRecent(10) }
            Column(Modifier.fillMaxSize()) {
                PrinterHubHeader("プリンター運用", "認証：$actor")
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PrinterHubPanel(Modifier.width(390.dp).fillMaxHeight()) {
                        Text("現在の設定", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PhNavy)
                        Spacer(Modifier.height(12.dp))
                        PrinterHubValue("プリンター", printer.name)
                        PrinterHubValue("接続先", if (printer.host.isBlank()) "未設定" else "${printer.host}:${printer.port}")
                        PrinterHubValue("機種", printer.profile.displayName)
                        PrinterHubValue("用紙", "${printer.paperWidthMm}mm")
                        PrinterHubValue("状態方式", capability.implementationName)
                        PrinterHubValue("検証区分", capability.verification.displayName)
                        PrinterHubValue(
                            "通知",
                            when (notification) {
                                PrinterNotificationPermissionState.ENABLED -> "有効"
                                PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> "許可が必要"
                                PrinterNotificationPermissionState.SYSTEM_DISABLED -> "Android設定で無効"
                            },
                        )
                        PrinterHubValue("試験履歴保持", "${maintenance.retentionDays()}日")
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (capability.automaticQueryAllowed) {
                                "状態自動監視と印刷前診断を使用できます。送信開始後の失敗は自動再送しません。"
                            } else {
                                "STAR／汎用の状態取得は未検証です。自動監視には使わず、状態ラボで実機応答を蓄積・比較します。"
                            },
                            color = Color.DarkGray,
                            lineHeight = 22.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { revision++ }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text("状態を更新")
                        }
                    }

                    Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrinterHubAction("状態診断", "解釈済みのオンライン、カバー、用紙、エラー", PhGreen, onOpenDiagnostics, Modifier.weight(1f))
                        PrinterHubAction("状態ラボ", "条件付きRAW採取、履歴、最大4件比較、CSV", PhTeal, onOpenProbe, Modifier.weight(1f))
                        PrinterHubAction("応答分析", "型番別の採取進捗と正常時との差分ビット候補", PhIndigo, onOpenAnalysis, Modifier.weight(1f))
                        PrinterHubAction("管理者通知", "Android通知の許可・端末通知設定", PhBlue, onOpenNotification, Modifier.weight(1f))
                        PrinterHubAction("連続印刷試験", "状態確認付きで1～500回。自動再送なし", PhOrange, onOpenSoakTest, Modifier.weight(1f))
                        PrinterHubAction("試験履歴・CSV", "詳細、保持期間、削除、過去CSV再出力", PhPurple, onOpenHistory, Modifier.weight(1f))
                        PrinterHubAction("統合印刷キュー", "FAILEDを含む全帳票と紙確認後の再印刷", PhRed, onOpenQueue, Modifier.weight(1f))
                    }

                    PrinterHubPanel(Modifier.weight(1f).fillMaxHeight()) {
                        Text("直近の連続印刷試験", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PhNavy)
                        Spacer(Modifier.height(8.dp))
                        if (recent.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("保存された試験結果はありません", color = Color.Gray)
                            }
                        } else {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                recent.forEach { run ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                        colors = CardDefaults.cardColors(containerColor = hubStatusColor(run.status).copy(alpha = 0.07f)),
                                        border = BorderStroke(1.dp, hubStatusColor(run.status)),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("ID ${run.id}", fontWeight = FontWeight.Bold, color = PhNavy)
                                                Spacer(Modifier.weight(1f))
                                                Text(run.status.displayName, color = hubStatusColor(run.status), fontWeight = FontWeight.Bold)
                                            }
                                            Text("${run.completedCount}/${run.totalPlanned}回  ${formatHubTime(run.startedAt)}")
                                            Text(run.summary, color = Color.DarkGray, fontSize = 13.sp, maxLines = 2)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text("履歴の詳細を開く", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onClose, modifier = Modifier.width(220.dp).fillMaxHeight()) { Text("閉じる", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { unlocked = false; actor = "責任者" },
                        modifier = Modifier.width(220.dp).fillMaxHeight(),
                    ) { Text("運用をロック", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.weight(1f))
                    Text("変化ビット候補とCI成功だけでは実機互換性確認完了になりません", color = PhRed, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PrinterHubHeader(title: String, status: String) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(PhNavy).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(status, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun PrinterHubPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PhBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), content = content)
    }
}

@Composable
private fun PrinterHubAction(
    title: String,
    description: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(description, textAlign = TextAlign.Center, fontSize = 9.sp, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun PrinterHubValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

private fun hubStatusColor(status: PrinterSoakTestRunStatus): Color = when (status) {
    PrinterSoakTestRunStatus.RUNNING -> PhBlue
    PrinterSoakTestRunStatus.COMPLETED -> PhGreen
    PrinterSoakTestRunStatus.STOPPED -> PhOrange
    PrinterSoakTestRunStatus.FAILED -> PhRed
}

private fun formatHubTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))
