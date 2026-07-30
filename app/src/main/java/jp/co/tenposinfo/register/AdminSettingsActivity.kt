package jp.co.tenposinfo.register

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AsNavy = Color(0xFF173F6B)
private val AsBlue = Color(0xFF1976B9)
private val AsDanger = Color(0xFFC62828)
private val AsGreen = Color(0xFF2E7D32)
private val AsBackground = Color(0xFFF4F7FA)
private val AsBorder = Color(0xFFD5DEE7)
private val AsPaleBlue = Color(0xFFEAF3FA)
private val AsPaleGreen = Color(0xFFEAF5EC)
private val AsPaleYellow = Color(0xFFFFF4D9)

class AdminSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AdminSettingsApp(onClose = { finish() })
            }
        }
    }
}

private enum class AdminScreen {
    MENU,
    OPERATORS,
    PRINTER,
    SECURITY,
    AUDIT,
}

@Composable
private fun AdminSettingsApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AdminSettingsStore(context.applicationContext) }
    var unlocked by remember { mutableStateOf(false) }
    var actorName by remember { mutableStateOf("責任者") }
    var screen by remember { mutableStateOf(AdminScreen.MENU) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }

    Surface(Modifier.fillMaxSize(), color = AsBackground) {
        if (!unlocked) {
            AdminUnlockScreen(
                onVerify = { pin ->
                    val name = store.managerNameForPin(pin)
                    if (name == null) {
                        false
                    } else {
                        actorName = name
                        unlocked = true
                        true
                    }
                },
                onClose = onClose,
            )
        } else {
            when (screen) {
                AdminScreen.MENU -> AdminMenuScreen(
                    operatorCount = store.listOperators().count { it.enabled },
                    printer = store.loadPrinterConfiguration(),
                    auditCount = store.auditCount(),
                    actorName = actorName,
                    onOperators = { screen = AdminScreen.OPERATORS },
                    onPrinter = { screen = AdminScreen.PRINTER },
                    onSecurity = { screen = AdminScreen.SECURITY },
                    onAudit = { screen = AdminScreen.AUDIT },
                    onLock = { unlocked = false },
                    onClose = onClose,
                )

                AdminScreen.OPERATORS -> OperatorMasterScreen(
                    store = store,
                    actorName = actorName,
                    onBack = { screen = AdminScreen.MENU },
                )

                AdminScreen.PRINTER -> PrinterSettingsScreen(
                    store = store,
                    actorName = actorName,
                    onBack = { screen = AdminScreen.MENU },
                )

                AdminScreen.SECURITY -> ManagerPinScreen(
                    store = store,
                    actorName = actorName,
                    onPinChanged = { unlocked = false },
                    onBack = { screen = AdminScreen.MENU },
                )

                AdminScreen.AUDIT -> AuditLogScreen(
                    store = store,
                    onBack = { screen = AdminScreen.MENU },
                )
            }
        }
    }
}

