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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CatalogHubNavyV030 = Color(0xFF173F6B)
private val CatalogHubBlueV030 = Color(0xFF1976B9)
private val CatalogHubBackgroundV030 = Color(0xFFF4F7FA)
private val CatalogHubBorderV030 = Color(0xFFD5DEE7)
private val CatalogHubDangerV030 = Color(0xFFC62828)
private val CatalogHubPaleBlueV030 = Color(0xFFEAF3FA)
private val CatalogHubPaleGreenV030 = Color(0xFFEAF5EC)
private val CatalogHubPaleYellowV030 = Color(0xFFFFF4D9)

object CatalogNavigationContractV030 {
    const val EXTRA_INITIAL_SCREEN = "jp.co.tenposinfo.register.extra.CATALOG_INITIAL_SCREEN"
    const val EXTRA_PREFILL_BARCODE = "jp.co.tenposinfo.register.extra.CATALOG_PREFILL_BARCODE"
    const val PRODUCTS = "PRODUCTS"
    const val DEPARTMENTS = "DEPARTMENTS"
    const val GROUPS = "GROUPS"
    const val LAYOUT = "LAYOUT"
    const val TAXES = "TAXES"
    const val PROFILES = "PROFILES"

    fun intent(context: Context, destination: String): Intent =
        Intent(context, CatalogSettingsActivity::class.java)
            .putExtra(EXTRA_INITIAL_SCREEN, destination)

    fun productRegistrationIntent(context: Context, scannedCode: String): Intent =
        intent(context, PRODUCTS)
            .putExtra(EXTRA_PREFILL_BARCODE, scannedCode.take(64))
}

/** SCR-200の商品・分類・税・販売プロファイル用レスポンシブ入口。 */
class CatalogHubActivityV030 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                CatalogHubRouteV030(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun CatalogHubRouteV030(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CatalogMasterStore(context.applicationContext) }
    val operator = remember { OperatorSessionRegistry.current(context.applicationContext) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }

    Surface(Modifier.fillMaxSize(), color = CatalogHubBackgroundV030) {
        if (operator?.isManager != true || !operator.allows(RegisterPermission.SETTINGS)) {
            CatalogHubDeniedV030(onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(context.applicationContext)
        CatalogHubScreenV030(
            productCount = store.listProducts().count { it.enabled },
            departmentCount = store.listDepartments().count { it.enabled },
            groupCount = store.listGroups().count { it.enabled },
            activeProfileName = store.activeProfile()?.name ?: "未設定",
            onProducts = { context.startActivity(CatalogNavigationContractV030.intent(context, CatalogNavigationContractV030.PRODUCTS)) },
            onDepartments = { context.startActivity(CatalogNavigationContractV030.intent(context, CatalogNavigationContractV030.DEPARTMENTS)) },
            onGroups = { context.startActivity(CatalogNavigationContractV030.intent(context, CatalogNavigationContractV030.GROUPS)) },
            onLayout = { context.startActivity(CatalogNavigationContractV030.intent(context, CatalogNavigationContractV030.LAYOUT)) },
            onTaxes = { context.startActivity(CatalogNavigationContractV030.intent(context, CatalogNavigationContractV030.TAXES)) },
            onProfiles = { context.startActivity(CatalogNavigationContractV030.intent(context, CatalogNavigationContractV030.PROFILES)) },
            onDynamic = { context.startActivity(Intent(context, DynamicCatalogHubActivityV030::class.java)) },
            onClose = onClose,
        )
    }
}

@Composable
private fun CatalogHubScreenV030(
    productCount: Int,
    departmentCount: Int,
    groupCount: Int,
    activeProfileName: String,
    onProducts: () -> Unit,
    onDepartments: () -> Unit,
    onGroups: () -> Unit,
    onLayout: () -> Unit,
    onTaxes: () -> Unit,
    onProfiles: () -> Unit,
    onDynamic: () -> Unit,
    onClose: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Column(Modifier.fillMaxSize()) {
        CatalogHubHeaderV030(metrics)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = metrics.isCompact || maxWidth < 1_080.dp || maxHeight < 600.dp
            val contentModifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(metrics.screenPaddingDp.dp)
            Column(contentModifier, verticalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp)) {
                if (compact) {
                    CatalogHubSummaryV030(
                        modifier = Modifier.fillMaxWidth(),
                        productCount = productCount,
                        departmentCount = departmentCount,
                        groupCount = groupCount,
                        activeProfileName = activeProfileName,
                    )
                    CatalogHubTileV030("SCR-210", "商品マスター", "商品コード・名称・価格・税区分・所属", CatalogHubPaleBlueV030, onProducts, Modifier.fillMaxWidth())
                    CatalogHubTileV030("SCR-220", "部門マスター", "商品が所属する部門", CatalogHubPaleGreenV030, onDepartments, Modifier.fillMaxWidth())
                    CatalogHubTileV030("SCR-230", "グループマスター", "部門配下の分析・表示グループ", CatalogHubPaleYellowV030, onGroups, Modifier.fillMaxWidth())
                    CatalogHubTileV030("SCR-240", "商品ボタン配置", "最大9ページ・各24ボタン", Color(0xFFE8EAF6), onLayout, Modifier.fillMaxWidth())
                    CatalogHubTileV030("SCR-250", "税区分マスター", "非課税・標準税率・軽減税率・内外税", Color(0xFFFFE8E8), onTaxes, Modifier.fillMaxWidth())
                    CatalogHubTileV030("SCR-260", "販売プロファイル", "時間帯別価格・税区分", Color(0xFFEDE7F6), onProfiles, Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp)) {
                        CatalogHubSummaryV030(
                            modifier = Modifier.weight(1f).heightIn(min = 150.dp),
                            productCount = productCount,
                            departmentCount = departmentCount,
                            groupCount = groupCount,
                            activeProfileName = activeProfileName,
                        )
                        CatalogHubTileV030("SCR-210", "商品マスター", "商品コード・名称・価格・税区分・所属", CatalogHubPaleBlueV030, onProducts, Modifier.weight(1f))
                        CatalogHubTileV030("SCR-220", "部門マスター", "商品が所属する部門", CatalogHubPaleGreenV030, onDepartments, Modifier.weight(1f))
                        CatalogHubTileV030("SCR-230", "グループマスター", "部門配下の分析・表示グループ", CatalogHubPaleYellowV030, onGroups, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp)) {
                        CatalogHubTileV030("SCR-240", "商品ボタン配置", "最大9ページ・各24ボタン", Color(0xFFE8EAF6), onLayout, Modifier.weight(1f))
                        CatalogHubTileV030("SCR-250", "税区分マスター", "非課税・標準税率・軽減税率・内外税", Color(0xFFFFE8E8), onTaxes, Modifier.weight(1f))
                        CatalogHubTileV030("SCR-260", "販売プロファイル", "時間帯別価格・税区分", Color(0xFFEDE7F6), onProfiles, Modifier.weight(1f))
                    }
                }
                Button(
                    onClick = onDynamic,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CatalogHubNavyV030),
                ) {
                    Text("SCR-270  任意税率・メニュー改定", fontWeight = FontWeight.Bold)
                }
                Text(
                    "編集画面は対象マスターを直接開きます。保存後、販売画面へ戻ると商品ボタンと現在時刻の販売プロファイルが自動反映されます。",
                    color = Color.DarkGray,
                )
            }
        }
        CatalogHubBottomV030(metrics, onClose)
    }
}

