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

private val CmNavy = Color(0xFF173F6B)
private val CmBlue = Color(0xFF1976B9)
private val CmDanger = Color(0xFFC62828)
private val CmBackground = Color(0xFFF4F7FA)
private val CmBorder = Color(0xFFD5DEE7)
private val CmPaleBlue = Color(0xFFEAF3FA)
private val CmPaleGreen = Color(0xFFEAF5EC)
private val CmPaleYellow = Color(0xFFFFF4D9)

class CatalogSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                CatalogSettingsApp(onClose = { finish() })
            }
        }
    }
}

private enum class CatalogScreen {
    MENU,
    PRODUCTS,
    DEPARTMENTS,
    GROUPS,
    LAYOUT,
    TAXES,
    PROFILES,
}

@Composable
private fun CatalogSettingsApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CatalogMasterStore(context.applicationContext) }
    val actor = remember { OperatorSessionRegistry.current(context.applicationContext)?.name ?: "責任者" }
    val initialBarcode = remember {
        (context as? ComponentActivity)?.intent
            ?.getStringExtra(CatalogNavigationContractV030.EXTRA_PREFILL_BARCODE)
            ?.let { runCatching { CatalogValidation.normalizeBarcode(it) }.getOrNull() }
            .orEmpty()
    }
    val initialScreen = remember {
    when ((context as? ComponentActivity)?.intent?.getStringExtra(CatalogNavigationContractV030.EXTRA_INITIAL_SCREEN)) {
        CatalogNavigationContractV030.PRODUCTS -> CatalogScreen.PRODUCTS
        CatalogNavigationContractV030.DEPARTMENTS -> CatalogScreen.DEPARTMENTS
        CatalogNavigationContractV030.GROUPS -> CatalogScreen.GROUPS
        CatalogNavigationContractV030.LAYOUT -> CatalogScreen.LAYOUT
        CatalogNavigationContractV030.TAXES -> CatalogScreen.TAXES
        CatalogNavigationContractV030.PROFILES -> CatalogScreen.PROFILES
        else -> CatalogScreen.MENU
    }
}
var screen by remember { mutableStateOf(initialScreen) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }

    fun saved(text: String) {
        refresh++
        message = text
    }

    Surface(Modifier.fillMaxSize(), color = CmBackground) {
        when (screen) {
            CatalogScreen.MENU -> CatalogMenuScreen(
                store = store,
                refresh = refresh,
                message = message,
                onProducts = { message = null; screen = CatalogScreen.PRODUCTS },
                onDepartments = { message = null; screen = CatalogScreen.DEPARTMENTS },
                onGroups = { message = null; screen = CatalogScreen.GROUPS },
                onLayout = { message = null; screen = CatalogScreen.LAYOUT },
                onTaxes = { message = null; screen = CatalogScreen.TAXES },
                onProfiles = { message = null; screen = CatalogScreen.PROFILES },
                onDynamic = { context.startActivity(Intent(context, DynamicCatalogHubActivityV030::class.java)) },
                onClose = onClose,
            )

            CatalogScreen.PRODUCTS -> ProductMasterScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                initialBarcode = initialBarcode,
                onSaved = { saved("商品マスターを保存しました") },
                onBack = { screen = CatalogScreen.MENU },
            )

            CatalogScreen.DEPARTMENTS -> DepartmentMasterScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                onSaved = { saved("部門を保存しました") },
                onBack = { screen = CatalogScreen.MENU },
            )

            CatalogScreen.GROUPS -> GroupMasterScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                onSaved = { saved("グループを保存しました") },
                onBack = { screen = CatalogScreen.MENU },
            )

            CatalogScreen.LAYOUT -> ButtonLayoutScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                onSaved = { saved("商品ボタン配置を保存しました") },
                onBack = { screen = CatalogScreen.MENU },
            )

            CatalogScreen.TAXES -> TaxMasterScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                onSaved = { saved("税区分マスターを保存しました") },
                onBack = { screen = CatalogScreen.MENU },
            )

            CatalogScreen.PROFILES -> SalesProfileScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                onSaved = { saved(it) },
                onBack = { screen = CatalogScreen.MENU },
            )
        }
    }
}

