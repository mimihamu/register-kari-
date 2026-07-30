package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private val DcNavy = Color(0xFF173F6B)
private val DcBlue = Color(0xFF1976B9)
private val DcDanger = Color(0xFFC62828)
private val DcBackground = Color(0xFFF4F7FA)
private val DcBorder = Color(0xFFD5DEE7)
private val DcSelected = Color(0xFFFFEBEE)
private val DcPaleBlue = Color(0xFFEAF3FA)

class DynamicCatalogSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DynamicCatalogSettingsApp(onClose = { finish() })
            }
        }
    }
}

private enum class DynamicCatalogScreen {
    MENU,
    TAX_RULES,
    ASSIGNMENTS,
    REVISIONS,
}

@Composable
private fun DynamicCatalogSettingsApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DynamicCatalogStore(context.applicationContext) }
    val actor = remember { OperatorSessionRegistry.current(context.applicationContext)?.name ?: "責任者" }
    var screen by remember { mutableStateOf(DynamicCatalogScreen.MENU) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { store.close() } }

    Surface(Modifier.fillMaxSize(), color = DcBackground) {
        when (screen) {
            DynamicCatalogScreen.MENU -> DynamicMenu(
                store = store,
                refresh = refresh,
                message = message,
                onTaxes = { message = null; screen = DynamicCatalogScreen.TAX_RULES },
                onAssignments = { message = null; screen = DynamicCatalogScreen.ASSIGNMENTS },
                onRevisions = { message = null; screen = DynamicCatalogScreen.REVISIONS },
                onClose = onClose,
            )
            DynamicCatalogScreen.TAX_RULES -> DynamicTaxRuleScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                onChanged = { refresh++; message = it },
                onBack = { screen = DynamicCatalogScreen.MENU },
            )
            DynamicCatalogScreen.ASSIGNMENTS -> ProductTaxAssignmentScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                onChanged = { refresh++; message = it },
                onBack = { screen = DynamicCatalogScreen.MENU },
            )
            DynamicCatalogScreen.REVISIONS -> MenuRevisionScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                onChanged = { refresh++; message = it },
                onBack = { screen = DynamicCatalogScreen.MENU },
            )
        }
    }
}

@Composable
private fun DynamicMenu(
    store: DynamicCatalogStore,
    refresh: Int,
    message: String?,
    onTaxes: () -> Unit,
    onAssignments: () -> Unit,
    onRevisions: () -> Unit,
    onClose: () -> Unit,
) {
    val rules = remember(refresh) { store.listTaxRules() }
    val revisions = remember(refresh) { store.listMenuRevisions() }
    val active = remember(refresh) { store.activeRevision() }
    Column(Modifier.fillMaxSize()) {
        DcHeader("SCR-270", "任意税率・メニュー改定", onClose)
        Column(Modifier.fillMaxSize().padding(26.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DcSummary("有効な税区分", "${rules.count { it.enabled }}件", Modifier.weight(1f))
                DcSummary("予約中の改定", "${revisions.count { it.status == "SCHEDULED" && it.effectiveDate > LocalDate.now().toString() }}件", Modifier.weight(1f))
                DcSummary("現在の改定", active?.name ?: "ライブマスター", Modifier.weight(1.4f))
            }
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(message, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                DcMenuTile("SCR-271", "任意税率マスター", "第3税率を追加し、内税・外税・非課税と税記号を設定", onTaxes, Modifier.weight(1f))
                DcMenuTile("SCR-272", "商品への税区分割当", "商品ごとに標準税区分または追加税率を割り当て", onAssignments, Modifier.weight(1f))
                DcMenuTile("SCR-273", "メニュー改定予約", "現在のメニューを営業日指定の不変スナップショットとして予約", onRevisions, Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, DcBorder),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("適用ルール", fontWeight = FontWeight.Bold, color = DcNavy, fontSize = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("税率は0～100%の整数で追加できます。計算は1インボイス・税率単位で合算後に一度だけ端数処理します。")
                    Text("予約した改定は適用営業日になるまで現在のメニューへ影響しません。適用後は予約時点の商品名・価格・税区分・ボタン配置を使用します。")
                    Text("販売画面は15秒ごとに販売プロファイルと改定状態を再判定します。")
                }
            }
        }
    }
}

