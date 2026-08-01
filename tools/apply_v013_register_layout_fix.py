from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt")
text = main_path.read_text(encoding="utf-8")

text = replace_once(
    text,
    "import androidx.compose.material3.CardDefaults\n",
    "import androidx.compose.material3.CardDefaults\nimport androidx.compose.material3.LocalMinimumInteractiveComponentSize\n",
    "material minimum-size import",
)
text = replace_once(
    text,
    "import androidx.compose.runtime.Composable\n",
    "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.CompositionLocalProvider\n",
    "composition local import",
)
text = replace_once(
    text,
    "private val Border = Color(0xFFD5DEE7)\n",
    """private val Border = Color(0xFFD5DEE7)\n\ninternal object RegisterLayoutPolicy {\n    const val DIAGNOSTIC_CARD_HEIGHT_DP = 280\n    const val COMPACT_VALUE_HEIGHT_DP = 40\n    const val COMPACT_KEY_HEIGHT_DP = 36\n    const val COMPACT_KEY_GAP_DP = 2\n    const val COMPACT_FUNCTION_HEIGHT_DP = 34\n\n    fun salesUtilityRequiredHeightDp(panelPaddingDp: Int = 32): Int =\n        40 + 4 +\n            (COMPACT_KEY_HEIGHT_DP * 4 + COMPACT_KEY_GAP_DP * 3) + 4 +\n            COMPACT_FUNCTION_HEIGHT_DP + 4 +\n            COMPACT_FUNCTION_HEIGHT_DP +\n            panelPaddingDp\n\n    fun paymentControlsRequiredHeightDp(panelPaddingDp: Int = 32): Int =\n        48 + 4 + 36 + COMPACT_VALUE_HEIGHT_DP + 4 +\n            (COMPACT_KEY_HEIGHT_DP * 4 + COMPACT_KEY_GAP_DP * 3) + 4 +\n            COMPACT_FUNCTION_HEIGHT_DP +\n            panelPaddingDp\n}\n""",
    "layout policy",
)

text = replace_once(
    text,
    """        Column(\n            modifier = Modifier.fillMaxSize().padding(32.dp),\n            horizontalAlignment = Alignment.CenterHorizontally,\n            verticalArrangement = Arrangement.Center,\n        ) {\n            Text(\"起動チェックを実行しました\", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Navy)\n            Spacer(Modifier.height(24.dp))\n            CardPanel(Modifier.width(700.dp)) {\n""",
    """        Column(\n            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),\n            horizontalAlignment = Alignment.CenterHorizontally,\n            verticalArrangement = Arrangement.Center,\n        ) {\n            Text(\"起動チェックを実行しました\", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Navy)\n            Spacer(Modifier.height(14.dp))\n            CardPanel(Modifier.width(700.dp).height(RegisterLayoutPolicy.DIAGNOSTIC_CARD_HEIGHT_DP.dp)) {\n""",
    "diagnostic constrained content",
)
text = replace_once(
    text,
    """            Spacer(Modifier.height(26.dp))\n            BlueButton(\"診断完了・担当者選択へ\", onComplete, Modifier.width(340.dp).height(58.dp))\n""",
    """            Spacer(Modifier.height(14.dp))\n            BlueButton(\"診断完了・担当者選択へ\", onComplete, Modifier.width(340.dp).height(54.dp))\n""",
    "diagnostic button",
)