@Composable
private fun CatalogMenuScreen(
    store: CatalogMasterStore,
    refresh: Int,
    message: String?,
    onProducts: () -> Unit,
    onDepartments: () -> Unit,
    onGroups: () -> Unit,
    onLayout: () -> Unit,
    onTaxes: () -> Unit,
    onProfiles: () -> Unit,
    onDynamic: () -> Unit,
    onClose: () -> Unit,
) {
    val products = remember(refresh) { store.listProducts() }
    val departments = remember(refresh) { store.listDepartments() }
    val groups = remember(refresh) { store.listGroups() }
    val activeProfile = remember(refresh) { store.activeProfile() }
    Column(Modifier.fillMaxSize()) {
        CatalogHeader("SCR-200", "商品・分類・税・販売プロファイル", onClose)
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SummaryCard("商品", "${products.count { it.enabled }}件", Modifier.weight(1f))
                SummaryCard("部門", "${departments.count { it.enabled }}件", Modifier.weight(1f))
                SummaryCard("グループ", "${groups.count { it.enabled }}件", Modifier.weight(1f))
                SummaryCard("現在の販売プロファイル", activeProfile?.name ?: "未設定", Modifier.weight(1.4f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = onDynamic,
                    modifier = Modifier.width(240.dp).height(46.dp),
                ) { Text("任意税率・メニュー改定", fontWeight = FontWeight.Bold) }
            }
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(message, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MenuTile("SCR-210", "商品マスター", "商品コード・名称・価格・税区分・所属", onProducts, Modifier.weight(1f))
                MenuTile("SCR-220", "部門マスター", "商品が所属する部門", onDepartments, Modifier.weight(1f))
                MenuTile("SCR-230", "グループマスター", "部門配下の分析・表示グループ", onGroups, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MenuTile("SCR-240", "商品ボタン配置", "最大9ページ・各24ボタン", onLayout, Modifier.weight(1f))
                MenuTile("SCR-250", "税区分マスター", "非課税・10%内外税・8%内外税", onTaxes, Modifier.weight(1f))
                MenuTile("SCR-260", "販売プロファイル", "時間帯別価格・税区分", onProfiles, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "保存後、販売画面へ戻ると商品ボタンと現在時刻の販売プロファイルが自動反映されます。保留済み伝票は登録時の価格・税区分スナップショットを維持します。",
                color = Color.DarkGray,
            )
        }
    }
}

@Composable
private fun DepartmentMasterScreen(
    store: CatalogMasterStore,
    refresh: Int,
    actor: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val rows = remember(refresh) { store.listDepartments() }
    var selected by remember { mutableStateOf<DepartmentRecord?>(null) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var order by remember { mutableStateOf("10") }
    var enabled by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected?.id) {
        code = selected?.code.orEmpty()
        name = selected?.name.orEmpty()
        order = selected?.displayOrder?.toString() ?: "10"
        enabled = selected?.enabled ?: true
        error = null
    }

    MasterSplitScreen("SCR-220", "部門マスター", onBack, left = {
        MasterListHeader("部門一覧", onNew = { selected = null })
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                MasterListRow(
                    title = "${row.code}  ${row.name}",
                    subtitle = "表示順 ${row.displayOrder} / ${if (row.enabled) "有効" else "停止"}",
                    selected = selected?.id == row.id,
                    onClick = { selected = row },
                )
            }
        }
    }, right = {
        Text(if (selected == null) "部門を追加" else "部門を編集", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = CmNavy)
        Spacer(Modifier.height(12.dp))
        MasterField(code, { code = it }, "部門コード", enabled = selected == null)
        MasterField(name, { name = it }, "部門名")
        MasterField(order, { order = it.filter(Char::isDigit).take(4) }, "表示順", KeyboardType.Number)
        EnabledCheck(enabled, { enabled = it })
        ErrorText(error)
        Spacer(Modifier.weight(1f))
        SaveButton {
            error = runCatching {
                store.saveDepartment(selected?.id, code, name, enabled, order.toIntOrNull() ?: 0, actor)
                onSaved()
                selected = null
            }.exceptionOrNull()?.message
        }
    })
}

