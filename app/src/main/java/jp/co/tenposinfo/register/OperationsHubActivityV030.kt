package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
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
import java.text.NumberFormat
import java.util.Locale

private val HubNavyV030 = Color(0xFF173F6B)
private val HubBlueV030 = Color(0xFF1976B9)
private val HubBackgroundV030 = Color(0xFFF4F7FA)
private val HubBorderV030 = Color(0xFFD5DEE7)
private val HubDangerV030 = Color(0xFFC62828)
private val HubGreenV030 = Color(0xFF2E7D32)
private val HubPaleBlueV030 = Color(0xFFEAF3FA)
private val HubPaleGreenV030 = Color(0xFFEAF5EC)
private val HubPaleYellowV030 = Color(0xFFFFF4D9)

/** v0.30のレスポンシブなレジ管理入口。 */
class OperationsHubActivityV030 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                OperationsHubRouteV030(
                    onClose = { finish() },
                    openBusiness = { startActivity(Intent(this, BusinessStartActivityV030::class.java)) },
                    openXInspection = {
                        startActivity(SettlementActivityV030.intent(this, SettlementReportType.X_INSPECTION))
                    },
                    openZSettlement = {
                        startActivity(SettlementActivityV030.intent(this, SettlementReportType.Z_SETTLEMENT))
                    },
                    openHistory = { startActivity(Intent(this, SettlementHistoryActivityV030::class.java)) },
                    openReceiptVoucher = { startActivity(Intent(this, ReceiptVoucherActivity::class.java)) },
                    openSalesLookup = { startActivity(Intent(this, BusinessDateSalesLookupActivity::class.java)) },
                    openLegacyManagement = { startActivity(Intent(this, OperationsActivity::class.java)) },
                )
            }
        }
    }
}

