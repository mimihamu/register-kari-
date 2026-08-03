package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private val DynamicHubNavyV030 = Color(0xFF173F6B)
private val DynamicHubBlueV030 = Color(0xFF1976B9)
private val DynamicHubBackgroundV030 = Color(0xFFF4F7FA)
private val DynamicHubBorderV030 = Color(0xFFD5DEE7)
private val DynamicHubDangerV030 = Color(0xFFC62828)
private val DynamicHubPaleBlueV030 = Color(0xFFEAF3FA)
private val DynamicHubPaleGreenV030 = Color(0xFFEAF5EC)
private val DynamicHubPaleYellowV030 = Color(0xFFFFF4D9)

object DynamicCatalogNavigationContractV030 {
    const val EXTRA_INITIAL_SCREEN = "jp.co.tenposinfo.register.extra.DYNAMIC_CATALOG_INITIAL_SCREEN"
    const val TAX_RULES = "TAX_RULES"
    const val ASSIGNMENTS = "ASSIGNMENTS"
    const val REVISIONS = "REVISIONS"

    fun intent(context: Context, destination: String): Intent =
        Intent(context, DynamicCatalogSettingsActivity::class.java)
            .putExtra(EXTRA_INITIAL_SCREEN, destination)
}

/** SCR-270の任意税率・メニュー改定用レスポンシブ入口。 */
class DynamicCatalogHubActivityV030 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                DynamicCatalogHubRouteV030(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun DynamicCatalogHubRouteV030(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DynamicCatalogStore(context.applicationContext) }
    val operator = remember { OperatorSessionRegistry.current(context.applicationContext) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }

    Surface(Modifier.fillMaxSize(), color = DynamicHubBackgroundV030) {
        if (operator?.isManager != true || !operator.allows(RegisterPermission.SETTINGS)) {
            DynamicHubDeniedV030(onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(context.applicationContext)
        val rules = store.listTaxRules()
        val revisions = store.listMenuRevisions()
        DynamicCatalogHubScreenV030(
            activeTaxRuleCount = rules.count { it.enabled },
            scheduledRevisionCount = revisions.count {
                it.status == "SCHEDULED" && it.effectiveDate > LocalDate.now().toString()
            },
            activeRevisionName = store.activeRevision()?.name ?: "ライブマスター",
            onTaxRules = {
                context.startActivity(
                    DynamicCatalogNavigationContractV030.intent(
                        context,
                        DynamicCatalogNavigationContractV030.TAX_RULES,
                    ),
                )
            },
            onAssignments = {
                context.startActivity(
                    DynamicCatalogNavigationContractV030.intent(
                        context,
                        DynamicCatalogNavigationContractV030.ASSIGNMENTS,
                    ),
                )
            },
            onRevisions = {
                context.startActivity(
                    DynamicCatalogNavigationContractV030.intent(
                        context,
                        DynamicCatalogNavigationContractV030.REVISIONS,
                    ),
                )
            },
            onTaxInvoice = { context.startActivity(Intent(context, TaxInvoiceSettingsActivity::class.java)) },
            onRevisionEditor = { context.startActivity(Intent(context, MenuRevisionEditorActivity::class.java)) },
            onSync = { context.startActivity(Intent(context, SyncSettingsActivity::class.java)) },
            onClose = onClose,
        )
    }
}

@Composable
private fun DynamicCatalogHubScreenV030(
    activeTaxRuleCount: Int,
    scheduledRevisionCount: Int,
    activeRevisionName: String,
    onTaxRules: () -> Unit,
    onAssignments: () -> Unit,
    onRevisions: () -> Unit,
    onTaxInvoice: () -> Unit,
    onRevisionEditor: () -> Unit,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Column(Modifier.fillMaxSize()) {
        DynamicHubHeaderV030(metrics)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = metrics.isCompact || maxWidth < 1_080.dp || maxHeight < 600.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(metrics.screenPaddingDp.dp),
                verticalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
            ) {
                if (compact) {
                    DynamicHubSummaryV030(
                        Modifier.fillMaxWidth(),
                        activeTaxRuleCount,
                        scheduledRevisionCount,
                        activeRevisionName,
                    )
                    DynamicHubTileV030("SCR-271", "任意税率マスター", "第3税率、内税・外税・非課税、税記号", DynamicHubPaleBlueV030, onTaxRules, Modifier.fillMaxWidth())
                    DynamicHubTileV030("SCR-272", "商品への税区分割当", "商品ごとの標準税区分・追加税率", DynamicHubPaleGreenV030, onAssignments, Modifier.fillMaxWidth())
                    DynamicHubTileV030("SCR-273", "メニュー改定予約", "適用営業日を指定した不変スナップショット", DynamicHubPaleYellowV030, onRevisions, Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp)) {
                        DynamicHubSummaryV030(
                            Modifier.weight(1f).heightIn(min = 150.dp),
                            activeTaxRuleCount,
                            scheduledRevisionCount,
                            activeRevisionName,
                        )
                        DynamicHubTileV030("SCR-271", "任意税率マスター", "第3税率、内税・外税・非課税、税記号", DynamicHubPaleBlueV030, onTaxRules, Modifier.weight(1f))
                        DynamicHubTileV030("SCR-272", "商品への税区分割当", "商品ごとの標準税区分・追加税率", DynamicHubPaleGreenV030, onAssignments, Modifier.weight(1f))
                        DynamicHubTileV030("SCR-273", "メニュー改定予約", "適用営業日を指定した不変スナップショット", DynamicHubPaleYellowV030, onRevisions, Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp)) {
                    DynamicHubActionV030("税・インボイス", onTaxInvoice, Modifier.weight(1f))
                    DynamicHubActionV030("改定内容編集", onRevisionEditor, Modifier.weight(1f))
                    DynamicHubActionV030("同期基盤", onSync, Modifier.weight(1f))
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, DynamicHubBorderV030),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(metrics.cardPaddingDp.dp)) {
                        Text("適用ルール", fontWeight = FontWeight.Bold, color = DynamicHubNavyV030, fontSize = 19.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("税率は0～100%の整数で追加し、1インボイス・税率単位で合算後に一度だけ端数処理します。")
                        Text("予約した改定は適用営業日まで現在のメニューへ影響せず、適用後は予約時点の商品名・価格・税区分・配置を使用します。")
                    }
                }
            }
        }
        DynamicHubBottomV030(metrics, onClose)
    }
}