@Composable
private fun GroupMasterScreen(
    store: CatalogMasterStore,
    refresh: Int,
    actor: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val rows = remember(refresh) { store.listGroups() }
    val departments = remember(refresh) { store.listDepartments().filter { it.enabled } }
    var selected by remember { mutableStateOf<ProductGroupRecord?>(null) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var departmentId by remember { mutableStateOf<Long?>(null) }
    var order by remember { mutableStateOf("10") }
    var enabled by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected?.id) {
        code = selected?.code.orEmpty()
        name = selected?.name.orEmpty()
        departmentId = selected?.departmentId ?: departments.firstOrNull()?.id
        order = selected?.displayOrder?.toString() ?: "10"
        enabled = selected?.enabled ?: true
        error = null
    }

    MasterSplitScreen("SCR-230", "グループマスター", onBack, left = {
        MasterListHeader("グループ一覧", onNew = { selected = null })
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                val dept = departments.firstOrNull { it.id == row.departmentId }?.name ?: "部門未設定"
                MasterListRow(
                    title = "${row.code}  ${row.name}",
                    subtitle = "$dept / 表示順 ${row.displayOrder} / ${if (row.enabled) "有効" else "停止"}",
                    selected = selected?.id == row.id,
                    onClick = { selected = row },
                )
            }
        }
    }, right = {
        Text(if (selected == null) "グループを追加" else "グループを編集", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = CmNavy)
        Spacer(Modifier.height(12.dp))
        MasterField(code, { code = it }, "グループコード", enabled = selected == null)
        MasterField(name, { name = it }, "グループ名")
        CycleButton("所属部門", departments.firstOrNull { it.id == departmentId }?.name ?: "未設定") {
            departmentId = nextId(departments.map { it.id }, departmentId)
        }
        MasterField(order, { order = it.filter(Char::isDigit).take(4) }, "表示順", KeyboardType.Number)
        EnabledCheck(enabled, { enabled = it })
        ErrorText(error)
        Spacer(Modifier.weight(1f))
        SaveButton {
            error = runCatching {
                store.saveGroup(selected?.id, code, name, departmentId, enabled, order.toIntOrNull() ?: 0, actor)
                onSaved()
                selected = null
            }.exceptionOrNull()?.message
        }
    })
}

