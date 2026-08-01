from pathlib import Path
from datetime import datetime, timezone

results: list[str] = []


def replace_once(label: str, file: str, old: str, new: str) -> None:
    path = Path(file)
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    results.append(f"{label}: matches={count}")
    if count == 1:
        path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Authenticated settings menu.
replace_once(
    "admin callback invocation",
    "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt",
    '''                    onOperators = { screen = AdminScreen.OPERATORS },
                    onPrinter = { screen = AdminScreen.PRINTER },
                    onSecurity = { screen = AdminScreen.SECURITY },
                    onAudit = { screen = AdminScreen.AUDIT },''',
    '''                    onOperators = { screen = AdminScreen.OPERATORS },
                    onPrinter = { screen = AdminScreen.PRINTER },
                    onCatalog = { context.startActivity(Intent(context, CatalogSettingsActivity::class.java)) },
                    onCustomerDisplay = { context.startActivity(Intent(context, CustomerDisplaySettingsActivity::class.java)) },
                    onPrinterTools = { context.startActivity(Intent(context, PrinterToolsHubActivity::class.java)) },
                    onSecurity = { screen = AdminScreen.SECURITY },
                    onAudit = { screen = AdminScreen.AUDIT },''',
)
replace_once(
    "admin menu layout",
    "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt",
    '''            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsMenuTile("担当者・権限", "担当者登録、停止、並び順、権限", AsPaleBlue, Modifier.weight(1f), onOperators)
                    AsMenuTile("プリンター設定", "機種、IP、58/80mm、カット、ドロア", AsPaleGreen, Modifier.weight(1f), onPrinter)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsMenuTile("責任者PIN", "PBKDF2ハッシュでPINを更新", AsPaleYellow, Modifier.weight(1f), onSecurity)
                    AsMenuTile("監査ログ", "設定、返品、精算、入出金を確認", Color(0xFFF0EAF8), Modifier.weight(1f), onAudit)
                }
            }''',
    '''            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsMenuTile("担当者・権限", "担当者登録、停止、並び順、権限", AsPaleBlue, Modifier.weight(1f), onOperators)
                    AsMenuTile("商品設定", "商品、部門、税区分、価格改定", Color(0xFFE8F0FC), Modifier.weight(1f), onCatalog)
                    AsMenuTile("プリンター設定", "機種、IP、用紙、カット、ドロア", AsPaleGreen, Modifier.weight(1f), onPrinter)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsMenuTile("顧客表示", "つぐレジ CDの接続と表示設定", Color(0xFFEDEBFA), Modifier.weight(1f), onCustomerDisplay)
                    AsMenuTile("プリンター運用", "診断、印刷キュー、検証、試験履歴", Color(0xFFE5F3FA), Modifier.weight(1f), onPrinterTools)
                    AsMenuTile("監査ログ", "設定、返品、精算、入出金を確認", Color(0xFFF0EAF8), Modifier.weight(1f), onAudit)
                }
                Row(Modifier.weight(0.72f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsMenuTile("責任者PIN", "責任者PINを安全に更新", AsPaleYellow, Modifier.weight(1f), onSecurity)
                    Spacer(Modifier.weight(2f))
                }
            }''',
)
replace_once(
    "admin tile compact text",
    "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt",
    '''            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = AsNavy)
            Spacer(Modifier.height(10.dp))
            Text(description, textAlign = TextAlign.Center, color = Color.DarkGray, lineHeight = 22.sp)''',
    '''            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AsNavy)
            Spacer(Modifier.height(6.dp))
            Text(description, textAlign = TextAlign.Center, color = Color.DarkGray, lineHeight = 18.sp, fontSize = 13.sp)''',
)

