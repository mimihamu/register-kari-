package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PerformanceDiagnosticsActivityV136 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            RegisterSafeAreaV141 {
                MaterialTheme {
                    PerformanceDiagnosticsScreenV136(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun PerformanceDiagnosticsScreenV136(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var revision by remember { mutableIntStateOf(0) }
    val snapshot = remember(revision) { PerformanceDiagnosticsV136.capture(context) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F7FA)) {
        Column(Modifier.fillMaxSize()) {
            RegisterTopBarV141(
                title = "性能診断",
                onBack = onClose,
                action = {
                    Button(onClick = { revision++ }) { Text("再計測") }
                },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("現在の端末状態  ·  Android API " + snapshot.sdkInt,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RegisterTopBarColorV141)
                Text(
                    "計測時刻 " + SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(snapshot.capturedAt)) +
                        " / メモリ取得 " + snapshot.memorySource,
                    color = Color.DarkGray,
                )
                DiagnosticCardV136("アプリ Runtime", "使用 " + PerformanceDiagnosticsV136.formatBytes(snapshot.runtimeUsedBytes) + " / 上限 " + PerformanceDiagnosticsV136.formatBytes(snapshot.runtimeMaxBytes))
                DiagnosticCardV136("プロセス PSS", PerformanceDiagnosticsV136.formatBytes(snapshot.processPssBytes))
                DiagnosticCardV136("端末メモリ", "空き " + PerformanceDiagnosticsV136.formatBytes(snapshot.deviceAvailableBytes) + " / 合計 " + PerformanceDiagnosticsV136.formatBytes(snapshot.deviceTotalBytes))
                DiagnosticCardV136("内部ストレージ", "空き " + PerformanceDiagnosticsV136.formatBytes(snapshot.storageAvailableBytes) + " / 合計 " + PerformanceDiagnosticsV136.formatBytes(snapshot.storageTotalBytes))
                Text(
                    "取得できない値は「取得不可」と表示します。Android 12以降はPSSを優先し、取得不能時もRuntime値へフォールバックします。",
                    color = Color.DarkGray,
                )
 
            }
        }
    }
}

@Composable
private fun DiagnosticCardV136(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, modifier = Modifier.weight(0.35f), fontWeight = FontWeight.Bold, color = Color(0xFF173F6B))
            Text(value, modifier = Modifier.weight(0.65f), fontWeight = FontWeight.SemiBold)
        }
    }
}