@Composable
private fun ProductMasterScreen(
    store: CatalogMasterStore,
    refresh: Int,
    actor: String,
    initialBarcode: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val products = remember(refresh) { store.listProducts() }
    val departments = remember(refresh) { store.listDepartments().filter { it.enabled } }
    val groups = remember(refresh) { store.listGroups().filter { it.enabled } }
    var selected by remember { mutableStateOf<ProductMasterRecord?>(null) }
    var productId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var kana by remember { mutableStateOf("") }
    var barcode by remember(initialBarcode) { mutableStateOf(initialBarcode) }
    var price by remember { mutableStateOf("0") }
    var tax by remember { mutableStateOf(TaxCategory.INCLUDED_10) }
    var departmentId by remember { mutableStateOf<Long?>(null) }
    var groupId by remember { mutableStateOf<Long?>(null) }
    var enabled by remember { mutableStateOf(true) }
    var color by remember { mutableStateOf("BLUE") }
    var page by remember { mutableStateOf("1") }
    var slot by remember { mutableStateOf("1") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected?.productId) {
        productId = selected?.productId.orEmpty()
        name = selected?.name.orEmpty()
        kana = selected?.kana.orEmpty()
        barcode = selected?.barcode ?: initialBarcode
        price = selected?.basePrice?.toString() ?: "0"
        tax = selected?.baseTaxCategory ?: TaxCategory.INCLUDED_10
        departmentId = selected?.departmentId ?: departments.firstOrNull()?.id
        groupId = selected?.groupId
        enabled = selected?.enabled ?: true
        color = selected?.buttonColor ?: "BLUE"
        page = selected?.pageNo?.toString() ?: "1"
        slot = selected?.slotNo?.toString() ?: nextFreeSlot(products).toString()
        error = null
    }

    MasterSplitScreen("SCR-210", "商品マスター", onBack, leftWeight = 0.44f, left = {
        MasterListHeader("商品一覧 ${products.size}件", onNew = { selected = null })
        LazyColumn(Modifier.fillMaxSize()) {
            items(products, key = { it.productId }) { row ->
                val dept = departments.firstOrNull { it.id == row.departmentId }?.name ?: "未分類"
                MasterListRow(
                    title = "${row.productId}  ${row.name}",
                    subtitle = "${row.basePrice}円 / ${row.baseTaxCategory.displayName} / $dept / ${row.pageNo}P-${row.slotNo}",
                    selected = selected?.productId == row.productId,
                    disabled = !row.enabled,
                    onClick = { selected = row },
                )
            }
        }
    }, right = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(if (selected == null) "商品を追加" else "商品を編集", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = CmNavy)
            Spacer(Modifier.height(10.dp))
            MasterField(productId, { productId = it }, "商品コード", enabled = selected == null)
            MasterField(name, { name = it }, "商品名")
            MasterField(kana, { kana = it.take(60) }, "かな（検索用・任意）")
            MasterField(barcode, { barcode = it.filterNot(Char::isWhitespace).take(64) }, "バーコード（任意・一意）")
            MasterField(price, { price = it.filter(Char::isDigit).take(8) }, "基準価格（円）", KeyboardType.Number)
            CycleButton("税区分", tax.displayName) { tax = TaxCategory.entries[(tax.ordinal + 1) % TaxCategory.entries.size] }
            CycleButton("部門", departments.firstOrNull { it.id == departmentId }?.name ?: "未設定") {
                departmentId = nextId(departments.map { it.id }, departmentId)
                val validGroups = groups.filter { it.departmentId == departmentId }
                if (groupId !in validGroups.map { it.id }) groupId = validGroups.firstOrNull()?.id
            }
            val availableGroups = groups.filter { departmentId == null || it.departmentId == departmentId }
            CycleButton("グループ", availableGroups.firstOrNull { it.id == groupId }?.name ?: "未設定") {
                groupId = nextNullableId(availableGroups.map { it.id }, groupId)
            }
            CycleButton("ボタン色", color) {
                val colors = CatalogMasterStore.BUTTON_COLORS.toList().sorted()
                color = colors[(colors.indexOf(color).coerceAtLeast(0) + 1) % colors.size]
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { MasterField(page, { page = it.filter(Char::isDigit).take(1) }, "ページ", KeyboardType.Number) }
                Box(Modifier.weight(1f)) { MasterField(slot, { slot = it.filter(Char::isDigit).take(2) }, "位置1～24", KeyboardType.Number) }
            }
            EnabledCheck(enabled, { enabled = it })
            ErrorText(error)
            Spacer(Modifier.height(14.dp))
            SaveButton {
                error = runCatching {
                    store.saveProduct(
                        originalId = selected?.productId,
                        productId = productId,
                        name = name,
                        basePrice = price.toLongOrNull() ?: 0,
                        taxCategory = tax,
                        departmentId = departmentId,
                        groupId = groupId,
                        enabled = enabled,
                        buttonColor = color,
                        pageNo = page.toIntOrNull() ?: 1,
                        slotNo = slot.toIntOrNull() ?: 1,
                        actor = actor,
                        kana = kana,
                        barcode = barcode,
                    )
                    onSaved()
                    selected = null
                }.exceptionOrNull()?.message
            }
            Spacer(Modifier.height(18.dp))
        }
    })
}

@Composable
private fun ButtonLayoutScreen(
    store: CatalogMasterStore,
    refresh: Int,
    actor: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val products = remember(refresh) { store.listProducts(false) }
    var selectedId by remember { mutableStateOf(products.firstOrNull()?.productId) }
    var page by remember { mutableIntStateOf(products.firstOrNull()?.pageNo ?: 1) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = products.firstOrNull { it.productId == selectedId }

    Column(Modifier.fillMaxSize()) {
        CatalogHeader("SCR-240", "商品ボタン配置", onBack)
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CatalogPanel(Modifier.width(330.dp).fillMaxHeight()) {
                Text("移動する商品", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = CmNavy)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(products, key = { it.productId }) { product ->
                        MasterListRow(
                            title = product.name,
                            subtitle = "${product.pageNo}P-${product.slotNo} / ${product.basePrice}円",
                            selected = selectedId == product.productId,
                            onClick = { selectedId = product.productId; page = product.pageNo; error = null },
                        )
                    }
                }
            }
            CatalogPanel(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ページ $page", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = CmNavy)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { page = if (page <= 1) 9 else page - 1 }) { Text("前") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { page = if (page >= 9) 1 else page + 1 }) { Text("次") }
                }
                Text("商品を選択して配置先を押してください。配置済み位置へ移動すると2商品を入れ替えます。", color = Color.DarkGray)
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f)) {
                    repeat(6) { row ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(4) { column ->
                                val slotNo = row * 4 + column + 1
                                val product = products.firstOrNull { it.pageNo == page && it.slotNo == slotNo }
                                Button(
                                    onClick = {
                                        val target = selected
                                        if (target != null) {
                                            error = runCatching {
                                                store.moveProduct(target.productId, page, slotNo, actor)
                                                onSaved()
                                            }.exceptionOrNull()?.message
                                        }
                                    },
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = layoutColor(product?.buttonColor),
                                        contentColor = CmNavy,
                                    ),
                                    border = BorderStroke(if (product?.productId == selectedId) 3.dp else 1.dp, if (product?.productId == selectedId) CmDanger else CmBorder),
                                ) {
                                    Text(
                                        if (product == null) "$slotNo\n空き" else "$slotNo\n${product.name}\n${product.basePrice}円",
                                        textAlign = TextAlign.Center,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
                ErrorText(error)
            }
        }
    }
}