@Composable
private fun DynamicHubSummaryV030(
    modifier: Modifier,
    activeTaxRuleCount: Int,
    scheduledRevisionCount: Int,
    activeRevisionName: String,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, DynamicHubBorderV030),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(metrics.cardPaddingDp.dp)) {
            Text("改定状態", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DynamicHubNavyV030)
            Spacer(Modifier.height(8.dp))
            DynamicHubValueV030("有効な税区分", "${activeTaxRuleCount}件")
            DynamicHubValueV030("予約中の改定", "${scheduledRevisionCount}件")
            DynamicHubValueV030("現在の改定", activeRevisionName)
        }
    }
}

@Composable
private fun DynamicHubTileV030(
    screenId: String,
    title: String,
    description: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier.heightIn(min = 142.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, DynamicHubBorderV030),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(metrics.cardPaddingDp.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(screenId, color = DynamicHubBlueV030, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(title, fontSize = if (metrics.isCompact) 19.sp else 21.sp, fontWeight = FontWeight.Bold, color = DynamicHubNavyV030, textAlign = TextAlign.Center)
            Spacer(Modifier.height(5.dp))
            Text(description, color = Color.DarkGray, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DynamicHubActionV030(label: String, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(label, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DynamicHubValueV030(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(value, fontWeight = FontWeight.Bold, color = DynamicHubNavyV030)
    }
}

@Composable
private fun DynamicHubHeaderV030(metrics: RegisterResponsiveMetrics) {
    Row(
        Modifier.fillMaxWidth().height(metrics.headerHeightDp.dp).background(DynamicHubNavyV030).padding(horizontal = metrics.screenPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = if (metrics.isCompact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(if (metrics.isCompact) 12.dp else 24.dp))
        Text("SCR-270  任意税率・メニュー改定", color = Color.White, fontSize = if (metrics.isCompact) 17.sp else 21.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DynamicHubBottomV030(metrics: RegisterResponsiveMetrics, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(metrics.bottomBarHeightDp.dp).background(Color.White).padding(horizontal = metrics.screenPaddingDp.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text("商品設定へ戻る", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DynamicHubDeniedV030(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("任意税率・メニュー改定を利用できません", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = DynamicHubDangerV030)
        Spacer(Modifier.height(12.dp))
        Text("責任者の各種設定権限が必要です。")
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
    }
}
