from __future__ import annotations

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt"
BUILD = ROOT / "app/build.gradle.kts"
WORKFLOW = ROOT / ".github/workflows/build-apk.yml"
RELEASE_NOTES = ROOT / "docs/V0.29_RELEASE_NOTES.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    start_index = text.find(start)
    if start_index < 0:
        raise RuntimeError(f"{label}: start marker not found")
    end_index = text.find(end, start_index + len(start))
    if end_index < 0:
        raise RuntimeError(f"{label}: end marker not found")
    return text[:start_index] + replacement + text[end_index:]


def patch_main() -> None:
    text = MAIN.read_text()
    if "RegisterResponsiveLayoutPolicy.keypadMetrics" in text and "buttonHeightDp: Int? = null" in text:
        return

    text = replace_once(
        text,
        "import androidx.compose.foundation.layout.Box\n",
        "import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.BoxWithConstraints\n",
        "BoxWithConstraints import",
    )
    text = replace_once(
        text,
        "import androidx.compose.foundation.layout.height\n",
        "import androidx.compose.foundation.layout.height\nimport androidx.compose.foundation.layout.heightIn\n",
        "heightIn import",
    )

    old_header_start = "@Composable\nprivate fun Header(screenId: String, title: String) {"
    old_header_end = "\n\n@Composable\nprivate fun DiagnosticScreen"
    new_header = '''@Composable
private fun Header(screenId: String, title: String) {
    val responsive = rememberRegisterResponsiveMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(responsive.headerHeightDp.dp)
            .background(Navy)
            .padding(horizontal = responsive.screenPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "つぐレジ",
            color = Color.White,
            fontSize = if (responsive.isCompact) 20.sp else 23.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.width(if (responsive.isCompact) 12.dp else 24.dp))
        Text(
            "$screenId  $title",
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = if (responsive.isCompact) 17.sp else 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (!responsive.isCompact) {
            Text(
                "営業日 ${LocalDate.now()}  ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
    }
}'''
    text = replace_between(text, old_header_start, old_header_end, new_header, "responsive header")

    text = replace_once(
        text,
        '    var numericInput by remember { mutableStateOf("") }\n    Column(Modifier.fillMaxSize()) {',
        '    var numericInput by remember { mutableStateOf("") }\n    val responsive = rememberRegisterResponsiveMetrics()\n    Column(Modifier.fillMaxSize()) {',
        "sales responsive metrics",
    )
    text = replace_once(
        text,
        '            Text("店舗：サンプル居酒屋  |  担当：$operatorName", color = Navy, fontWeight = FontWeight.Medium)',
        '            Text(\n                if (responsive.isCompact) "担当：$operatorName" else "店舗：サンプル居酒屋  |  担当：$operatorName",\n                color = Navy,\n                fontWeight = FontWeight.Medium,\n                maxLines = 1,\n            )',
        "sales compact operator label",
    )
    text = replace_once(
        text,
        '''            } else {
                Text("SQLite保存・オフライン販売", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
            }''',
        '''            } else if (!responsive.isCompact) {
                Text("SQLite保存・オフライン販売", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
            }''',
        "sales compact status",
    )
    text = replace_once(
        text,
        "        Row(Modifier.weight(1f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {",
        "        Row(\n            Modifier.weight(1f).padding(responsive.screenPaddingDp.dp),\n            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),\n        ) {",
        "sales main row",
    )
    text = replace_once(
        text,
        "            CardPanel(Modifier.weight(0.36f).fillMaxHeight()) {",
        "            CardPanel(Modifier.weight(responsive.salesListWeight).fillMaxHeight()) {",
        "sales list weight",
    )

    sales_keypad_start = "            CardPanel(Modifier.weight(0.24f).fillMaxHeight()) {"
    sales_products_start = "            CardPanel(Modifier.weight(0.40f).fillMaxHeight()) {"
    sales_keypad = '''            CardPanel(Modifier.weight(responsive.salesKeypadWeight).fillMaxHeight()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val keypad = RegisterResponsiveLayoutPolicy.keypadMetrics(
                        availableHeightDp = maxHeight.value.toInt(),
                        functionRows = 2,
                    )
                    val keypadScroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (keypad.scrollRequired) Modifier.verticalScroll(keypadScroll) else Modifier,
                            ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(keypad.valueHeightDp.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "置数・機能",
                                fontSize = if (responsive.isCompact) 16.sp else 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Navy,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(keypad.gapDp.dp))
                            ValueBox(
                                if (numericInput.isBlank()) "0" else numericInput,
                                compact = true,
                                modifier = Modifier.weight(1f),
                                heightDp = keypad.valueHeightDp,
                            )
                        }
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        NumberPad(
                            onDigit = { if (numericInput.length < 5) numericInput += it },
                            onClear = { numericInput = "" },
                            bottomActionLabel = "数量",
                            onBottomAction = {
                                numericInput.toIntOrNull()?.let(onChangeQuantity)
                                numericInput = ""
                            },
                            compact = true,
                            buttonHeightDp = keypad.keyHeightDp,
                            rowGapDp = keypad.gapDp,
                        )
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(keypad.gapDp.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onRemove,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("訂正", fontSize = 13.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { selectedIndex?.let(onEdit) },
                                    enabled = selectedIndex != null,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("行編集", fontSize = 13.sp, maxLines = 1) }
                            }
                            Spacer(Modifier.height(keypad.gapDp.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(keypad.gapDp.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onDiscount,
                                    enabled = cart.isNotEmpty(),
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("値引・割引", fontSize = 13.sp, maxLines = 1) }
                                Button(
                                    onClick = onCancelTransaction,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFBE9E7),
                                        contentColor = Danger,
                                    ),
                                ) { Text("取引中止", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            }
                        }
                    }
                }
            }

'''
    text = replace_between(
        text,
        sales_keypad_start,
        sales_products_start,
        sales_keypad,
        "sales adaptive keypad panel",
    )
    text = replace_once(
        text,
        sales_products_start,
        "            CardPanel(Modifier.weight(responsive.salesProductsWeight).fillMaxHeight()) {",
        "sales products weight",
    )

    sales_bottom_start = '''        Row(
            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {'''
    sales_bottom_end = "\n    }\n}\n\n@Composable\nprivate fun LineEditScreen"
    sales_bottom = '''        Row(
            Modifier
                .fillMaxWidth()
                .height(responsive.bottomBarHeightDp.dp)
                .padding(horizontal = responsive.screenPaddingDp.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
        ) {
            OutlinedButton(onClick = onTickets, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("伝票一覧", maxLines = 1) }
            OutlinedButton(onClick = onHold, enabled = cart.isNotEmpty(), modifier = Modifier.weight(0.8f).fillMaxHeight()) { Text("保留", maxLines = 1) }
            OutlinedButton(onClick = onSalesHistory, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("売上一覧", maxLines = 1) }
            OutlinedButton(onClick = onPrinterStatus, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("プリンター", maxLines = 1) }
            OutlinedButton(onClick = onPrintQueue, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("印刷管理", maxLines = 1) }
            BlueButton(
                "小計／会計  ${yen(summary.grossAmount)}",
                onPayment,
                Modifier.weight(if (responsive.isCompact) 2.2f else 2.8f).fillMaxHeight(),
                cart.isNotEmpty(),
            )
        }'''
    text = replace_between(text, sales_bottom_start, sales_bottom_end, sales_bottom, "sales responsive bottom bar")

    payment_anchor = '''    val mixedBlocked = mixed.hasMixedTax && mixedPolicy == MixedTaxPolicy.BLOCK
    val mixedNeedsAcknowledgement = mixed.hasMixedTax && mixedPolicy == MixedTaxPolicy.WARN
'''
    text = replace_once(
        text,
        payment_anchor,
        payment_anchor + "    val responsive = rememberRegisterResponsiveMetrics()\n",
        "payment responsive metrics",
    )
    text = replace_once(
        text,
        "        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {",
        "        Row(\n            Modifier.weight(1f).padding(responsive.screenPaddingDp.dp),\n            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),\n        ) {",
        "payment main row",
    )

    payment_function_start = text.find("private fun PaymentScreen(")
    payment_right_start = text.find("            CardPanel(Modifier.width(430.dp).fillMaxHeight()) {", payment_function_start)
    if payment_right_start < 0:
        raise RuntimeError("payment keypad start not found")
    payment_left = "            CardPanel(Modifier.weight(1f).fillMaxHeight()) {"
    payment_left_index = text.find(payment_left, payment_function_start, payment_right_start)
    if payment_left_index < 0:
        raise RuntimeError("payment detail panel not found")
    text = text[:payment_left_index] + text[payment_left_index:].replace(
        payment_left,
        "            CardPanel(Modifier.weight(responsive.paymentDetailWeight).fillMaxHeight()) {",
        1,
    )
    payment_right_start = text.find("            CardPanel(Modifier.width(430.dp).fillMaxHeight()) {", payment_function_start)
    payment_right_end_marker = "\n        }\n        BottomActions("
    payment_right_end = text.find(payment_right_end_marker, payment_right_start)
    if payment_right_end < 0:
        raise RuntimeError("payment keypad end not found")
    payment_right = '''            CardPanel(Modifier.weight(responsive.paymentKeypadWeight).fillMaxHeight()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val keypad = RegisterResponsiveLayoutPolicy.keypadMetrics(
                        availableHeightDp = maxHeight.value.toInt(),
                        functionRows = 1,
                        reservedTopDp = if (responsive.isCompact) 104 else 126,
                    )
                    val keypadScroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (keypad.scrollRequired) Modifier.verticalScroll(keypadScroll) else Modifier,
                            ),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp)) {
                            Column(Modifier.weight(1f)) {
                                PaymentAmountRow("合計", yen(summary.grossAmount), emphasized = true)
                                PaymentAmountRow("支払済", yen(state.paidAmount))
                            }
                            Column(Modifier.weight(1f)) {
                                PaymentAmountRow("残額", yen(remaining), emphasized = true)
                                PaymentAmountRow("お釣り", yen(state.changeAmount))
                            }
                        }
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = keypad.allocationListMaxHeightDp.dp),
                        ) {
                            itemsIndexed(state.allocations) { index, payment ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${payment.method.displayName} ${yen(payment.appliedAmount)}",
                                        Modifier.weight(1f),
                                        maxLines = 1,
                                    )
                                    OutlinedButton(onClick = { onStateChange(PaymentEngine.removeAt(state, index)) }) {
                                        Text("取消", maxLines = 1)
                                    }
                                }
                            }
                        }
                        val visibleMessage = externalMessage ?: operationMessage
                        if (!visibleMessage.isNullOrBlank()) {
                            Text(
                                visibleMessage,
                                color = if (completing) Color(0xFF2E7D32) else Danger,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                            )
                        }
                        ValueBox(
                            if (input.isBlank()) "残額全額" else input,
                            compact = true,
                            heightDp = keypad.valueHeightDp,
                        )
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        NumberPad(
                            onDigit = { if (input.length < 10) input += it },
                            onClear = { input = "" },
                            bottomActionLabel = "現金",
                            onBottomAction = { add(PaymentMethod.CASH) },
                            compact = true,
                            buttonHeightDp = keypad.keyHeightDp,
                            rowGapDp = keypad.gapDp,
                        )
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(keypad.gapDp.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { add(PaymentMethod.CARD) },
                                    enabled = remaining > 0 && !completing,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("カード", fontSize = 13.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { add(PaymentMethod.GIFT_CERTIFICATE) },
                                    enabled = remaining > 0 && !completing,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("商品券", fontSize = 13.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { add(PaymentMethod.ACCOUNT_RECEIVABLE) },
                                    enabled = remaining > 0 && !completing,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("掛売", fontSize = 13.sp, maxLines = 1) }
                            }
                        }
                    }
                }
            }'''
    text = text[:payment_right_start] + payment_right + text[payment_right_end:]

    number_pad_start = "@Composable\nprivate fun NumberPad("
    value_box_start = "\n\n@Composable\nprivate fun ValueBox("
    new_number_pad = '''@Composable
private fun NumberPad(
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    bottomActionLabel: String,
    onBottomAction: () -> Unit,
    compact: Boolean = false,
    buttonHeightDp: Int? = null,
    rowGapDp: Int? = null,
) {
    val resolvedButtonHeightDp = buttonHeightDp
        ?: if (compact) RegisterLayoutPolicy.COMPACT_KEY_HEIGHT_DP else 48
    val resolvedRowGapDp = rowGapDp
        ?: if (compact) RegisterLayoutPolicy.COMPACT_KEY_GAP_DP else 6
    val buttonHeight = resolvedButtonHeightDp.dp
    val rowGap = resolvedRowGapDp.dp
    val columnGap = if (compact) resolvedRowGapDp.dp else 8.dp
    val digitFontSize = when {
        resolvedButtonHeightDp >= 72 -> 24.sp
        resolvedButtonHeightDp >= 60 -> 21.sp
        resolvedButtonHeightDp >= 48 -> 18.sp
        else -> 16.sp
    }
    val content: @Composable () -> Unit = {
        for (rowStart in 1..9 step 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(columnGap)) {
                for (digit in rowStart until rowStart + 3) {
                    OutlinedButton(
                        onClick = { onDigit(digit.toString()) },
                        modifier = Modifier.weight(1f).height(buttonHeight),
                    ) {
                        Text(digit.toString(), fontSize = digitFontSize, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(rowGap))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(columnGap)) {
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).height(buttonHeight)) {
                Text("C", color = Danger, fontSize = digitFontSize)
            }
            OutlinedButton(onClick = { onDigit("0") }, modifier = Modifier.weight(1f).height(buttonHeight)) {
                Text("0", fontSize = digitFontSize)
            }
            BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1f).height(buttonHeight))
        }
    }
    if (compact || buttonHeightDp != null) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) { content() }
    } else {
        content()
    }
}'''
    text = replace_between(text, number_pad_start, value_box_start, new_number_pad, "adaptive number pad")

    old_value_box = '''@Composable
private fun ValueBox(value: String, compact: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(if (compact) RegisterLayoutPolicy.COMPACT_VALUE_HEIGHT_DP.dp else 54.dp)
            .background(PaleBlue, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
    }
}'''
    new_value_box = '''@Composable
private fun ValueBox(
    value: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    heightDp: Int? = null,
) {
    val resolvedHeightDp = heightDp
        ?: if (compact) RegisterLayoutPolicy.COMPACT_VALUE_HEIGHT_DP else 54
    Box(
        modifier
            .fillMaxWidth()
            .height(resolvedHeightDp.dp)
            .background(PaleBlue, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            value,
            fontSize = if (resolvedHeightDp >= 60) 29.sp else 25.sp,
            fontWeight = FontWeight.Bold,
            color = Navy,
            maxLines = 1,
        )
    }
}'''
    text = replace_once(text, old_value_box, new_value_box, "adaptive value box")

    old_card_panel = '''@Composable
private fun CardPanel(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), content = content)
    }
}'''
    new_card_panel = '''@Composable
private fun CardPanel(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    val responsive = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(
            Modifier.fillMaxSize().padding(responsive.cardPaddingDp.dp),
            content = content,
        )
    }
}'''
    text = replace_once(text, old_card_panel, new_card_panel, "responsive card padding")

    old_bottom_actions = '''@Composable
private fun BottomActions(
    onBack: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 18.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.width(190.dp).fillMaxHeight()) { Text("戻る") }
        BlueButton(confirmLabel, onConfirm, Modifier.weight(1f).fillMaxHeight(), confirmEnabled)
    }
}'''
    new_bottom_actions = '''@Composable
private fun BottomActions(
    onBack: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    val responsive = rememberRegisterResponsiveMetrics()
    Row(
        Modifier
            .fillMaxWidth()
            .height(responsive.bottomBarHeightDp.dp)
            .padding(horizontal = responsive.screenPaddingDp.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.weight(if (responsive.isCompact) 0.28f else 0.20f).fillMaxHeight(),
        ) { Text("戻る", maxLines = 1) }
        BlueButton(
            confirmLabel,
            onConfirm,
            Modifier.weight(1f).fillMaxHeight(),
            confirmEnabled,
        )
    }
}'''
    text = replace_once(text, old_bottom_actions, new_bottom_actions, "responsive bottom actions")

    MAIN.write_text(text)