@Composable
private fun TaxMasterScreen(
    store: CatalogMasterStore,
    refresh: Int,
    actor: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val rows = remember(refresh) { store.listTaxMasters() }
    var selected by remember { mutableStateOf(rows.firstOrNull()) }
    var label by remember { mutableStateOf(selected?.label.orEmpty()) }
    var enabled by remember { mutableStateOf(selected?.enabled ?: true) }
    var validFrom by remember { mutableStateOf(selected?.validFrom.orEmpty()) }
    var validTo by remember { mutableStateOf(selected?.validTo.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected?.systemKey) {
        label = selected?.label.orEmpty()
        enabled = selected?.enabled ?: true
        validFrom = selected?.validFrom.orEmpty()
        validTo = selected?.validTo.orEmpty()
        error = null
    }

    MasterSplitScreen("SCR-250", "税区分マスター", onBack, left = {
        Text("税区分一覧", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = CmNavy)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.systemKey }) { row ->
                MasterListRow(
                    title = row.label,
                    subtitle = "${row.ratePercent}% / ${row.priceMode} / ${if (row.reduced) "軽減" else "標準"} / ${if (row.enabled) "有効" else "停止"}",
                    selected = selected?.systemKey == row.systemKey,
                    onClick = { selected = row },
                )
            }
        }
    }, right = {
        val row = selected
        Text("税区分を編集", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = CmNavy)
        Spacer(Modifier.height(12.dp))
        if (row != null) {
            ValueLine("システムキー", row.systemKey)
            ValueLine("税率", "${row.ratePercent}%")
            ValueLine("課税方式", row.priceMode)
            MasterField(label, { label = it }, "表示名")
            MasterField(validFrom, { validFrom = it.take(10) }, "適用開始日 YYYY-MM-DD（任意）")
            MasterField(validTo, { validTo = it.take(10) }, "適用終了日 YYYY-MM-DD（任意）")
            EnabledCheck(enabled, { enabled = it })
            Text("税率・内外税方式は会計整合性のため、この版ではシステム5区分を固定して名称・有効期間のみ編集します。", color = Color.DarkGray, fontSize = 13.sp)
            ErrorText(error)
            Spacer(Modifier.weight(1f))
            SaveButton {
                error = runCatching {
                    store.saveTaxMaster(row.copy(label = label, enabled = enabled, validFrom = validFrom, validTo = validTo), actor)
                    onSaved()
                }.exceptionOrNull()?.message
            }
        }
    })
}

