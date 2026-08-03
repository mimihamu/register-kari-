package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private val BusinessNavyV030 = Color(0xFF173F6B)
private val BusinessBlueV030 = Color(0xFF1976B9)
private val BusinessBackgroundV030 = Color(0xFFF4F7FA)
private val BusinessBorderV030 = Color(0xFFD5DEE7)
private val BusinessDangerV030 = Color(0xFFC62828)
private val BusinessGreenV030 = Color(0xFF2E7D32)
private val BusinessPaleBlueV030 = Color(0xFFEAF3FA)

/**
 * 営業開始ゲートから直接開くv0.30専用画面。
 * 管理メニューを経由せず、画面表示時と保存直前の両方でZ_SETTLEMENTを検証する。
 */
class BusinessStartActivityV030 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                BusinessStartRouteV030(
                    onOpenOperations = {
                        startActivity(Intent(this, OperationsHubActivityV030::class.java))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun BusinessStartRouteV030(
    onOpenOperations: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { OperationsStore(appContext) }
    val secureStore = remember { SecureOperationsCoordinator(appContext, store) }
    var revision by remember { mutableIntStateOf(0) }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }

    val currentOperator = operator
    val allowed = currentOperator != null &&
        OperationsAccessPolicyV030.canOpenBusinessStart(currentOperator.permissions)

    Surface(Modifier.fillMaxSize(), color = BusinessBackgroundV030) {
        if (!allowed || currentOperator == null) {
            BusinessStartDeniedV030(onClose)
            return@Surface
        }

        @Suppress("UNUSED_VARIABLE") val refresh = revision
        BusinessStartScreenV030(
            session = store.activeBusinessSession(),
            history = store.recentBusinessSessions(),
            summary = store.dailySummary(),
            operatorName = currentOperator.name,
            message = message,
            onStart = { businessDate, openingCash ->
                val result = runCatching {
                    secureStore.startBusinessDay(businessDate, openingCash)
                }
                message = result.fold(
                    onSuccess = { "営業を開始しました（No.$it）" },
                    onFailure = { it.message ?: "営業開始に失敗しました" },
                )
                if (result.isSuccess) revision++
                operator = OperatorSessionRegistry.current(appContext)
            },
            onOpenOperations = onOpenOperations,
            onClose = onClose,
        )
    }
}

@Composable
private fun BusinessStartScreenV030(
    session: BusinessSessionRecord?,
    history: List<BusinessSessionRecord>,
    summary: DailyOperationsSummary,
    operatorName: String,
    message: String?,
    onStart: (LocalDate, Long) -> Unit,
    onOpenOperations: () -> Unit,
    onClose: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    var businessDate by remember(session?.businessDate) {
        mutableStateOf(session?.businessDate ?: LocalDate.now().toString())
    }
    var openingCash by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BusinessHeaderV030(metrics)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = metrics.isCompact || maxWidth < 1_050.dp || maxHeight < 560.dp
            if (compact) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(metrics.screenPaddingDp.dp),
                    verticalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    BusinessControlPanelV030(
                        modifier = Modifier.fillMaxWidth(),
                        session = session,
                        operatorName = operatorName,
                        businessDate = businessDate,
                        openingCash = openingCash,
                        message = validationMessage ?: message,
                        onBusinessDateChange = {
                            businessDate = it.take(10)
                            validationMessage = null
                        },
                        onOpeningCashChange = { openingCash = it.filter(Char::isDigit).take(12) },
                        onStart = {
                            val date = runCatching { LocalDate.parse(businessDate) }.getOrNull()
                            if (date == null) {
                                validationMessage = "営業日はYYYY-MM-DD形式で入力してください"
                            } else {
                                validationMessage = null
                                onStart(date, openingCash.toLongOrNull() ?: 0L)
                            }
                        },
                    )
                    BusinessSummaryPanelV030(Modifier.fillMaxWidth(), summary)
                    BusinessHistoryPanelV030(
                        Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        history,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(metrics.screenPaddingDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    BusinessControlPanelV030(
                        modifier = Modifier.weight(1.15f).fillMaxHeight(),
                        session = session,
                        operatorName = operatorName,
                        businessDate = businessDate,
                        openingCash = openingCash,
                        message = validationMessage ?: message,
                        onBusinessDateChange = {
                            businessDate = it.take(10)
                            validationMessage = null
                        },
                        onOpeningCashChange = { openingCash = it.filter(Char::isDigit).take(12) },
                        onStart = {
                            val date = runCatching { LocalDate.parse(businessDate) }.getOrNull()
                            if (date == null) {
                                validationMessage = "営業日はYYYY-MM-DD形式で入力してください"
                            } else {
                                validationMessage = null
                                onStart(date, openingCash.toLongOrNull() ?: 0L)
                            }
                        },
                    )
                    BusinessSummaryPanelV030(Modifier.weight(0.9f).fillMaxHeight(), summary)
                    BusinessHistoryPanelV030(Modifier.weight(1.15f).fillMaxHeight(), history)
                }
            }
        }
        BusinessBottomBarV030(metrics, onOpenOperations, onClose)
    }
}