def patch_version() -> None:
    text = BUILD.read_text()
    text = replace_once(text, "versionCode = 58", "versionCode = 59", "version code")
    text = replace_once(text, 'versionName = "0.28.0-dev.1"', 'versionName = "0.29.0-dev.1"', "version name")
    BUILD.write_text(text)


def write_release_notes() -> None:
    RELEASE_NOTES.write_text(
        """# つぐレジ v0.29.0-dev.1 リリースノート\n\n"
        "## 全画面レスポンシブ基盤\n\n"
        "- 800×480dpから1920×1080dpまでを共通の画面クラスで判定\n"
        "- Androidの表示サイズ・フォント倍率をレイアウト判定へ反映\n"
        "- ヘッダー、カード余白、画面余白、下部操作の寸法を共通化\n"
        "- 固定幅の下部操作を画面幅に応じた比率配分へ変更\n"
        "- 小さい画面では補助情報を省略し、主要操作領域を優先\n\n"
        "## 販売・会計画面\n\n"
        "- テンキーを固定42dpから、残り高さに応じた48～80dpへ変更\n"
        "- 余裕のある画面ではテンキー、金額表示、機能キーを拡大\n"
        "- 高さ不足時は重ねず、テンキー領域だけを安全にスクロール\n"
        "- 販売画面の注文一覧・テンキー・商品領域の列比率を画面幅別に変更\n"
        "- 会計画面の内訳・支払領域を固定430dpから比率配分へ変更\n"
        "- 下部の伝票・保留・売上・印刷・会計操作を固定幅から比率配分へ変更\n\n"
        "## 継続確認\n\n"
        "- 管理・設定系画面は共通基盤を利用しながら順次個別最適化する\n"
        "- 実機で表示サイズとフォントサイズを切り替え、全画面の重なりを確認する\n"
        """
    )


