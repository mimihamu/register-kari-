from pathlib import Path

path = Path('customer-display/src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt')
text = path.read_text(encoding='utf-8')

import_anchor = 'import androidx.compose.ui.graphics.Color\n'
if 'import androidx.compose.ui.platform.LocalContext\n' not in text:
    text = text.replace(import_anchor, import_anchor + 'import androidx.compose.ui.platform.LocalContext\n', 1)

start_marker = '@Composable\nprivate fun CustomerDisplayConnectionSettingsScreen('
end_marker = '\nprivate fun yen(value: Long): String'
start = text.index(start_marker)
end = text.index(end_marker, start)

replacement = '''@Composable
private fun CustomerDisplayConnectionSettingsScreen(
    initial: CustomerDisplayConnectionSettings,
    onSave: (CustomerDisplayConnectionSettings) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var host by remember(initial) { mutableStateOf(initial.host) }
    var portText by remember(initial) { mutableStateOf(initial.port.toString()) }
    var token by remember(initial) { mutableStateOf(initial.token) }
    var autoConnect by remember(initial) { mutableStateOf(initial.autoConnect) }
    var message by remember { mutableStateOf<String?>(null) }
    var discoveryState by remember { mutableStateOf(CustomerDisplayDiscoveryState()) }
    var discoveredRegisters by remember { mutableStateOf<List<DiscoveredRegister>>(emptyList()) }
    val discovery = remember(context) {
        CustomerDisplayNsdDiscovery(
            context = context,
            onStateChanged = { state -> discoveryState = state },
            onFound = { candidate ->
                discoveredRegisters = (discoveredRegisters + candidate)
                    .distinctBy { it.identity }
                    .sortedBy { it.storeName }
            },
        )
    }

    DisposableEffect(discovery) {
        onDispose { discovery.stop(null) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layoutMode = CustomerDisplayLayoutPolicy.select(maxWidth.value, maxHeight.value)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF4F7FA))
                .verticalScroll(rememberScrollState())
                .padding(if (layoutMode.compact) 16.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (layoutMode.compact) 11.dp else 16.dp),
        ) {
            if (layoutMode.compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "つぐレジ CD 接続設定",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF173F6B),
                            modifier = Modifier.weight(1f),
                        )
                        if (initial.isConfigured) OutlinedButton(onClick = onCancel) { Text("戻る") }
                    }
                    Text("同じWi-Fiのつぐレジを探すか、接続情報を手入力します。", color = Color(0xFF455A64), fontSize = 13.sp)
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("つぐレジ CD 接続設定", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173F6B))
                        Text("同じWi-Fiのつぐレジを探すか、接続情報を手入力します。", color = Color(0xFF455A64))
                    }
                    if (initial.isConfigured) OutlinedButton(onClick = onCancel) { Text("戻る") }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3FA)),
            ) {
                Column(
                    modifier = Modifier.padding(if (layoutMode.compact) 14.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("かんたん接続", fontWeight = FontWeight.Bold, color = Color(0xFF173F6B), fontSize = if (layoutMode.compact) 19.sp else 22.sp)
                    Text("レジ側の［顧客表示］で［2分間ペアリング受付を開始］を押してから検索します。")
                    Button(
                        onClick = {
                            discoveredRegisters = emptyList()
                            message = null
                            discovery.start()
                        },
                        modifier = if (layoutMode.compact) Modifier.fillMaxWidth() else Modifier,
                    ) {
                        Text(if (discoveryState.status == CustomerDisplayDiscoveryStatus.SEARCHING) "検索し直す" else "同じWi-Fiのレジを探す")
                    }
                    discoveryState.message?.let {
                        Text(
                            it,
                            color = if (discoveryState.status == CustomerDisplayDiscoveryStatus.SEARCHING) Color(0xFF1565C0) else Color(0xFF455A64),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (discoveredRegisters.isEmpty() && discoveryState.status == CustomerDisplayDiscoveryStatus.FINISHED) {
                        Text("見つからない場合は、レジ側が受付中か、両端末が同じWi-Fiか確認してください。", color = Color(0xFFC62828))
                    }
                    discoveredRegisters.forEach { candidate ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                        ) {
                            if (layoutMode.compact) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(candidate.storeName, fontWeight = FontWeight.Bold, color = Color(0xFF173F6B))
                                    Text("${candidate.host}:${candidate.port}", color = Color(0xFF607D8B), fontSize = 12.sp)
                                    Button(
                                        onClick = {
                                            discovery.stop(null)
                                            onSave(candidate.toConnectionSettings())
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("このレジに接続") }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(candidate.storeName, fontWeight = FontWeight.Bold, color = Color(0xFF173F6B))
                                        Text("${candidate.host}:${candidate.port}", color = Color(0xFF607D8B))
                                    }
                                    Button(onClick = {
                                        discovery.stop(null)
                                        onSave(candidate.toConnectionSettings())
                                    }) { Text("このレジに接続") }
                                }
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(
                    modifier = Modifier.padding(if (layoutMode.compact) 14.dp else 22.dp),
                    verticalArrangement = Arrangement.spacedBy(if (layoutMode.compact) 10.dp else 14.dp),
                ) {
                    Text("手動接続", fontWeight = FontWeight.Bold, color = Color(0xFF173F6B))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim().take(255) },
                        label = { Text("レジIPアドレス／ホスト名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text("ポート（初期値 18080）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it.trim().take(128) },
                        label = { Text("接続トークン") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
                        Text("起動時に自動接続")
                    }
                    Text("通信パス：$CUSTOMER_DISPLAY_PATH", color = Color(0xFF607D8B), fontSize = if (layoutMode.compact) 12.sp else 14.sp)
                }
            }

            message?.let { Text(it, color = Color(0xFFC62828), fontWeight = FontWeight.Bold) }

            Button(
                onClick = {
                    val port = portText.toIntOrNull()
                    when {
                        host.isBlank() -> message = "レジIPアドレスを入力してください"
                        port == null || port !in 1024..65535 -> message = "ポートは1024～65535で入力してください"
                        token.length < 16 -> message = "接続トークンを正しく入力してください"
                        else -> onSave(
                            CustomerDisplayConnectionSettings(
                                host = host,
                                port = port,
                                token = token,
                                autoConnect = autoConnect,
                            ),
                        )
                    }
                },
                modifier = if (layoutMode.compact) Modifier.fillMaxWidth() else Modifier,
            ) {
                Text("手動設定を保存して接続")
            }
        }
    }
}
'''

text = text[:start] + replacement + text[end:]
path.write_text(text, encoding='utf-8')