@Composable
private fun SalesProfileScreen(
    store: CatalogMasterStore,
    refresh: Int,
    actor: String,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
) {
    val profiles = remember(refresh) { store.listProfiles() }
    val products = remember(refresh) { store.listProducts() }
    var selected by remember { mutableStateOf<SalesProfileRecord?>(profiles.firstOrNull()) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("00:00") }
    var end by remember { mutableStateOf("00:00") }
    var priority by remember { mutableStateOf("0") }
    var enabled by remember { mutableStateOf(true) }
    var isDefault by remember { mutableStateOf(false) }
    var overrideProductId by remember { mutableStateOf(products.firstOrNull()?.productId) }
    var overridePrice by remember { mutableStateOf("") }
    var overrideTax by remember { mutableStateOf<TaxCategory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected?.id, refresh) {
        code = selected?.code.orEmpty()
        name = selected?.name.orEmpty()
        start = selected?.let { SalesProfileRecord.minuteText(it.startMinute) } ?: "00:00"
        end = selected?.let { SalesProfileRecord.minuteText(it.endMinute) } ?: "00:00"
        priority = selected?.priority?.toString() ?: "0"
        enabled = selected?.enabled ?: true
        isDefault = selected?.isDefault ?: false
        val currentOverride = selected?.let { profile -> store.listOverrides(profile.id).firstOrNull { it.productId == overrideProductId } }
        overridePrice = currentOverride?.unitPrice?.toString().orEmpty()
        overrideTax = currentOverride?.taxCategory
        error = null
    }

    Column(Modifier.fillMaxSize()) {
        CatalogHeader("SCR-260", "販売プロファイル", onBack)
        Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CatalogPanel(Modifier.width(330.dp).fillMaxHeight()) {
                MasterListHeader("プロファイル一覧", onNew = { selected = null })
                LazyColumn(Modifier.weight(1f)) {
                    items(profiles, key = { it.id }) { row ->
                        MasterListRow(
                            title = "${row.code}  ${row.name}",
                            subtitle = "${row.timeLabel} / 優先${row.priority} / ${if (row.isDefault) "既定" else "時間帯"}",
                            selected = selected?.id == row.id,
                            disabled = !row.enabled,
                            onClick = { selected = row },
                        )
                    }
                }
                Text("現在：${store.activeProfile()?.name ?: "未設定"}", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            }
            CatalogPanel(Modifier.weight(0.95f).fillMaxHeight()) {
                Text(if (selected == null) "プロファイルを追加" else "プロファイルを編集", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CmNavy)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { MasterField(code, { code = it }, "コード", enabled = selected == null) }
                    Box(Modifier.weight(1f)) { MasterField(name, { name = it }, "名称") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { MasterField(start, { start = it.take(5) }, "開始 HH:mm") }
                    Box(Modifier.weight(1f)) { MasterField(end, { end = it.take(5) }, "終了 HH:mm") }
                    Box(Modifier.weight(0.7f)) { MasterField(priority, { priority = it.filter(Char::isDigit).take(4) }, "優先度", KeyboardType.Number) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(enabled, { enabled = it })
                    Text("有効")
                    Spacer(Modifier.width(18.dp))
                    Checkbox(isDefault, { isDefault = it })
                    Text("既定プロファイル")
                }
                Text("開始＝終了は終日。日付をまたぐ時間帯（例 17:00～02:00）にも対応します。", color = Color.DarkGray, fontSize = 13.sp)
                ErrorText(error)
                Spacer(Modifier.weight(1f))
                SaveButton {
                    error = runCatching {
                        val id = store.saveProfile(
                            id = selected?.id,
                            code = code,
                            name = name,
                            enabled = enabled,
                            startMinute = CatalogValidation.parseTime(start),
                            endMinute = CatalogValidation.parseTime(end),
                            priority = priority.toIntOrNull() ?: 0,
                            isDefault = isDefault,
                            actor = actor,
                        )
                        selected = store.listProfiles().firstOrNull { it.id == id }
                        onSaved("販売プロファイルを保存しました")
                    }.exceptionOrNull()?.message
                }
            }
            CatalogPanel(Modifier.weight(1.05f).fillMaxHeight()) {
                Text("商品別の価格・税区分", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CmNavy)
                Text("選択中プロファイルだけに適用する上書きです。空欄は商品の基準設定を使用します。", color = Color.DarkGray, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                CycleButton("商品", products.firstOrNull { it.productId == overrideProductId }?.name ?: "商品未登録") {
                    overrideProductId = nextString(products.map { it.productId }, overrideProductId)
                    val current = selected?.let { p -> store.listOverrides(p.id).firstOrNull { it.productId == overrideProductId } }
                    overridePrice = current?.unitPrice?.toString().orEmpty()
                    overrideTax = current?.taxCategory
                }
                MasterField(overridePrice, { overridePrice = it.filter(Char::isDigit).take(8) }, "上書き価格（空欄＝基準）", KeyboardType.Number)
                CycleButton("上書き税区分", overrideTax?.displayName ?: "基準税区分") {
                    overrideTax = when (val current = overrideTax) {
                        null -> TaxCategory.entries.first()
                        TaxCategory.entries.last() -> null
                        else -> TaxCategory.entries[current.ordinal + 1]
                    }
                }
                val product = products.firstOrNull { it.productId == overrideProductId }
                if (product != null) {
                    ValueLine("基準", "${product.basePrice}円 / ${product.baseTaxCategory.displayName}")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val profile = selected
                        val productId = overrideProductId
                        if (profile == null || productId == null) {
                            error = "先に販売プロファイルと商品を選択してください"
                        } else {
                            error = runCatching {
                                store.saveOverride(profile.id, productId, overridePrice.toLongOrNull(), overrideTax, actor)
                                onSaved("商品別プロファイル設定を保存しました")
                            }.exceptionOrNull()?.message
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CmBlue),
                ) { Text("商品別設定を保存", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun MasterSplitScreen(
    screenId: String,
    title: String,
    onBack: () -> Unit,
    leftWeight: Float = 0.46f,
    left: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    right: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        CatalogHeader(screenId, title, onBack)
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CatalogPanel(Modifier.weight(leftWeight).fillMaxHeight(), left)
            CatalogPanel(Modifier.weight(1f - leftWeight).fillMaxHeight(), right)
        }
    }
}

@Composable
private fun CatalogHeader(screenId: String, title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).background(CmNavy).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(18.dp))
        Text("$screenId  $title", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, border = BorderStroke(1.dp, Color.White)) {
            Text("戻る", color = Color.White)
        }
    }
}

@Composable
private fun CatalogPanel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, CmBorder),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), content = content)
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, CmBorder)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Color.DarkGray)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CmNavy)
        }
    }
}