def restore_formal_workflow() -> None:
    formal = subprocess.check_output(
        ["git", "show", "origin/develop/v0.28:.github/workflows/build-apk.yml"],
        cwd=ROOT,
        text=True,
    )
    formal = formal.replace("v0.14-v0.28", "v0.14-v0.29")
    formal = formal.replace("versionCode = 58", "versionCode = 59")
    formal = formal.replace('versionName = \"0.28.0-dev.1\"', 'versionName = \"0.29.0-dev.1\"')
    formal = formal.replace(
        "          test -s docs/V0.28_RELEASE_NOTES.md\n",
        "          test -s docs/V0.28_RELEASE_NOTES.md\n"
        "          test -s app/src/main/java/jp/co/tenposinfo/register/ResponsiveLayoutV029.kt\n"
        "          test -s app/src/test/java/jp/co/tenposinfo/register/V029ResponsiveLayoutTest.kt\n"
        "          test -s docs/V0.29_RESPONSIVE_LAYOUT_REQUIREMENTS.md\n"
        "          test -s docs/V0.29_RELEASE_NOTES.md\n",
    )
    formal = formal.replace(
        "          assert 'UI_ADMIN_NAVIGATION=compose-flow' not in main\n"
        if "          assert 'UI_ADMIN_NAVIGATION=compose-flow' not in main\n" in formal else
        "          assert 'BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1f).height(buttonHeight))' in main\n",
        "          assert 'BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1f).height(buttonHeight))' in main\n"
        "          responsive = (root / 'ResponsiveLayoutV029.kt').read_text()\n"
        "          assert 'MIN_TOUCH_DP = 48' in responsive\n"
        "          assert 'MAX_KEY_HEIGHT_DP = 80' in responsive\n"
        "          assert 'RegisterResponsiveLayoutPolicy.keypadMetrics' in main\n"
        "          assert 'BoxWithConstraints(Modifier.fillMaxSize())' in main\n"
        "          assert 'buttonHeightDp: Int? = null' in main\n"
        "          assert 'responsive.salesKeypadWeight' in main\n"
        "          assert 'responsive.paymentKeypadWeight' in main\n",
    )
    formal = formal.replace(
        "            V027SettlementHistoryReprintTest.kt \\\n",
        "            V029ResponsiveLayoutTest.kt \\\n"
        "            V027SettlementHistoryReprintTest.kt \\\n",
    )
    formal = formal.replace(
        "          test ! -e tools/apply_v028.py\n",
        "          test ! -e tools/apply_v028.py\n          test ! -e tools/apply_v029.py\n",
    )
    formal = formal.replace(
        "TSUGUREGI_v0.28.0_dev1_ui_stability_debug.apk",
        "TSUGUREGI_v0.29.0_dev1_responsive_layout_debug.apk",
    )
    formal = formal.replace("REGISTER_VERSION_NAME=0.28.0-dev.1", "REGISTER_VERSION_NAME=0.29.0-dev.1")
    formal = formal.replace("REGISTER_VERSION_CODE=58", "REGISTER_VERSION_CODE=59")
    formal = formal.replace(
        "TSUGUREGI-v0.28.0-dev1-ui-stability-apks",
        "TSUGUREGI-v0.29.0-dev1-responsive-layout-apks",
    )
    formal = formal.replace(
        "          echo 'UI_COMPACT_KEY_GAP_DP=5' >> artifacts/build-summary.txt\n",
        "          echo 'UI_COMPACT_KEY_GAP_DP=5' >> artifacts/build-summary.txt\n"
        "          echo 'UI_RESPONSIVE_POLICY=all-screens' >> artifacts/build-summary.txt\n"
        "          echo 'UI_KEYPAD_HEIGHT_DP=48-80-adaptive' >> artifacts/build-summary.txt\n"
        "          echo 'UI_SMALL_HEIGHT_FALLBACK=panel-scroll' >> artifacts/build-summary.txt\n"
        "          echo 'UI_SUPPORTED_PROFILES=800x480-to-1920x1080' >> artifacts/build-summary.txt\n",
    )
    formal = formal.replace(
        "          echo 'REAL_DEVICE_KEYPAD_SIZE_VERIFICATION=required' >> artifacts/build-summary.txt\n",
        "          echo 'REAL_DEVICE_KEYPAD_SIZE_VERIFICATION=required' >> artifacts/build-summary.txt\n"
        "          echo 'REAL_DEVICE_ALL_SCREEN_DISPLAY_SIZE_VERIFICATION=required' >> artifacts/build-summary.txt\n",
    )
    WORKFLOW.write_text(formal)


def main() -> None:
    patch_main()
    patch_version()
    write_release_notes()
    restore_formal_workflow()
    Path(__file__).unlink()


if __name__ == "__main__":
    main()