@Composable
private fun DynamicTaxRuleScreen(
    store: DynamicCatalogStore,
    refresh: Int,
    actor: String,
    onChanged: (String) -> Unit,
    onBack: () -> Unit,
) {
    val rows = remember(refresh) { store.listTaxRules() }
    var selected by remember { mutableStateOf<DynamicTaxRule?>(null) }
    var key by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("10") }
    var mode by remember { mutableStateOf(DynamicTaxMode.INCLUDED) }
    var reduced by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var symbol by remember { mutableStateOf("内") }
    var validFrom by remember { mutableStateOf("") }
    var validTo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected?.key) {
        key = selected?.key.orEmpty()
        label = selected?.label.orEmpty()
        rate = selected?.ratePercent?.toString() ?: "10"
        mode = selected?.mode ?: DynamicTaxMode.INCLUDED
        reduced = selected?.reduced ?: false
        enabled = selected?.enabled ?: true
        symbol = selected?.symbol ?: "内"
        validFrom = selected?.validFrom.orEmpty()
        validTo = selected?.validTo.orEmpty()
        error = null
    }

    DcSplit("SCR-271", "任意税率マスター", onBack, left = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("税区分一覧", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = DcNavy)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { selected = null }) { Text("新規") }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.key }) { row ->
                val status = if (row.enabled) "有効" else "停止"
                DcListRow(
                    title = "${row.label}  ${row.ratePercent}%",
                    subtitle = "${row.key} / ${modeLabel(row.mode)} / ${row.symbol} / $status",
                    selected = selected?.key == row.key,
                    onClick = { selected = row },
                )
            }
        }
    }, right = {
        Text(if (selected == null) "税区分を追加" else "税区分を編集", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = DcNavy)
        Spacer(Modifier.height(12.dp))
        DcField(key, { key = it.uppercase().take(30) }, "税区分キー", selected == null)
        DcField(label, { label = it.take(60) }, "表示名")
        DcField(rate, { rate = it.filter(Char::isDigit).take(3) }, "税率（%）", true, KeyboardType.Number)
        DcCycle("課税方式", modeLabel(mode)) {
            mode = DynamicTaxMode.entries[(mode.ordinal + 1) % DynamicTaxMode.entries.size]
            symbol = defaultSymbol(mode, reduced)
            if (mode == DynamicTaxMode.NON_TAXABLE) rate = "0"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(reduced, { reduced = it; symbol = defaultSymbol(mode, it) })
            Text("軽減税率対象")
            Spacer(Modifier.width(18.dp))
            Checkbox(enabled, { enabled = it })
            Text("有効")
        }
        DcField(symbol, { symbol = it.take(4) }, "税記号")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DcField(validFrom, { validFrom = it.take(10) }, "適用開始日", true, KeyboardType.Text, Modifier.weight(1f))
            DcField(validTo, { validTo = it.take(10) }, "適用終了日", true, KeyboardType.Text, Modifier.weight(1f))
        }
        Text("日付はyyyy-MM-dd。空欄は制限なし。", color = Color.Gray, fontSize = 12.sp)
        DcError(error)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selected != null && TaxCategory.entries.none { it.name == selected?.key }) {
                OutlinedButton(
                    onClick = {
                        error = runCatching {
                            store.deleteTaxRule(selected!!.key, actor)
                            selected = null
                            onChanged("税区分を削除しました")
                        }.exceptionOrNull()?.message
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DcDanger),
                ) { Text("削除") }
            }
            Button(
                onClick = {
                    error = runCatching {
                        store.saveTaxRule(
                            originalKey = selected?.key,
                            record = DynamicTaxRule(
                                key = key,
                                label = label,
                                ratePercent = rate.toIntOrNull() ?: 0,
                                mode = mode,
                                reduced = reduced,
                                enabled = enabled,
                                symbol = symbol,
                                validFrom = validFrom,
                                validTo = validTo,
                            ),
                            actor = actor,
                        )
                        selected = null
                        onChanged("税区分マスターを保存しました")
                    }.exceptionOrNull()?.message
                },
                modifier = Modifier.weight(2f),
                colors = ButtonDefaults.buttonColors(containerColor = DcBlue),
            ) { Text("保存") }
        }
    })
}

