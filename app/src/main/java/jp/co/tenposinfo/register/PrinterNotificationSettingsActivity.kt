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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private val PnNavy = Color(0xFF173F6B)
private val PnBlue = Color(0xFF1976B9)
private val PnGreen = Color(0xFF2E7D32)
private val PnOrange = Color(0xFFEF6C00)
private val PnRed = Color(0xFFC62828)
private val PnBackground = Color(0xFFF4F7FA)

class PrinterNotificationSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PrinterNotificationSettingsScreen(
                    onOpenDiagnostics = {
                        startActivity(Intent(this, PrinterStatusActivity::class.java))
                    },
                    onOpenSoakTest = {
                        startActivity(Intent(this, PrinterSoakTestActivity::class.java))
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun PrinterNotificationSettingsScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenSoakTest: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    val permissionState = remember(revision) {
        PrinterNotificationPermissionStatus.read(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        revision++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    val title: String
    val description: String
    val stateColor: Color
    when (permissionState) {
        PrinterNotificationPermissionState.ENABLED -> {
            title = "管理者通知は有効です"
            description = "プリンター異常が1分継続すると、販売画面の警告に加えてAndroid通知を表示します。"
            stateColor = PnGreen
        }
        PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> {
            title = "通知の許可が必要です"
            description = "Android 13以降では、プリンター継続異常の通知を表示するために通知権限を許可してください。"
            stateColor = PnOrange
        }
        PrinterNotificationPermissionState.SYSTEM_DISABLED -> {
            title = "Android設定で通知が無効です"
            description = "端末のアプリ通知設定から、つぐレジの通知を有効にしてください。画面上の警告は通知が無効でも継続します。"
            stateColor = PnRed
        }
    }

    Surface(Modifier.fillMaxSize(), color = PnBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(PnNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("プリンター通知・試験", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("管理者通知と実機検証", color = Color.White, fontSize = 14.sp)
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Card(
                    modifier = Modifier.width(760.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(2.dp, stateColor),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(title, color = stateColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            description,
                            fontSize = 18.sp,
                            lineHeight = 28.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        when (permissionState) {
                            PrinterNotificationPermissionState.ENABLED -> {
                                Button(
                                    onClick = ::openSystemNotificationSettings,
                                    modifier = Modifier.width(360.dp).height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PnGreen),
                                ) { Text("Androidの通知設定を確認", fontWeight = FontWeight.Bold) }
                            }
                            PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED -> {
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            openSystemNotificationSettings()
                                        }
                                    },
                                    modifier = Modifier.width(360.dp).height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PnBlue),
                                ) { Text("通知を許可する", fontWeight = FontWeight.Bold) }
                            }
                            PrinterNotificationPermissionState.SYSTEM_DISABLED -> {
                                Button(
                                    onClick = ::openSystemNotificationSettings,
                                    modifier = Modifier.width(360.dp).height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PnRed),
                                ) { Text("Androidの通知設定を開く", fontWeight = FontWeight.Bold) }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "通知権限はプリンター状態の管理者通知だけに使用します。売上保存、印刷キュー、FAILEDの再送制御には影響しません。",
                            color = Color.DarkGray,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.width(320.dp).height(52.dp),
                    ) { Text("プリンター診断を開く", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = onOpenSoakTest,
                        modifier = Modifier.width(320.dp).height(52.dp),
                    ) { Text("連続印刷試験を開く", fontWeight = FontWeight.Bold) }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.width(240.dp).fillMaxHeight()) {
                    Text("閉じる", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text("連続印刷試験は実機確認用です。開始前にロール紙と排紙口を確認してください", color = PnRed, fontWeight = FontWeight.Bold)
            }
        }
    }
}
