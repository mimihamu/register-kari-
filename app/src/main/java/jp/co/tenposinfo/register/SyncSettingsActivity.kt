package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SyNavy = Color(0xFF173F6B)
private val SyBlue = Color(0xFF1976B9)
private val SyDanger = Color(0xFFC62828)
private val SyBackground = Color(0xFFF4F7FA)
private val SyBorder = Color(0xFFD5DEE7)
private val SyPaleGreen = Color(0xFFEAF5EC)
private val SyPaleYellow = Color(0xFFFFF4D9)

class SyncSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                SyncSettingsApp(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun SyncSettingsApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { JournalOutboxStore(context.applicationContext) }
    var refresh by remember { mutableIntStateOf(0) }
    val summary = remember(refresh) { store.summary() }
    val rows = remember(refresh) { store.list(250) }
    val directStatus = remember(refresh) {
        GoogleDriveDirectUploadStatusStore(context.applicationContext).load()
    }
    val initial = remember { DriveSyncSettingsStore.load(context.applicationContext) }
    var automatic by remember { mutableStateOf(initial.automaticStaging) }
    var folderName by remember { mutableStateOf(initial.folderName) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { store.close() } }

    Surface(Modifier.fillMaxSize(), color = SyBackground) {
        Column(Modifier.fillMaxSize()) {
            SyHeader(onClose)
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SySummary("ジャーナル", summary.journalCount, Modifier.weight(1f))
                    SySummary("送信待ち", summary.pendingCount + summary.retryCount, Modifier.weight(1f))
                    SySummary("ステージ済み", summary.stagedCount, Modifier.weight(1f))
                    SySummary("送信済み", summary.sentCount, Modifier.weight(1f))
                    SySummary("失敗", summary.failedCount, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Card(
                        Modifier.width(380.dp).fillMaxHeight(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, SyBorder),
                    ) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Text("Google Drive同期基盤", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SyNavy)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(automatic, { automatic = it })
                                Text("Outboxを1時間ごとに自動処理")
                            }
                            OutlinedTextField(
                                value = folderName,
                                onValueChange = { folderName = it.take(80) },
                                label = { Text("同期ルート名") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    DriveSyncSettingsStore.save(
                                        context.applicationContext,
                                        DriveSyncFoundationSettings(automatic, folderName),
                                    )
                                    DriveOutboxScheduler.ensurePeriodic(context.applicationContext)
                                    GoogleDriveDirectUploadScheduler.ensurePeriodic(context.applicationContext)
                                    message = "同期設定を保存しました"
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SyBlue),
                            ) { Text("設定保存") }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    context.startActivity(Intent(context, GoogleDriveSetupGuideActivity::class.java))
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SyBlue),
                            ) { Text("Google Drive初期設定・アカウント") }
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    val count = store.stagePending(500)
                                    DriveOutboxScheduler.enqueueNow(context.applicationContext)
                                    GoogleDriveDirectUploadScheduler.enqueueNow(context.applicationContext)
                                    refresh++
                                    message = "$count 件をローカルステージへ出力し、Drive APIと互換用送信を要求しました"
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                            ) { Text("今すぐステージ出力・送信") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val count = store.requeueStaged()
                                    DriveOutboxScheduler.enqueueNow(context.applicationContext)
                                    GoogleDriveDirectUploadScheduler.enqueueNow(context.applicationContext)
                                    refresh++
                                    message = "$count 件を再キューしました"
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                            ) { Text("ステージ済みを再キュー") }
                            Spacer(Modifier.height(14.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = SyPaleGreen)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("v0.45 Drive API直接送信", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                    Text(
                                        "Googleアカウントのdrive.file権限を都度取得し、Outboxの売上JSONをDrive APIへ直接送信します。トークンは保存しません。従来のAndroidフォルダ配送は互換用として残します。",
                                        fontSize = 13.sp,
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Text(directStatus.lastMessage, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    context.startActivity(Intent(context, OutboxDeliverySettingsActivity::class.java))
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SyBlue),
                            ) { Text("互換用フォルダ送信設定") }
                            Spacer(Modifier.height(12.dp))
                            Text("ローカル出力先", fontWeight = FontWeight.Bold, color = SyNavy)
                            Text(store.stagingRoot().absolutePath, fontSize = 12.sp, color = Color.DarkGray)
                            if (message != null) {
                                Spacer(Modifier.height(10.dp))
                                Text(message!!, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.weight(1f))
                            Card(colors = CardDefaults.cardColors(containerColor = SyPaleGreen)) {
                                Text(
                                    "販売確定時は、売上・明細・支払・税スナップショット・印刷キュー・ジャーナル・Outboxを同一SQLiteトランザクションで保存します。Driveは同期経路であり、唯一の原本にはしません。",
                                    modifier = Modifier.padding(12.dp),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }

                    Card(
                        Modifier.weight(1f).fillMaxHeight(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, SyBorder),
                    ) {
                        Column(Modifier.fillMaxSize().padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Outbox一覧", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SyNavy)
                                Spacer(Modifier.weight(1f))
                                OutlinedButton(onClick = { refresh++ }) { Text("更新") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth().background(SyBackground).padding(8.dp)) {
                                Text("営業日", Modifier.width(100.dp), fontWeight = FontWeight.Bold)
                                Text("種別", Modifier.width(120.dp), fontWeight = FontWeight.Bold)
                                Text("対象", Modifier.width(80.dp), fontWeight = FontWeight.Bold)
                                Text("状態", Modifier.width(90.dp), fontWeight = FontWeight.Bold)
                                Text("試行", Modifier.width(50.dp), fontWeight = FontWeight.Bold)
                                Text("保存キー", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            }
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(rows, key = { it.id }) { row ->
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .background(statusBackground(row.status), RoundedCornerShape(5.dp))
                                            .padding(horizontal = 8.dp, vertical = 7.dp),
                                    ) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(row.businessDate, Modifier.width(100.dp), fontSize = 12.sp)
                                            Text(row.eventType, Modifier.width(120.dp), fontSize = 12.sp)
                                            Text(row.aggregateId, Modifier.width(80.dp), fontSize = 12.sp)
                                            Text(statusLabel(row.status), Modifier.width(90.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(row.attemptCount.toString(), Modifier.width(50.dp), fontSize = 12.sp, textAlign = TextAlign.Center)
                                            Text(row.objectKey, Modifier.weight(1f), fontSize = 12.sp)
                                        }
                                        if (row.lastError != null) {
                                            Text("${formatEpoch(row.updatedAt)}  ${row.lastError}", color = SyDanger, fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyHeader(onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).background(SyNavy).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(22.dp))
        Text("SCR-760  売上ジャーナル・外部同期基盤", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("営業日 ${BusinessDateResolver.current(LocalContext.current)}", color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onClose, border = BorderStroke(1.dp, Color.White)) { Text("戻る", color = Color.White) }
    }
}

@Composable
private fun SySummary(label: String, value: Int, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, SyBorder)) {
        Column(Modifier.fillMaxWidth().padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.Gray, fontSize = 12.sp)
            Text(value.toString(), color = SyNavy, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun statusLabel(status: SyncOutboxStatus): String = when (status) {
    SyncOutboxStatus.PENDING -> "待機"
    SyncOutboxStatus.PROCESSING -> "処理中"
    SyncOutboxStatus.RETRY -> "再試行"
    SyncOutboxStatus.STAGED -> "ステージ済"
    SyncOutboxStatus.SENT -> "送信済"
    SyncOutboxStatus.FAILED -> "失敗"
}

private fun statusBackground(status: SyncOutboxStatus): Color = when (status) {
    SyncOutboxStatus.STAGED, SyncOutboxStatus.SENT -> SyPaleGreen
    SyncOutboxStatus.RETRY, SyncOutboxStatus.FAILED -> SyPaleYellow
    else -> Color.Transparent
}

private fun formatEpoch(value: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(value))