@Composable
private fun ProductTaxAssignmentScreen(
    store: DynamicCatalogStore,
    refresh: Int,
    actor: String,
    onChanged: (String) -> Unit,
    onBack: () -> Unit,
) {
    val assignments = remember(refresh) { store.listAssignments() }
    val rules = remember(refresh) { store.listTaxRules().filter { it.enabled } }
    var selected by remember { mutableStateOf<ProductTaxAssignment?>(null) }
    var selectedRule by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected?.productId) {
        selectedRule = selected?.taxKey ?: rules.firstOrNull()?.key
        error = null
    }

    DcSplit("SCR-272", "商品への税区分割当", onBack, left = {
        Text("商品一覧", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = DcNavy)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(assignments, key = { it.productId }) { row ->
                DcListRow(
                    title = "${row.productId}  ${row.productName}",
                    subtitle = "${row.taxLabel}（${row.taxKey}）",
                    selected = selected?.productId == row.productId,
                    onClick = { selected = row },
                )
            }
        }
    }, right = {
        Text("税区分を割り当て", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = DcNavy)
        Spacer(Modifier.height(12.dp))
        if (selected == null) {
            Text("左の商品を選択してください", color = Color.Gray)
        } else {
            Text("${selected!!.productId}  ${selected!!.productName}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            DcCycle(
                "適用税区分",
                rules.firstOrNull { it.key == selectedRule }?.let { "${it.label} ${it.ratePercent}% ${modeLabel(it.mode)}" } ?: "未選択",
            ) {
                val index = rules.indexOfFirst { it.key == selectedRule }
                selectedRule = rules[(index + 1).mod(rules.size.coerceAtLeast(1))].key
            }
            DcError(error)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        error = runCatching {
                            store.clearProductTaxAssignment(selected!!.productId, actor)
                            selected = null
                            onChanged("標準税区分へ戻しました")
                        }.exceptionOrNull()?.message
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("標準へ戻す") }
                Button(
                    onClick = {
                        error = runCatching {
                            val key = requireNotNull(selectedRule) { "税区分を選択してください" }
                            store.assignProductTax(selected!!.productId, key, actor)
                            selected = null
                            onChanged("商品の税区分を保存しました")
                        }.exceptionOrNull()?.message
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = DcBlue),
                ) { Text("保存") }
            }
        }
    })
}