old_sales = """                Text(\"置数・機能\", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)\n                Spacer(Modifier.height(8.dp))\n                ValueBox(if (numericInput.isBlank()) \"0\" else numericInput)\n                Spacer(Modifier.height(8.dp))\n                NumberPad(\n                    onDigit = { if (numericInput.length < 5) numericInput += it },\n                    onClear = { numericInput = \"\" },\n                    bottomActionLabel = \"数量\",\n                    onBottomAction = {\n                        numericInput.toIntOrNull()?.let(onChangeQuantity)\n                        numericInput = \"\"\n                    },\n                )\n                Spacer(Modifier.height(8.dp))\n                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                    OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text(\"訂正\") }\n                    OutlinedButton(\n                        onClick = { selectedIndex?.let(onEdit) },\n                        enabled = selectedIndex != null,\n                        modifier = Modifier.weight(1f),\n                    ) { Text(\"行編集\") }\n                }\n                Spacer(Modifier.height(8.dp))\n                OutlinedButton(onClick = onDiscount, enabled = cart.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {\n                    Text(\"値引・割引\")\n                }\n                Spacer(Modifier.weight(1f))\n                Button(\n                    onClick = onCancelTransaction,\n                    modifier = Modifier.fillMaxWidth(),\n                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBE9E7), contentColor = Danger),\n                ) { Text(\"取引中止\", fontWeight = FontWeight.Bold) }\n"""
new_sales = """                Row(\n                    Modifier.fillMaxWidth().height(40.dp),\n                    verticalAlignment = Alignment.CenterVertically,\n                ) {\n                    Text(\"置数・機能\", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Navy)\n                    Spacer(Modifier.width(8.dp))\n                    ValueBox(\n                        if (numericInput.isBlank()) \"0\" else numericInput,\n                        compact = true,\n                        modifier = Modifier.weight(1f),\n                    )\n                }\n                Spacer(Modifier.height(4.dp))\n                NumberPad(\n                    onDigit = { if (numericInput.length < 5) numericInput += it },\n                    onClear = { numericInput = \"\" },\n                    bottomActionLabel = \"数量\",\n                    onBottomAction = {\n                        numericInput.toIntOrNull()?.let(onChangeQuantity)\n                        numericInput = \"\"\n                    },\n                    compact = true,\n                )\n                Spacer(Modifier.height(4.dp))\n                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {\n                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {\n                        OutlinedButton(\n                            onClick = onRemove,\n                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),\n                        ) { Text(\"訂正\", fontSize = 12.sp) }\n                        OutlinedButton(\n                            onClick = { selectedIndex?.let(onEdit) },\n                            enabled = selectedIndex != null,\n                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),\n                        ) { Text(\"行編集\", fontSize = 12.sp) }\n                    }\n                    Spacer(Modifier.height(4.dp))\n                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {\n                        OutlinedButton(\n                            onClick = onDiscount,\n                            enabled = cart.isNotEmpty(),\n                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),\n                        ) { Text(\"値引・割引\", fontSize = 12.sp) }\n                        Button(\n                            onClick = onCancelTransaction,\n                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),\n                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBE9E7), contentColor = Danger),\n                        ) { Text(\"取引中止\", fontSize = 12.sp, fontWeight = FontWeight.Bold) }\n                    }\n                }\n"""
text = replace_once(text, old_sales, new_sales, "sales utility controls")

old_payment_summary = """                PaymentAmountRow(\"合計\", yen(summary.grossAmount), emphasized = true)\n                PaymentAmountRow(\"支払済\", yen(state.paidAmount))\n                PaymentAmountRow(\"残額\", yen(remaining), emphasized = true)\n                PaymentAmountRow(\"お釣り\", yen(state.changeAmount))\n                Spacer(Modifier.height(4.dp))\n                LazyColumn(Modifier.height(40.dp)) {\n"""
new_payment_summary = """                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {\n                    Column(Modifier.weight(1f)) {\n                        PaymentAmountRow(\"合計\", yen(summary.grossAmount), emphasized = true)\n                        PaymentAmountRow(\"支払済\", yen(state.paidAmount))\n                    }\n                    Column(Modifier.weight(1f)) {\n                        PaymentAmountRow(\"残額\", yen(remaining), emphasized = true)\n                        PaymentAmountRow(\"お釣り\", yen(state.changeAmount))\n                    }\n                }\n                Spacer(Modifier.height(4.dp))\n                LazyColumn(Modifier.height(36.dp)) {\n"""
text = replace_once(text, old_payment_summary, new_payment_summary, "payment summary grid")

old_payment_methods = """                Spacer(Modifier.height(4.dp))\n                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                    OutlinedButton(onClick = { add(PaymentMethod.CARD) }, enabled = remaining > 0, modifier = Modifier.weight(1f).height(40.dp)) { Text(\"カード\") }\n                    OutlinedButton(onClick = { add(PaymentMethod.GIFT_CERTIFICATE) }, enabled = remaining > 0, modifier = Modifier.weight(1f).height(40.dp)) { Text(\"商品券\") }\n                    OutlinedButton(onClick = { add(PaymentMethod.ACCOUNT_RECEIVABLE) }, enabled = remaining > 0, modifier = Modifier.weight(1f).height(40.dp)) { Text(\"掛売\") }\n                }\n"""
new_payment_methods = """                Spacer(Modifier.height(4.dp))\n                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {\n                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        OutlinedButton(\n                            onClick = { add(PaymentMethod.CARD) },\n                            enabled = remaining > 0,\n                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),\n                        ) { Text(\"カード\", fontSize = 12.sp) }\n                        OutlinedButton(\n                            onClick = { add(PaymentMethod.GIFT_CERTIFICATE) },\n                            enabled = remaining > 0,\n                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),\n                        ) { Text(\"商品券\", fontSize = 12.sp) }\n                        OutlinedButton(\n                            onClick = { add(PaymentMethod.ACCOUNT_RECEIVABLE) },\n                            enabled = remaining > 0,\n                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),\n                        ) { Text(\"掛売\", fontSize = 12.sp) }\n                    }\n                }\n"""
text = replace_once(text, old_payment_methods, new_payment_methods, "payment method controls")