# Sales-screen navigation and customer-display accounting state.
replace_once(
    "sales invocation navigation",
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                onPrinterStatus = {
                    context.startActivity(Intent(context, PrinterStatusActivity::class.java))
                },
                accessMessage = accessMessage,''',
    '''                onPrinterStatus = {
                    context.startActivity(Intent(context, PrinterStatusActivity::class.java))
                },
                canOpenSettings = currentOperator?.isManager == true && currentOperator?.allows(RegisterPermission.SETTINGS) == true,
                canOpenManagement = currentOperator?.permissions?.any {
                    it == RegisterPermission.VIEW_SALES ||
                        it == RegisterPermission.CASH_MOVEMENT ||
                        it == RegisterPermission.SETTLEMENT ||
                        it == RegisterPermission.REVERSAL
                } == true,
                onOpenSettings = { context.startActivity(Intent(context, AdminSettingsActivity::class.java)) },
                onOpenManagement = { context.startActivity(Intent(context, OperationsActivity::class.java)) },
                accessMessage = accessMessage,''',
)
replace_once(
    "initial accounting state",
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                onPayment = {
                    paymentState = PaymentState()
                    screen = AppScreen.PAYMENT
                },''',
    '''                onPayment = {
                    paymentState = PaymentState()
                    CustomerDisplayRuntime.publish(
                        CustomerDisplaySnapshotFactory.accounting(
                            cart.toList(),
                            paymentState,
                            CustomerDisplaySettingsStore(context.applicationContext).load().storeName,
                        ),
                    )
                    screen = AppScreen.PAYMENT
                },''',
)
replace_once(
    "payment state and back",
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                onStateChange = { paymentState = it },
                onBack = { screen = AppScreen.SALES },''',
    '''                onStateChange = {
                    paymentState = it
                    CustomerDisplayRuntime.publish(
                        CustomerDisplaySnapshotFactory.accounting(
                            cart.toList(),
                            it,
                            CustomerDisplaySettingsStore(context.applicationContext).load().storeName,
                        ),
                    )
                },
                onBack = {
                    CustomerDisplayRuntime.publish(
                        CustomerDisplaySnapshotFactory.sales(
                            cart.toList(),
                            CustomerDisplaySettingsStore(context.applicationContext).load().storeName,
                        ),
                    )
                    screen = AppScreen.SALES
                },''',
)
replace_once(
    "complete immediate state",
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                    val saleId = database.saveSale(operatorName, cart.toList(), paymentState, receiptPaper.widthMm)
                    AutomaticPrintScheduler.enqueueNow(context.applicationContext)''',
    '''                    val saleId = database.saveSale(operatorName, cart.toList(), paymentState, receiptPaper.widthMm)
                    database.loadSaleDetail(saleId)?.let { detail ->
                        CustomerDisplayRuntime.publish(
                            CustomerDisplaySnapshotFactory.complete(
                                detail,
                                CustomerDisplaySettingsStore(context.applicationContext).load().storeName,
                            ),
                        )
                    }
                    AutomaticPrintScheduler.enqueueNow(context.applicationContext)''',
)

# Payment screen: all payment controls visible at once.
replace_once(
    "payment compact totals",
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                AmountRow("合計", yen(summary.grossAmount), emphasized = true)
                AmountRow("支払済", yen(state.paidAmount))
                AmountRow("残額", yen(remaining), emphasized = true)
                AmountRow("お釣り", yen(state.changeAmount))
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.height(120.dp)) {''',
    '''                PaymentAmountRow("合計", yen(summary.grossAmount), emphasized = true)
                PaymentAmountRow("支払済", yen(state.paidAmount))
                PaymentAmountRow("残額", yen(remaining), emphasized = true)
                PaymentAmountRow("お釣り", yen(state.changeAmount))
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.height(40.dp)) {''',
)
replace_once(
    "payment compact keypad call",
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                    bottomActionLabel = "現金",
                    onBottomAction = { add(PaymentMethod.CASH) },
                )
                Spacer(Modifier.height(8.dp))''',
    '''                    bottomActionLabel = "現金",
                    onBottomAction = { add(PaymentMethod.CASH) },
                    compact = true,
                )
                Spacer(Modifier.height(4.dp))''',
)
replace_once(
    "payment method button heights",
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                    OutlinedButton(onClick = { add(PaymentMethod.CARD) }, enabled = remaining > 0, modifier = Modifier.weight(1f)) { Text("カード") }
                    OutlinedButton(onClick = { add(PaymentMethod.GIFT_CERTIFICATE) }, enabled = remaining > 0, modifier = Modifier.weight(1f)) { Text("商品券") }
                    OutlinedButton(onClick = { add(PaymentMethod.ACCOUNT_RECEIVABLE) }, enabled = remaining > 0, modifier = Modifier.weight(1f)) { Text("掛売") }''',
    '''                    OutlinedButton(onClick = { add(PaymentMethod.CARD) }, enabled = remaining > 0, modifier = Modifier.weight(1f).height(40.dp)) { Text("カード") }
                    OutlinedButton(onClick = { add(PaymentMethod.GIFT_CERTIFICATE) }, enabled = remaining > 0, modifier = Modifier.weight(1f).height(40.dp)) { Text("商品券") }
                    OutlinedButton(onClick = { add(PaymentMethod.ACCOUNT_RECEIVABLE) }, enabled = remaining > 0, modifier = Modifier.weight(1f).height(40.dp)) { Text("掛売") }''',
)

# CD: always reveal the latest added or quantity-changed row.
replace_once(
    "cd latest item list",
    "customer-display/src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt",
    '''            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            ) {
                items(snapshot.orderItems, key = { "${it.productId}-${it.name}" }) { item ->
                    CustomerDisplayItemRow(item, compact)
                }
            }''',
    '''            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            ) {
                itemsIndexed(
                    items = snapshot.orderItems,
                    key = { index, item -> "${item.productId}-${item.unitPrice}-$index" },
                ) { _, item ->
                    CustomerDisplayItemRow(item, compact)
                }
            }''',
)

Path("docs/V013_DEV3_FIXUP_DIAGNOSTIC.txt").write_text(
    datetime.now(timezone.utc).isoformat() + "\n" + "\n".join(results) + "\n",
    encoding="utf-8",
)