@Composable
private fun BusinessControlPanelV030(
    modifier: Modifier,
    session: BusinessSessionRecord?,
    operatorName: String,
    businessDate: String,
    openingCash: String,
    message: String?,
    onBusinessDateChange: (String) -> Unit,
    onOpeningCashChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    BusinessPanelV030(modifier) {
        Text("現在の営業セッション", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = BusinessNavyV030)
        Spacer(Modifier.height(10.dp))
        if (session == null) {
            Text("営業開始前です", color = BusinessDangerV030, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = businessDate,
                onValueChange = onBusinessDateChange,
                label = { Text("営業日（YYYY-MM-DD）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = openingCash,
                onValueChange = onOpeningCashChange,
                label = { Text("開始釣銭") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "同じ営業日でも、前の営業セッションがZ精算済みなら新しい営業を開始できます。",
                color = Color.DarkGray,
            )
            Spacer(Modifier.height(10.dp))
            BusinessOperatorV030(operatorName)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BusinessBlueV030),
            ) { Text("営業を開始", fontWeight = FontWeight.Bold) }
        } else {
            BusinessAmountRowV030("営業セッション", "No.${session.id}")
            BusinessAmountRowV030("営業日", session.businessDate)
            BusinessAmountRowV030("状態", session.status.displayName)
            BusinessAmountRowV030("開始釣銭", businessYenV030(session.openingCash))
            BusinessAmountRowV030("開始時刻", businessDateTimeV030(session.openedAt))
            BusinessAmountRowV030("開始担当", session.openedBy)
            Spacer(Modifier.height(14.dp))
            Text(
                "Z精算を実行すると、この営業セッションは精算と同時に終了します。",
                color = BusinessDangerV030,
                fontWeight = FontWeight.Bold,
            )
        }
        if (message != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                color = if (message.contains("開始しました")) BusinessGreenV030 else BusinessDangerV030,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun BusinessSummaryPanelV030(
    modifier: Modifier,
    summary: DailyOperationsSummary,
) {
    BusinessPanelV030(modifier) {
        Text("直近セッション集計", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = BusinessNavyV030)
        Spacer(Modifier.height(10.dp))
        BusinessAmountRowV030("営業セッション", if (summary.businessSessionId > 0) "No.${summary.businessSessionId}" else "未開始")
        BusinessAmountRowV030("営業日", summary.businessDate)
        BusinessAmountRowV030("開始釣銭", businessYenV030(summary.openingCash))
        BusinessAmountRowV030("純売上", businessYenV030(summary.netSales), emphasized = true)
        BusinessAmountRowV030("入金", businessYenV030(summary.cashIn))
        BusinessAmountRowV030("出金", "-${businessYenV030(summary.cashOut)}")
        BusinessAmountRowV030("現金理論残高", businessYenV030(summary.expectedCash), emphasized = true)
        BusinessAmountRowV030("Z精算", if (summary.settled) "済" else "未")
    }
}

@Composable
private fun BusinessHistoryPanelV030(
    modifier: Modifier,
    history: List<BusinessSessionRecord>,
) {
    BusinessPanelV030(modifier) {
        Text("営業セッション履歴", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = BusinessNavyV030)
        Spacer(Modifier.height(8.dp))
        if (history.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                Text("履歴はありません", color = Color.Gray)
            }
        } else {
            history.take(20).forEach { record ->
                Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("${record.businessDate}  No.${record.id}", fontWeight = FontWeight.Bold, color = BusinessNavyV030)
                        Spacer(Modifier.weight(1f))
                        Text(
                            record.status.displayName,
                            color = if (record.status == BusinessSessionStatus.CLOSED) BusinessGreenV030 else BusinessDangerV030,
                        )
                    }
                    Text("開始 ${businessDateTimeV030(record.openedAt)} / ${record.openedBy}", color = Color.Gray)
                    if (record.closedAt != null) {
                        Text("終了 ${businessDateTimeV030(record.closedAt)} / ${record.closedBy.orEmpty()}", color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun BusinessPanelV030(
    modifier: Modifier,
    content: @Composable Column.() -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, BusinessBorderV030),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(metrics.cardPaddingDp.dp),
            content = content,
        )
    }
}

@Composable
private fun BusinessOperatorV030(operatorName: String) {
    Row(
        Modifier.fillMaxWidth().background(BusinessPaleBlueV030, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("操作担当", color = Color.DarkGray)
        Spacer(Modifier.weight(1f))
        Text(operatorName, fontWeight = FontWeight.Bold, color = BusinessNavyV030)
    }
}

@Composable
private fun BusinessAmountRowV030(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (emphasized) BusinessNavyV030 else Color.DarkGray,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            value,
            fontSize = if (emphasized) 20.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) BusinessNavyV030 else Color.Black,
        )
    }
}

@Composable
private fun BusinessHeaderV030(metrics: RegisterResponsiveMetrics) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.headerHeightDp.dp)
            .background(BusinessNavyV030)
            .padding(horizontal = metrics.screenPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = if (metrics.isCompact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(if (metrics.isCompact) 12.dp else 24.dp))
        Text("SCR-490  営業開始・状態", color = Color.White, fontSize = if (metrics.isCompact) 17.sp else 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (!metrics.isCompact) Text("オフライン管理", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun BusinessBottomBarV030(
    metrics: RegisterResponsiveMetrics,
    onOpenOperations: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.bottomBarHeightDp.dp)
            .background(Color.White)
            .padding(horizontal = metrics.screenPaddingDp.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { Text("販売へ戻る", fontWeight = FontWeight.Bold) }
        Button(
            onClick = onOpenOperations,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = ButtonDefaults.buttonColors(containerColor = BusinessNavyV030),
        ) { Text("レジ管理メニューへ", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun BusinessStartDeniedV030(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("営業開始・状態画面を利用できません", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = BusinessDangerV030)
        Spacer(Modifier.height(12.dp))
        Text("Z精算権限がないか、ログインセッションが失効しています。")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("販売画面へ戻る") }
    }
}

private fun businessYenV030(value: Long): String =
    NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)

private fun businessDateTimeV030(epochMillis: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(epochMillis))