@Composable
private fun CatalogHubSummaryV030(
    modifier: Modifier,
    productCount: Int,
    departmentCount: Int,
    groupCount: Int,
    activeProfileName: String,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CatalogHubBorderV030),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(metrics.cardPaddingDp.dp)) {
            Text("マスター状態", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CatalogHubNavyV030)
            Spacer(Modifier.height(8.dp))
            CatalogHubValueV030("商品", "${productCount}件")
            CatalogHubValueV030("部門", "${departmentCount}件")
            CatalogHubValueV030("グループ", "${groupCount}件")
            CatalogHubValueV030("販売プロファイル", activeProfileName)
        }
    }
}

@Composable
private fun CatalogHubTileV030(
    screenId: String,
    title: String,
    description: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier.heightIn(min = 132.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, CatalogHubBorderV030),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(metrics.cardPaddingDp.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(screenId, color = CatalogHubBlueV030, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(title, fontSize = if (metrics.isCompact) 20.sp else 22.sp, fontWeight = FontWeight.Bold, color = CatalogHubNavyV030, textAlign = TextAlign.Center)
            Spacer(Modifier.height(5.dp))
            Text(description, color = Color.DarkGray, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CatalogHubValueV030(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(value, fontWeight = FontWeight.Bold, color = CatalogHubNavyV030)
    }
}

@Composable
private fun CatalogHubHeaderV030(metrics: RegisterResponsiveMetrics) {
    Row(
        Modifier.fillMaxWidth().height(metrics.headerHeightDp.dp).background(CatalogHubNavyV030).padding(horizontal = metrics.screenPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = if (metrics.isCompact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(if (metrics.isCompact) 12.dp else 24.dp))
        Text("SCR-200  商品・分類・税・販売プロファイル", color = Color.White, fontSize = if (metrics.isCompact) 16.sp else 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CatalogHubBottomV030(metrics: RegisterResponsiveMetrics, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(metrics.bottomBarHeightDp.dp).background(Color.White).padding(horizontal = metrics.screenPaddingDp.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text("各種設定へ戻る", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CatalogHubDeniedV030(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("商品設定を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = CatalogHubDangerV030)
        Spacer(Modifier.height(12.dp))
        Text("責任者の各種設定権限が必要です。")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
    }
}