@Composable
private fun MenuRevisionScreen(
    store: DynamicCatalogStore,
    refresh: Int,
    actor: String,
    onChanged: (String) -> Unit,
    onBack: () -> Unit,
) {
    val revisions = remember(refresh) { store.listMenuRevisions() }
    var name by remember { mutableStateOf("") }
    var effectiveDate by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        DcHeader("SCR-273", "メニュー改定予約", onBack)
        Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Card(
                modifier = Modifier.width(400.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, DcBorder),
            ) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Text("現在のメニューを予約", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DcNavy)
                    Spacer(Modifier.height(12.dp))
                    DcField(name, { name = it.take(80) }, "改定名")
                    DcField(effectiveDate, { effectiveDate = it.take(10) }, "適用営業日")
                    Text("保存時点の商品名・価格・税区分・有効状態・ボタン配置を丸ごと保存します。", color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("予約後にライブマスターを変更しても、予約済み改定の内容は変わりません。", color = Color.Gray, fontSize = 13.sp)
                    DcError(error)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            error = runCatching {
                                store.scheduleCurrentMenu(name, effectiveDate, actor)
                                name = ""
                                onChanged("メニュー改定を予約しました")
                            }.exceptionOrNull()?.message
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DcBlue),
                    ) { Text("現在のメニューを予約", fontWeight = FontWeight.Bold) }
                }
            }
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, DcBorder),
            ) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Text("改定履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DcNavy)
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(revisions, key = { it.id }) { revision ->
                            val future = revision.status == "SCHEDULED" && revision.effectiveDate > LocalDate.now().toString()
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                colors = CardDefaults.cardColors(containerColor = if (future) DcPaleBlue else Color.White),
                                border = BorderStroke(1.dp, DcBorder),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(revision.name, fontWeight = FontWeight.Bold, color = DcNavy)
                                        Text("適用 ${revision.effectiveDate} / ${revision.itemCount}商品 / ${statusLabel(revision)}", fontSize = 13.sp)
                                        Text("作成 ${formatEpoch(revision.createdAt)} / ${revision.createdBy}", fontSize = 12.sp, color = Color.Gray)
                                    }
                                    if (future) {
                                        OutlinedButton(
                                            onClick = {
                                                error = runCatching {
                                                    store.cancelMenuRevision(revision.id, actor)
                                                    onChanged("改定予約を取り消しました")
                                                }.exceptionOrNull()?.message
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DcDanger),
                                        ) { Text("予約取消") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun modeLabel(mode: DynamicTaxMode): String = when (mode) {
    DynamicTaxMode.NON_TAXABLE -> "非課税"
    DynamicTaxMode.INCLUDED -> "内税"
    DynamicTaxMode.EXCLUDED -> "外税"
}

private fun defaultSymbol(mode: DynamicTaxMode, reduced: Boolean): String = when (mode) {
    DynamicTaxMode.NON_TAXABLE -> "非"
    DynamicTaxMode.INCLUDED -> if (reduced) "内※" else "内"
    DynamicTaxMode.EXCLUDED -> if (reduced) "外※" else "外"
}

private fun statusLabel(revision: MenuRevisionRecord): String = when {
    revision.status == "CANCELLED" -> "取消済み"
    revision.effectiveDate <= LocalDate.now().toString() -> "適用中／適用済み"
    else -> "予約中"
}

private fun formatEpoch(value: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(value))

@Composable
private fun DcHeader(screenId: String, title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).background(DcNavy).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("REGISTER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 23.sp)
        Spacer(Modifier.width(22.dp))
        Text("$screenId  $title", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onBack,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            border = BorderStroke(1.dp, Color.White),
        ) { Text("戻る") }
    }
}

@Composable
private fun DcSplit(screenId: String, title: String, onBack: () -> Unit, left: @Composable Column.() -> Unit, right: @Composable Column.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DcHeader(screenId, title, onBack)
        Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Card(
                Modifier.weight(1.1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, DcBorder),
            ) { Column(Modifier.fillMaxSize().padding(16.dp), content = left) }
            Card(
                Modifier.width(480.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, DcBorder),
            ) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), content = right) }
        }
    }
}

@Composable
private fun DcField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun DcCycle(label: String, value: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(54.dp)) {
        Text("$label：$value", textAlign = TextAlign.Center)
    }
}

@Composable
private fun DcListRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) DcSelected else Color.Transparent, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(11.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = DcNavy)
        Text(subtitle, fontSize = 13.sp, color = Color.DarkGray)
    }
}

@Composable
private fun DcSummary(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, DcBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.DarkGray, fontSize = 13.sp)
            Text(value, color = DcNavy, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DcMenuTile(id: String, title: String, description: String, onClick: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier.height(150.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, DcBorder),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(id, color = DcBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(title, color = DcNavy, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(description, color = Color.DarkGray, fontSize = 14.sp)
        }
    }
}

@Composable
private fun DcError(value: String?) {
    if (value != null) {
        Spacer(Modifier.height(8.dp))
        Text(value, color = DcDanger, fontWeight = FontWeight.Bold)
    }
}