@Composable
private fun MenuTile(screenId: String, title: String, description: String, onClick: () -> Unit, modifier: Modifier) {
    Card(
        modifier.height(155.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CmBorder),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text(screenId, color = CmBlue, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CmNavy)
            Spacer(Modifier.height(6.dp))
            Text(description, color = Color.DarkGray)
        }
    }
}

@Composable
private fun MasterListHeader(title: String, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = CmNavy)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onNew) { Text("新規") }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MasterListRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    disabled: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) CmPaleBlue else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = if (disabled) Color.Gray else CmNavy)
        Text(subtitle, fontSize = 13.sp, color = if (disabled) CmDanger else Color.DarkGray)
    }
}

@Composable
private fun MasterField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun CycleButton(label: String, value: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(54.dp).padding(vertical = 3.dp)) {
        Text("$label：$value", textAlign = TextAlign.Center)
    }
}

@Composable
private fun EnabledCheck(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = enabled, onCheckedChange = onChanged)
        Text(if (enabled) "有効" else "停止", fontWeight = FontWeight.Bold, color = if (enabled) Color(0xFF2E7D32) else CmDanger)
    }
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CmBlue),
    ) { Text("保存", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(error, color = CmDanger, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ValueLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, modifier = Modifier.width(130.dp), color = Color.DarkGray)
        Text(value, fontWeight = FontWeight.Bold, color = CmNavy)
    }
}

private fun nextId(values: List<Long>, current: Long?): Long? {
    if (values.isEmpty()) return null
    val index = values.indexOf(current)
    return values[(index + 1).coerceAtLeast(0) % values.size]
}

private fun nextNullableId(values: List<Long>, current: Long?): Long? {
    if (values.isEmpty()) return null
    if (current == null) return values.first()
    val index = values.indexOf(current)
    return if (index < 0 || index == values.lastIndex) null else values[index + 1]
}

private fun nextString(values: List<String>, current: String?): String? {
    if (values.isEmpty()) return null
    val index = values.indexOf(current)
    return values[(index + 1).coerceAtLeast(0) % values.size]
}

private fun nextFreeSlot(products: List<ProductMasterRecord>): Int =
    (1..24).firstOrNull { slot -> products.none { it.pageNo == 1 && it.slotNo == slot } } ?: 1

private fun layoutColor(name: String?): Color = when (name) {
    "GREEN" -> CmPaleGreen
    "YELLOW" -> CmPaleYellow
    "PINK" -> Color(0xFFFFEAF0)
    "GRAY" -> Color(0xFFECEFF1)
    "WHITE" -> Color.White
    else -> CmPaleBlue
}