text = replace_once(
    text,
    """        Column(\n            Modifier.fillMaxSize().padding(30.dp),\n            horizontalAlignment = Alignment.CenterHorizontally,\n            verticalArrangement = Arrangement.Center,\n        ) {\n""",
    """        Column(\n            Modifier.weight(1f).fillMaxWidth().padding(24.dp),\n            horizontalAlignment = Alignment.CenterHorizontally,\n            verticalArrangement = Arrangement.Center,\n        ) {\n""",
    "complete constrained content",
)
text = replace_once(
    text,
    "CardPanel(Modifier.width(620.dp)) {",
    "CardPanel(Modifier.width(620.dp).height(200.dp)) {",
    "complete card height",
)

text = replace_once(
    text,
    """    val buttonHeight = if (compact) 40.dp else 44.dp\n    val rowGap = if (compact) 3.dp else 6.dp\n    for (rowStart in 1..9 step 3) {\n""",
    """    val buttonHeight = if (compact) RegisterLayoutPolicy.COMPACT_KEY_HEIGHT_DP.dp else 44.dp\n    val rowGap = if (compact) RegisterLayoutPolicy.COMPACT_KEY_GAP_DP.dp else 6.dp\n    val content: @Composable () -> Unit = {\n    for (rowStart in 1..9 step 3) {\n""",
    "number pad compact metrics start",
)
text = replace_once(
    text,
    """        BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1.4f).height(buttonHeight))\n    }\n}\n\n@Composable\nprivate fun ValueBox(value: String, compact: Boolean = false) {\n    Box(\n        Modifier.fillMaxWidth().height(if (compact) 46.dp else 54.dp).background(PaleBlue, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp),\n""",
    """        BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1.4f).height(buttonHeight))\n    }\n    }\n    if (compact) {\n        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) { content() }\n    } else {\n        content()\n    }\n}\n\n@Composable\nprivate fun ValueBox(value: String, compact: Boolean = false, modifier: Modifier = Modifier) {\n    Box(\n        modifier\n            .fillMaxWidth()\n            .height(if (compact) RegisterLayoutPolicy.COMPACT_VALUE_HEIGHT_DP.dp else 54.dp)\n            .background(PaleBlue, RoundedCornerShape(8.dp))\n            .padding(horizontal = 14.dp),\n""",
    "number pad end and value box modifier",
)

main_path.write_text(text, encoding="utf-8")

build_path = Path("app/build.gradle.kts")
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "versionCode = 39", "versionCode = 40", "register version code")
build = replace_once(build, 'versionName = "0.13.0-dev.2"', 'versionName = "0.13.0-dev.3"', "register version name")
build_path.write_text(build, encoding="utf-8")

test_path = Path("app/src/test/java/jp/co/tenposinfo/register/V013RegisterCompactLayoutTest.kt")
test_path.write_text(
    """package jp.co.tenposinfo.register\n\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass V013RegisterCompactLayoutTest {\n    @Test\n    fun salesUtilityControlsFitObservedTabletPanel() {\n        assertTrue(RegisterLayoutPolicy.salesUtilityRequiredHeightDp() <= 310)\n    }\n\n    @Test\n    fun paymentControlsFitObservedTabletPanel() {\n        assertTrue(RegisterLayoutPolicy.paymentControlsRequiredHeightDp() <= 360)\n    }\n\n    @Test\n    fun diagnosticCardLeavesRoomForContinueButton() {\n        assertTrue(RegisterLayoutPolicy.DIAGNOSTIC_CARD_HEIGHT_DP <= 280)\n    }\n}\n""",
    encoding="utf-8",
)
