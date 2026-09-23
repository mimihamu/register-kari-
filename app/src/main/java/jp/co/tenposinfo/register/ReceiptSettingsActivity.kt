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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RsNavy = Color(0xFF173F6B)
private val RsBlue = Color(0xFF1976B9)
private val RsBackground = Color(0xFFF4F7FA)
private val RsBorder = Color(0xFFD5DEE7)
private val RsPaleBlue = Color(0xFFEAF3FA)
private val RsGreen = Color(0xFF2E7D32)
private val RsDanger = Color(0xFFC62828)

class ReceiptSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                ReceiptSettingsApp(
                    actor = intent.getStringExtra(EXTRA_ACTOR).orEmpty().ifBlank { "責任者" },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_ACTOR = "jp.co.tenposinfo.register.extra.RECEIPT_SETTINGS_ACTOR"
    }
}

@Composable
private fun ReceiptSettingsApp(
    actor: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { AdminSettingsStore(context.applicationContext) }
    var revision by remember { mutableIntStateOf(0) }
    val printer = remember(revision) { store.loadPrinterConfiguration() }
    var receiptAutoPrint by remember(revision) { mutableStateOf(printer.receiptAutoPrintEnabled) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }

    Surface(Modifier.fillMaxSize(), color = RsBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .background(RsNavy)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(22.dp))
                Text("SCR-640  レシート設定", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("認証：${actor}", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onClose, border = BorderStroke(1.dp, Color.White)) {
                    Text("戻る", color = Color.White)
                }
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Card(
                    modifier = Modifier.width(330.dp).fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, RsBorder),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("レシート基本", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = RsNavy)
                        Spacer(Modifier.height(12.dp))

                        Card(colors = CardDefaults.cardColors(containerColor = RsPaleBlue)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("自動発行", fontWeight = FontWeight.Bold, color = RsNavy)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = receiptAutoPrint,
                                        onCheckedChange = { receiptAutoPrint = it; message = null },
                                    )
                                    Text(if (receiptAutoPrint) "会計確定時に自動発行する" else "自動発行しない")
                                }
                                Text(
                                    "OFFでも会計は確定し、売上一覧・会計完了から後レシートを発行できます。",
                                    fontSize = 12.sp,
                                    color = Color.DarkGray,
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        runCatching {
                                            store.savePrinterConfiguration(
                                                printer.copy(receiptAutoPrintEnabled = receiptAutoPrint),
                                                actor,
                                            )
                                        }.onSuccess {
                                            PrinterConfigurationRegistry.reload(context.applicationContext)
                                            revision++
                                            message = "レシート自動発行設定を保存しました"
                                        }.onFailure {
                                            message = it.message ?: "保存できませんでした"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = RsBlue),
                                ) {
                                    Text("自動発行設定を保存", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("店舗情報・登録番号", fontWeight = FontWeight.Bold, color = RsNavy)
                        Text(
                            "店名、住所、電話、適格請求書登録番号は店舗基本設定から編集します。",
                            fontSize = 12.sp,
                            color = Color.DarkGray,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(context, InitialReleaseSettingsActivityV135::class.java)
                                        .putExtra(InitialReleaseSettingsActivityV135.EXTRA_ACTOR, actor),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("店舗基本設定を開く")
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("現在の印刷条件", fontWeight = FontWeight.Bold, color = RsNavy)
                        Text("用紙幅：${printer.paperWidthMm}mm")
                        Text("印字幅：${printer.printableDotWidth}dot")
                        Text("プリンター：${printer.profile.displayName}")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "用紙幅や接続先の変更は「周辺機器設定」で行います。",
                            fontSize = 12.sp,
                            color = Color.DarkGray,
                        )

                        message?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                it,
                                color = if (it.contains("保存しました")) RsGreen else RsDanger,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, RsBorder),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "文書別・店名スタンプ",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = RsNavy,
                        )
                        Text(
                            "レシート・領収書等の文書別設定、58mm／80mmプレビュー、文字・画像スタンプをまとめて設定します。",
                            color = Color.DarkGray,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        DocumentPrintSettingsPanelV136(receiptAutoPrintEnabled = receiptAutoPrint)
                        ReceiptTextStampSettingsPanelV136()
                        ReceiptStampSettingsPanelV136()
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}