@Composable
private fun AdminUnlockScreen(
    onVerify: (String) -> Boolean,
    onClose: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsPanel(Modifier.width(520.dp).height(430.dp)) {
            Text("各種設定", fontSize = 31.sp, fontWeight = FontWeight.Bold, color = AsNavy)
            Spacer(Modifier.height(8.dp))
            Text("責任者認証", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                label = { Text("責任者PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text("初期責任者PIN：0000", color = Color.Gray)
            if (message != null) {
                Spacer(Modifier.height(10.dp))
                Text(message.orEmpty(), color = AsDanger, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("閉じる")
                }
                Button(
                    onClick = {
                        if (onVerify(pin)) {
                            pin = ""
                            message = null
                        } else {
                            message = "責任者PINが違います"
                        }
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AsBlue),
                ) { Text("認証", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun AdminMenuScreen(
    operatorCount: Int,
    printer: PrinterConfiguration,
    auditCount: Long,
    actorName: String,
    onOperators: () -> Unit,
    onPrinter: () -> Unit,
    onSecurity: () -> Unit,
    onAudit: () -> Unit,
    onLock: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AsHeader("SCR-760", "各種設定", "認証：$actorName")
        Row(Modifier.weight(1f).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AsPanel(Modifier.width(360.dp).fillMaxHeight()) {
                Text("設定状態", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AsNavy)
                Spacer(Modifier.height(18.dp))
                AsValueRow("有効担当者", "${operatorCount}名")
                AsValueRow("プリンター", if (printer.usable) "接続設定済み" else "未設定")
                AsValueRow("接続先", if (printer.host.isBlank()) "－" else "${printer.host}:${printer.port}")
                AsValueRow("用紙幅", "${printer.paperWidthMm}mm")
                AsValueRow("監査ログ", "${auditCount}件")
                Spacer(Modifier.weight(1f))
                Text(
                    "担当者・権限、責任者PIN、実プリンター、監査ログを端末内SQLiteで管理します。",
                    color = Color.DarkGray,
                    lineHeight = 23.sp,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsMenuTile("担当者・権限", "担当者登録、停止、並び順、権限", AsPaleBlue, Modifier.weight(1f), onOperators)
                    AsMenuTile("プリンター設定", "IP、TCP 9100、58/80mm、実機テスト", AsPaleGreen, Modifier.weight(1f), onPrinter)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsMenuTile("責任者PIN", "PBKDF2ハッシュでPINを更新", AsPaleYellow, Modifier.weight(1f), onSecurity)
                    AsMenuTile("監査ログ", "設定、返品、精算、入出金を確認", Color(0xFFF0EAF8), Modifier.weight(1f), onAudit)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(74.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onClose, modifier = Modifier.width(220.dp).fillMaxHeight()) { Text("販売へ戻る") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onLock, modifier = Modifier.width(220.dp).fillMaxHeight()) { Text("設定をロック") }
        }
    }
}

@Composable
private fun OperatorMasterScreen(
    store: AdminSettingsStore,
    actorName: String,
    onBack: () -> Unit,
) {
    var revision by remember { mutableStateOf(0) }
    val operators = remember(revision) { store.listOperators() }
    var selectedId by remember { mutableStateOf<Long?>(operators.firstOrNull()?.id) }
    val selected = operators.firstOrNull { it.id == selectedId }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(OperatorRole.CASHIER) }
    var enabled by remember { mutableStateOf(true) }
    var pin by remember { mutableStateOf("") }
    var permissions by remember { mutableStateOf(OperatorPermissionPolicy.defaults(OperatorRole.CASHIER)) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected?.id, selected?.updatedAt) {
        if (selected == null) {
            code = ""
            name = ""
            role = OperatorRole.CASHIER
            enabled = true
            pin = ""
            permissions = OperatorPermissionPolicy.defaults(role)
        } else {
            code = selected.code
            name = selected.name
            role = selected.role
            enabled = selected.enabled
            pin = ""
            permissions = selected.permissions
        }
    }

    Column(Modifier.fillMaxSize()) {
        AsHeader("SCR-761", "担当者・権限マスター", "有効 ${operators.count { it.enabled }}名")
        Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AsPanel(Modifier.width(420.dp).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("担当者一覧", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AsNavy)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { selectedId = null; message = null },
                        colors = ButtonDefaults.buttonColors(containerColor = AsBlue),
                    ) { Text("新規") }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(operators, key = { it.id }) { operator ->
                        val selectedRow = operator.id == selectedId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (selectedRow) AsPaleBlue else Color.Transparent)
                                .clickable { selectedId = operator.id; message = null }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(operator.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Spacer(Modifier.weight(1f))
                                Text(operator.role.displayName, color = AsNavy)
                            }
                            Text("${operator.code} / ${if (operator.enabled) "有効" else "停止"}", color = if (operator.enabled) AsGreen else AsDanger)
                            Text("権限 ${operator.permissions.size}件", color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
                if (selected != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { store.moveOperator(selected.id, -1, actorName); revision++ },
                            modifier = Modifier.weight(1f),
                        ) { Text("上へ") }
                        OutlinedButton(
                            onClick = { store.moveOperator(selected.id, 1, actorName); revision++ },
                            modifier = Modifier.weight(1f),
                        ) { Text("下へ") }
                    }
                }
            }

            AsPanel(Modifier.width(430.dp).fillMaxHeight()) {
                Text(if (selected == null) "担当者を追加" else "担当者を編集", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AsNavy)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(code, { code = it.uppercase().take(20) }, label = { Text("担当者コード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("表示名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsChoiceButton("担当者", role == OperatorRole.CASHIER, Modifier.weight(1f)) {
                        role = OperatorRole.CASHIER
                        permissions = OperatorPermissionPolicy.defaults(role)
                    }
                    AsChoiceButton("責任者", role == OperatorRole.MANAGER, Modifier.weight(1f)) {
                        role = OperatorRole.MANAGER
                        permissions = OperatorPermissionPolicy.defaults(role)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("有効")
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text(if (selected == null) "PIN（必須）" else "PIN（変更時のみ入力）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val result = runCatching {
                            store.saveOperator(selected?.id, code, name, role, enabled, pin.ifBlank { null }, permissions, actorName)
                        }
                        message = result.fold(
                            onSuccess = { "保存しました（ID $it）" },
                            onFailure = { it.message ?: "保存に失敗しました" },
                        )
                        if (result.isSuccess) {
                            revision++
                            selectedId = result.getOrNull()
                            pin = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AsBlue),
                ) { Text("保存", fontWeight = FontWeight.Bold) }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message.orEmpty(), color = resultColor(message.orEmpty()))
                }
            }

            AsPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("権限", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AsNavy)
                Spacer(Modifier.height(6.dp))
                Text("担当者ごとに許可する機能を選択します。", color = Color.DarkGray)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RegisterPermission.entries.forEach { permission ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                permissions = if (permission in permissions) permissions - permission else permissions + permission
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = permission in permissions,
                                onCheckedChange = { checked ->
                                    permissions = if (checked) permissions + permission else permissions - permission
                                },
                            )
                            Column {
                                Text(permission.displayName, fontWeight = FontWeight.SemiBold)
                                Text(permission.name, color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
        AsBottomBar("各種設定へ戻る", onBack)
    }
}

@Composable
private fun PrinterSettingsScreen(
    store: AdminSettingsStore,
    actorName: String,
    onBack: () -> Unit,
) {
    val initial = remember { store.loadPrinterConfiguration() }
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var paperWidth by remember { mutableStateOf(initial.paperWidthMm) }
    var timeout by remember { mutableStateOf(initial.timeoutMillis.toString()) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun currentConfiguration() = PrinterConfiguration(
        name = name,
        host = host,
        port = port.toIntOrNull() ?: 0,
        paperWidthMm = paperWidth,
        timeoutMillis = timeout.toIntOrNull() ?: 0,
        enabled = enabled,
    )

    Column(Modifier.fillMaxSize()) {
        AsHeader("SCR-762", "プリンター設定", "TCP/IP ESC/POS")
        Row(Modifier.weight(1f).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AsPanel(Modifier.width(520.dp).fillMaxHeight()) {
                Text("接続設定", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AsNavy)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("プリンター名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(host, { host = it.take(255) }, label = { Text("IPアドレス／ホスト名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        port,
                        { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("ポート") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        timeout,
                        { timeout = it.filter(Char::isDigit).take(5) },
                        label = { Text("タイムアウトms") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsChoiceButton("58mm", paperWidth == 58, Modifier.weight(1f)) { paperWidth = 58 }
                    AsChoiceButton("80mm", paperWidth == 80, Modifier.weight(1f)) { paperWidth = 80 }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("このプリンターを実印刷に使用")
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val config = currentConfiguration()
                            val result = runCatching { store.savePrinterConfiguration(config, actorName) }
                            message = result.fold(
                                onSuccess = {
                                    PrinterConfigurationRegistry.reload(context.applicationContext)
                                    "設定を保存しました"
                                },
                                onFailure = { it.message ?: "保存に失敗しました" },
                            )
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AsBlue),
                    ) { Text("保存", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = {
                            val config = currentConfiguration()
                            testing = true
                            message = "接続してテスト印刷しています…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { store.testPrinter(config).getOrThrow() } }
                                message = result.fold(
                                    onSuccess = { "テスト印刷データを送信しました" },
                                    onFailure = { "テスト印刷失敗：${it.message ?: it.javaClass.simpleName}" },
                                )
                                withContext(Dispatchers.IO) {
                                    runCatching { store.recordPrinterTest(config, result.isSuccess, message.orEmpty(), actorName) }
                                }
                                testing = false
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AsGreen),
                    ) { Text(if (testing) "送信中" else "実機テスト印刷", fontWeight = FontWeight.Bold) }
                }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message.orEmpty(), color = resultColor(message.orEmpty()), fontWeight = FontWeight.Bold)
                }
            }

            AsPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("印刷経路", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AsNavy)
                Spacer(Modifier.height(14.dp))
                AsFlowStep("1", "会計・精算・返品をSQLiteへ確定")
                AsFlowStep("2", "同一トランザクションで印刷キューを登録")
                AsFlowStep("3", "構造化データからESC/POSを生成")
                AsFlowStep("4", "${host.ifBlank { "IP未設定" }}:${port.ifBlank { "9100" }}へTCP送信")
                AsFlowStep("5", "失敗時はRETRY／FAILEDとして売上と分離")
                Spacer(Modifier.height(16.dp))
                Text("対応", fontWeight = FontWeight.Bold, color = AsNavy)
                Text("・ESC/POS TCP 9100\n・CP932日本語\n・58mm／80mm\n・部分カット\n・接続タイムアウト", lineHeight = 24.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "プリンターを有効にすると、既存の売上レシート・返品票・点検精算票のテスト印刷経路も保存済み接続先へ送信します。",
                    color = Color.DarkGray,
                    lineHeight = 23.sp,
                )
            }
        }
        AsBottomBar("各種設定へ戻る", onBack)
    }
}

@Composable
private fun ManagerPinScreen(
    store: AdminSettingsStore,
    actorName: String,
    onPinChanged: () -> Unit,
    onBack: () -> Unit,
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        AsHeader("SCR-763", "責任者PIN設定", "認証：$actorName")
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AsPanel(Modifier.width(600.dp).height(520.dp)) {
                Text("責任者PINを変更", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = AsNavy)
                Spacer(Modifier.height(10.dp))
                Text("PINは平文保存せず、ランダムソルト付きPBKDF2-HMAC-SHA256で保存します。", color = Color.DarkGray)
                Spacer(Modifier.height(18.dp))
                AsPinField("現在のPIN", currentPin) { currentPin = it }
                Spacer(Modifier.height(8.dp))
                AsPinField("新しいPIN（4～8桁）", newPin) { newPin = it }
                Spacer(Modifier.height(8.dp))
                AsPinField("新しいPIN（確認）", confirmation) { confirmation = it }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val result = runCatching {
                            require(newPin == confirmation) { "新しいPINと確認入力が一致しません" }
                            store.changeManagerPin(currentPin, newPin, actorName)
                        }
                        message = result.fold(
                            onSuccess = { "責任者PINを変更しました。新しいPINで再認証してください" },
                            onFailure = { it.message ?: "PIN変更に失敗しました" },
                        )
                        if (result.isSuccess) {
                            currentPin = ""
                            newPin = ""
                            confirmation = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AsBlue),
                ) { Text("PINを変更", fontWeight = FontWeight.Bold) }
                if (message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(message.orEmpty(), color = resultColor(message.orEmpty()), fontWeight = FontWeight.Bold)
                }
                if (message?.contains("変更しました") == true) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onPinChanged, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("再認証へ")
                    }
                }
            }
        }
        AsBottomBar("各種設定へ戻る", onBack)
    }
}

@Composable
private fun AuditLogScreen(
    store: AdminSettingsStore,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    val logs = remember(query, revision) { store.listAuditLogs(query = query) }

    Column(Modifier.fillMaxSize()) {
        AsHeader("SCR-764", "監査ログ", "${logs.size}件表示")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                label = { Text("イベント・内容・担当者で検索") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { revision++ }, modifier = Modifier.width(150.dp).height(56.dp)) { Text("更新") }
        }
        AsPanel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp)) {
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("該当する監査ログはありません", color = Color.Gray) }
            } else {
                LazyColumn {
                    items(logs, key = { it.id }) { log ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 9.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(log.eventType, color = AsNavy, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.weight(1f))
                                Text(asDateTime(log.createdAt), color = Color.Gray)
                            }
                            Text(log.detail)
                            Text("担当 ${log.operatorName} / Ref.${log.referenceId} / Log.${log.id}", color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        AsBottomBar("各種設定へ戻る", onBack)
    }
}

@Composable
private fun AsPinField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(8)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AsHeader(screenId: String, title: String, status: String) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).background(AsNavy).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("REGISTER", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        Text("$screenId  $title", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(status, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun AsPanel(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, AsBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp), content = content)
    }
}

@Composable
private fun AsMenuTile(title: String, description: String, background: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, AsBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = AsNavy)
            Spacer(Modifier.height(10.dp))
            Text(description, textAlign = TextAlign.Center, color = Color.DarkGray, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun AsChoiceButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) AsDanger else AsBorder),
    ) { Text(label, fontWeight = FontWeight.Bold, color = AsNavy) }
}

@Composable
private fun AsValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AsFlowStep(number: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(38.dp).height(38.dp).background(AsPaleBlue, RoundedCornerShape(19.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(number, fontWeight = FontWeight.Bold, color = AsNavy) }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 17.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AsBottomBar(label: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.width(240.dp).fillMaxHeight()) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

private fun resultColor(message: String): Color = if (
    message.contains("失敗") ||
    message.contains("違い") ||
    message.contains("入力") ||
    message.contains("一致しません") ||
    message.contains("できません")
) AsDanger else AsGreen

private fun asDateTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))
