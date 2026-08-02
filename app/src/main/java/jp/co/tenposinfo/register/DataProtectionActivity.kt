package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
    val manager = remember { DataProtectionManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DataProtectionReport?>(null) }
    var backups by remember { mutableStateOf<List<BackupRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(manager.pendingRestoreStatus()) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("診断を実行してください") }
    var busy by remember { mutableStateOf(false) }

    fun runTask(task: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            message = runCatching { task() }.getOrElse { "エラー: ${it.message}" }
            backups = withContext(Dispatchers.IO) { manager.listBackups() }
            pending = manager.pendingRestoreStatus()
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        backups = withContext(Dispatchers.IO) { manager.listBackups() }
        report = withContext(Dispatchers.IO) { manager.diagnose() }
        message = if (report?.healthy == true) "DB整合性は正常です" else "DB整合性エラーを確認してください"
    }

    Surface(Modifier.fillMaxSize(), color = DpBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(72.dp).background(DpNavy).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SCR-767", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(18.dp))
                Text("データ保全・バックアップ・復元", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f)); Text("v${BuildConfig.VERSION_NAME}", color = Color.White)
            }
            Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Card(Modifier.width(470.dp).fillMaxHeight(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("整合性診断", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DpNavy)
                        Spacer(Modifier.height(8.dp))
                        val current = report
                        Text(when { current == null -> "未診断"; current.healthy -> "正常"; else -> "要確認" }, color = if (current?.healthy == true) DpGreen else DpDanger, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        if (current != null) {
                            Text("SQLite: ${if (current.sqliteIntegrityOk) "OK" else "NG"} / 外部キー違反 ${current.foreignKeyViolationCount}件")
                            Text("テーブル ${current.tableCounts.size}件 / 診断 ${formatTime(current.checkedAt)}")
                            Spacer(Modifier.height(8.dp)); Text("復元前ブロッカー", fontWeight = FontWeight.Bold)
                            val reasons = DataRestorePolicy.reasons(current.restoreBlockers)
                            Text(if (reasons.isEmpty()) "なし" else reasons.joinToString("\n"), color = if (reasons.isEmpty()) DpGreen else DpDanger)
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(Modifier.weight(1f)) {
                                items(current.issues) { issue -> Text("${issue.code}: ${issue.message}${if (issue.count > 0) " (${issue.count})" else ""}", color = if (issue.severity == IntegritySeverity.ERROR) DpDanger else Color.DarkGray, modifier = Modifier.padding(vertical = 3.dp)) }
                            }
                        } else Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { runTask { report = withContext(Dispatchers.IO) { manager.diagnose() }; if (report?.healthy == true) "DB整合性は正常です" else "DB整合性エラーがあります" } }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = DpBlue)) { Text("再診断") }
                            Button(onClick = { runTask { val actor = OperatorSessionRegistry.current(context.applicationContext)?.name ?: "責任者"; val backup = withContext(Dispatchers.IO) { manager.createBackup(actor) }; "バックアップ作成: ${backup.fileName}" } }, enabled = !busy && current?.healthy == true, colors = ButtonDefaults.buttonColors(containerColor = DpGreen)) { Text("バックアップ作成") }
                        }
                    }
                }
                Card(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("バックアップ一覧", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DpNavy); Spacer(Modifier.weight(1f)); Text("${backups.size}件") }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(backups, key = { it.fileName }) { backup ->
                                Column(Modifier.fillMaxWidth().background(if (selected == backup.fileName) DpSelected else Color.Transparent).clickable { selected = backup.fileName }.padding(10.dp)) {
                                    Text(backup.fileName, fontWeight = FontWeight.Bold)
                                    Text("${backup.sizeBytes} bytes / ${formatTime(backup.createdAt)} / ${backup.appVersion ?: "不明"}")
                                    if (!backup.valid) Text(backup.error.orEmpty(), color = DpDanger)
                                }
                            }
                        }
                        if (pending.staged) Text("復元予約済み: ${pending.backupFileName}\nアプリを完全終了して再起動すると適用します。", color = DpDanger, fontWeight = FontWeight.Bold)
                        pending.lastResult?.let { Text(it, color = Color.DarkGray, fontSize = 13.sp) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("復元・取消用 責任者PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { val file = selected ?: return@OutlinedButton; runTask { val verified = withContext(Dispatchers.IO) { manager.verifyBackup(file) }; "検証成功: ${verified.fileName} / SHA-256 ${verified.manifest.databaseSha256.take(12)}…" } }, enabled = !busy && selected != null) { Text("検証") }
                            Button(onClick = { val file = selected ?: return@Button; runTask { val staged = withContext(Dispatchers.IO) { manager.stageRestore(file, pin) }; pin = ""; "復元予約: ${staged.backup.fileName}。アプリを完全終了して再起動してください。" } }, enabled = !busy && selected != null && pin.length >= 4 && report?.restoreReady == true && !pending.staged, colors = ButtonDefaults.buttonColors(containerColor = DpDanger)) { Text("次回起動時に復元") }
                            OutlinedButton(onClick = { runTask { val actor = withContext(Dispatchers.IO) { manager.cancelPendingRestore(pin) }; pin = ""; "復元予約を取り消しました（$actor）" } }, enabled = !busy && pending.staged && pin.length >= 4) { Text("予約取消") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(82.dp).background(Color.White).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = if (message.startsWith("エラー")) DpDanger else DpNavy, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f)); OutlinedButton(onClick = onClose, enabled = !busy, modifier = Modifier.width(220.dp).height(54.dp)) { Text("設定へ戻る") }
            }
        }
    }
}

private fun formatTime(value: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