@Composable
private fun OperationsHubRouteV030(
    onClose: () -> Unit,
    openBusiness: () -> Unit,
    openXInspection: () -> Unit,
    openZSettlement: () -> Unit,
    openHistory: () -> Unit,
    openReceiptVoucher: () -> Unit,
    openSalesLookup: () -> Unit,
    openLegacyManagement: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { OperationsStore(context.applicationContext) }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.current(context.applicationContext)) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }

    val current = operator
    Surface(Modifier.fillMaxSize(), color = HubBackgroundV030) {
        if (current == null || !OperationsAccessPolicyV030.canEnter(current.permissions)) {
            HubDeniedV030(onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(context.applicationContext)
        val summary = if (
            current.allows(RegisterPermission.VIEW_SALES) ||
            current.allows(RegisterPermission.X_INSPECTION) ||
            current.allows(RegisterPermission.Z_SETTLEMENT)
        ) store.dailySummary() else null
        OperationsHubScreenV030(
            operatorName = current.name,
            permissions = current.permissions,
            session = store.activeBusinessSession(),
            summary = summary,
            onClose = onClose,
            openBusiness = openBusiness,
            openXInspection = openXInspection,
            openZSettlement = openZSettlement,
            openHistory = openHistory,
            openReceiptVoucher = openReceiptVoucher,
            openSalesLookup = openSalesLookup,
            openLegacyManagement = openLegacyManagement,
        )
    }
}

@Composable
private fun OperationsHubScreenV030(
    operatorName: String,
    permissions: Set<RegisterPermission>,
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary?,
    onClose: () -> Unit,
    openBusiness: () -> Unit,
    openXInspection: () -> Unit,
    openZSettlement: () -> Unit,
    openHistory: () -> Unit,
    openReceiptVoucher: () -> Unit,
    openSalesLookup: () -> Unit,
    openLegacyManagement: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Column(Modifier.fillMaxSize()) {
        HubHeaderV030(metrics)
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
                    HubStatusPanelV030(
                        Modifier.fillMaxWidth(),
                        operatorName,
                        session,
                        summary,
                    )
                    HubTileV030(
                        title = "営業開始・状態",
                        description = "営業セッションの開始と状態確認",
                        background = Color(0xFFE8EAF6),
                        enabled = RegisterPermission.Z_SETTLEMENT in permissions,
                        onClick = openBusiness,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                    ) {
                        HubTileV030(
                            "X点検",
                            "営業を継続したまま保存",
                            HubPaleGreenV030,
                            RegisterPermission.X_INSPECTION in permissions,
                            openXInspection,
                            Modifier.weight(1f).heightIn(min = 126.dp),
                        )
                        HubTileV030(
                            "Z精算",
                            "精算して営業終了",
                            Color(0xFFFFE8E8),
                            RegisterPermission.Z_SETTLEMENT in permissions,
                            openZSettlement,
                            Modifier.weight(1f).heightIn(min = 126.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                    ) {
                        HubTileV030(
                            "点検・精算履歴",
                            "営業セッション別の確認・再印字",
                            Color(0xFFEDE7F6),
                            SettlementHistoryPolicyV027.canView(permissions),
                            openHistory,
                            Modifier.weight(1f).heightIn(min = 112.dp),
                        )
                        HubTileV030(
                            "領収書発行",
                            "一部領収・複数枚・再発行",
                            VoucherHubBackgroundV059,
                            RegisterPermission.VIEW_SALES in permissions,
                            openReceiptVoucher,
                            Modifier.weight(1f).heightIn(min = 112.dp),
                        )
                    }
                    HubTileV030(
                        "営業日別 売上検索",
                        "営業日・売上No.・担当・支払・金額で検索",
                        HubPaleBlueV030,
                        RegisterPermission.VIEW_SALES in permissions,
                        openSalesLookup,
                        Modifier.fillMaxWidth().heightIn(min = 112.dp),
                    )
                    HubLegacyPanelV030(Modifier.fillMaxWidth(), permissions, openLegacyManagement)
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(metrics.screenPaddingDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    HubStatusPanelV030(
                        Modifier.weight(0.92f).fillMaxHeight(),
                        operatorName,
                        session,
                        summary,
                    )
                    Column(
                        Modifier.weight(1.48f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                    ) {
                        HubTileV030(
                            "営業開始・状態",
                            "営業セッションの開始と状態確認",
                            Color(0xFFE8EAF6),
                            RegisterPermission.Z_SETTLEMENT in permissions,
                            openBusiness,
                            Modifier.weight(0.75f).fillMaxWidth(),
                        )
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                        ) {
                            HubTileV030(
                                "X点検",
                                "営業を継続したまま保存",
                                HubPaleGreenV030,
                                RegisterPermission.X_INSPECTION in permissions,
                                openXInspection,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            HubTileV030(
                                "Z精算",
                                "精算して営業終了",
                                Color(0xFFFFE8E8),
                                RegisterPermission.Z_SETTLEMENT in permissions,
                                openZSettlement,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                        ) {
                            HubTileV030(
                                "点検・精算履歴",
                                "営業セッション別の確認・再印字",
                                Color(0xFFEDE7F6),
                                SettlementHistoryPolicyV027.canView(permissions),
                                openHistory,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            HubTileV030(
                                "領収書発行",
                                "一部領収・複数枚・再発行",
                                VoucherHubBackgroundV059,
                                RegisterPermission.VIEW_SALES in permissions,
                                openReceiptVoucher,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        Row(
                            Modifier.weight(0.75f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                        ) {
                            HubTileV030(
                                "営業日別 売上検索",
                                "営業日・売上No.・担当・支払・金額で検索",
                                HubPaleBlueV030,
                                RegisterPermission.VIEW_SALES in permissions,
                                openSalesLookup,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            HubLegacyPanelV030(
                                Modifier.weight(1f).fillMaxHeight(),
                                permissions,
                                openLegacyManagement,
                            )
                        }
                    }
                }
            }
        }
        HubBottomV030(metrics, onClose)
    }
}

private val VoucherHubBackgroundV059 = Color(0xFFE3F2FD)

@Composable
private fun HubStatusPanelV030(
    modifier: Modifier,
    operatorName: String,
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary?,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, HubBorderV030),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(metrics.cardPaddingDp.dp)) {
            Text("営業日の状態", fontSize = if (metrics.isCompact) 20.sp else 24.sp, fontWeight = FontWeight.Bold, color = HubNavyV030)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().background(HubPaleBlueV030, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("操作担当", color = Color.DarkGray)
                Spacer(Modifier.weight(1f))
                Text(operatorName, fontWeight = FontWeight.Bold, color = HubNavyV030)
            }
            Spacer(Modifier.height(10.dp))
            if (summary == null) {
                Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) {
                    Text("売上集計の表示権限がありません", color = Color.Gray)
                }
            } else {
                HubAmountV030("営業日", summary.businessDate)
                HubAmountV030("営業セッション", session?.let { "No.${it.id}" } ?: "開始前")
                HubAmountV030("営業状態", session?.status?.displayName ?: "営業開始前")
                HubAmountV030("開始釣銭", hubYenV030(summary.openingCash))
                HubAmountV030("純売上", hubYenV030(summary.netSales), true)
                HubAmountV030("取引件数", "${summary.transactionCount}件")
                HubAmountV030("現金理論残高", hubYenV030(summary.expectedCash))
                HubAmountV030("未印刷", "${summary.pendingPrints}件")
                HubAmountV030("未会計伝票", "${summary.heldTickets}件")
                HubAmountV030("精算状態", if (summary.settled) "Z精算済み" else "未精算")
            }
        }
    }
}

@Composable
private fun HubLegacyPanelV030(
    modifier: Modifier,
    permissions: Set<RegisterPermission>,
    onClick: () -> Unit,
) {
    val enabled = permissions.any {
        it == RegisterPermission.VIEW_SALES ||
            it == RegisterPermission.CASH_MOVEMENT ||
            it == RegisterPermission.REVERSAL
    }
    HubTileV030(
        title = "その他の管理機能",
        description = "当日売上・入出金・返品取消を開く",
        background = HubPaleYellowV030,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun HubTileV030(
    title: String,
    description: String,
    background: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (enabled) background else Color(0xFFE8ECEF)),
        border = BorderStroke(1.dp, HubBorderV030),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(metrics.cardPaddingDp.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                fontSize = if (metrics.isCompact) 20.sp else 25.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) HubNavyV030 else Color.Gray,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (enabled) description else "$description\n権限なし",
                textAlign = TextAlign.Center,
                color = if (enabled) Color.DarkGray else Color.Gray,
            )
        }
    }
}

@Composable
private fun HubAmountV030(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (emphasized) HubNavyV030 else Color.DarkGray,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            value,
            fontSize = if (emphasized) 20.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) HubNavyV030 else Color.Black,
        )
    }
}

@Composable
private fun HubHeaderV030(metrics: RegisterResponsiveMetrics) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.headerHeightDp.dp)
            .background(HubNavyV030)
            .padding(horizontal = metrics.screenPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = if (metrics.isCompact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(if (metrics.isCompact) 12.dp else 24.dp))
        Text("SCR-700  レジ管理メニュー", color = Color.White, fontSize = if (metrics.isCompact) 17.sp else 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (!metrics.isCompact) Text("オフライン管理", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun HubBottomV030(metrics: RegisterResponsiveMetrics, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.bottomBarHeightDp.dp)
            .background(Color.White)
            .padding(horizontal = metrics.screenPaddingDp.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text("販売へ戻る", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HubDeniedV030(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("レジ管理を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HubDangerV030)
        Spacer(Modifier.height(12.dp))
        Text("管理権限がないか、ログインセッションが失効しています。")
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HubBlueV030),
        ) { Text("販売画面へ戻る") }
    }
}

private fun hubYenV030(value: Long): String =
    NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)