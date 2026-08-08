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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SaleReceiptNavy = Color(0xFF173F6B)
private val SaleReceiptBlue = Color(0xFF1976B9)
private val SaleReceiptBackground = Color(0xFFF4F7FA)
private val SaleReceiptBorder = Color(0xFFD5DEE7)
private val SaleReceiptDanger = Color(0xFFC62828)
private val SaleReceiptGreen = Color(0xFF2E7D32)
private val SaleReceiptWarning = Color(0xFFFFF4D9)

/**
 * v0.67 売上No.指定の通常レシート確認・再印字。
 *
 * MainActivityの売上詳細に依存せず、営業日DB検索など別画面から同じ売上No.を
 * 明示指定して開く。売上や税計算は更新せず、再印字要求のみ既存印刷キューへ追加する。
 */
class SaleReceiptReprintActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        val requestedSaleId = SaleReceiptNavigation.requestedSaleId(intent)
        setContent {
            MaterialTheme {
                SaleReceiptReprintRoute(
                    requestedSaleId = requestedSaleId,
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun SaleReceiptReprintRoute(
    requestedSaleId: Long?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val database = remember { RegisterDatabase(appContext) }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmingReprint by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { database.close() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            operator = OperatorSessionRegistry.current(appContext)
        }
    }

    Surface(Modifier.fillMaxSize(), color = SaleReceiptBackground) {
        val current = operator
        when {
            current == null || !current.allows(RegisterPermission.VIEW_SALES) -> {
                SaleReceiptDenied(onClose)
            }
            requestedSaleId == null -> {
                SaleReceiptUnavailable(
                    title = "対象売上が指定されていません",
                    message = "売上一覧または営業日別売上検索から対象売上を指定して開いてください。",
                    onClose = onClose,
                )
            }
            else -> {
                OperatorSessionRegistry.touch(appContext)
                val detail = remember(requestedSaleId, refreshEpoch) {
                    database.loadSaleDetail(requestedSaleId)
                }
                if (detail == null) {
                    SaleReceiptUnavailable(
                        title = "売上No.$requestedSaleId は見つかりません",
                        message = "別売上へ自動で切り替えません。元画面へ戻って対象売上を確認してください。",
                        onClose = onClose,
                    )
                } else {
                    SaleReceiptReprintScreen(
                        detail = detail,
                        paper = PrinterPaperSettingPolicy.currentPaper(appContext),
                        message = message,
                        confirmingReprint = confirmingReprint,
                        onRequestReprint = {
                            confirmingReprint = true
                            message = null
                        },
                        onCancelReprint = {
                            confirmingReprint = false
                            message = null
                        },
                        onConfirmReprint = {
                            runCatching {
                                database.enqueueReprint(detail.summary.id)
                                AutomaticPrintScheduler.enqueueNow(appContext)
                            }.onSuccess {
                                confirmingReprint = false
                                message = "売上No.${detail.summary.id} の再印字をキューへ登録しました"
                                refreshEpoch++
                            }.onFailure { error ->
                                confirmingReprint = false
                                message = error.message ?: "再印字を登録できませんでした"
                            }
                        },
                        onOpenQueue = {
                            context.startActivity(Intent(context, UnifiedPrintQueueActivity::class.java))
                        },
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaleReceiptReprintScreen(
    detail: SaleDetailRecord,
    paper: ReceiptPaper,
    message: String?,
    confirmingReprint: Boolean,
    onRequestReprint: () -> Unit,
    onCancelReprint: () -> Unit,
    onConfirmReprint: () -> Unit,
    onOpenQueue: () -> Unit,
    onClose: () -> Unit,
) {
    val receiptData = ReceiptFactory.fromSale(detail, reprint = detail.summary.printCount > 0)
    val receiptText = ReceiptRenderer.render(receiptData, paper)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).background(SaleReceiptNavy).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            Text("SCR-647  売上指定 レシート確認・再印字", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("売上No.${detail.summary.id} 固定", color = Color.White, fontWeight = FontWeight.Bold)
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, SaleReceiptBorder),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "売上No.${detail.summary.id} / ${paper.widthMm}mm",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = SaleReceiptNavy,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "印字履歴 ${detail.summary.printCount}回",
                            color = Color.Gray,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            receiptText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (paper == ReceiptPaper.MM58) 14.sp else 15.sp,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.width(340.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, SaleReceiptBorder),
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("再印字操作", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SaleReceiptNavy)
                    Spacer(Modifier.height(10.dp))
                    Text("対象売上は売上No.${detail.summary.id}に固定されています。別売上へ自動切替しません。")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "売上・税計算・支払データは変更せず、既存の再印字ジョブだけを追加します。",
                        color = SaleReceiptGreen,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!message.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            message,
                            color = if (message.contains("登録しました")) SaleReceiptGreen else SaleReceiptDanger,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    if (confirmingReprint) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SaleReceiptWarning),
                            border = BorderStroke(1.dp, Color(0xFFFFB300)),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("再印字内容を確認", fontWeight = FontWeight.Bold, color = SaleReceiptNavy)
                                Text("売上No.${detail.summary.id} を1枚、現在の${paper.widthMm}mm設定でキュー登録します。")
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = onConfirmReprint,
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SaleReceiptBlue),
                                ) { Text("再印字を確定") }
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = onCancelReprint, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                                    Text("取消")
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = onRequestReprint,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SaleReceiptBlue),
                        ) { Text("再印字を確認") }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("統合印刷キューを開く")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "印刷FAILEDはここで自動再送しません。紙の状態を確認し、統合印刷キューから安全に操作します。",
                        color = SaleReceiptDanger,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().height(68.dp).background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onClose, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                Text("元画面へ戻る", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "構造化売上データからプレビュー生成 / 再印字はキュー登録のみ",
                color = SaleReceiptGreen,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun SaleReceiptDenied(onClose: () -> Unit) {
    SaleReceiptUnavailable(
        title = "レシート確認を利用できません",
        message = "売上確認権限がないか、ログインセッションが失効しています。",
        onClose = onClose,
    )
}

@Composable
private fun SaleReceiptUnavailable(
    title: String,
    message: String,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = SaleReceiptDanger, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onClose) { Text("戻る") }
    }
}
