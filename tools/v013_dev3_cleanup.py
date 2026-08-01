from pathlib import Path
from datetime import datetime, timezone

log: list[str] = []


def dedupe_import(path_name: str, import_line: str, insert_after: str) -> None:
    path = Path(path_name)
    text = path.read_text(encoding="utf-8")
    count = text.count(import_line + "\n")
    text = text.replace(import_line + "\n", "")
    if insert_after not in text:
        raise RuntimeError(f"insert anchor not found: {path_name}: {insert_after}")
    text = text.replace(insert_after, insert_after + import_line + "\n", 1)
    path.write_text(text, encoding="utf-8")
    log.append(f"{path_name}: {import_line}: before={count}, after=1")


dedupe_import(
    "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt",
    "import android.content.Intent",
    "package jp.co.tenposinfo.register\n\n",
)
dedupe_import(
    "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt",
    "import androidx.core.view.WindowCompat",
    "import android.os.Bundle\n",
)
dedupe_import(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    "import androidx.core.view.WindowCompat",
    "import androidx.activity.compose.setContent\n",
)
dedupe_import(
    "customer-display/src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt",
    "import androidx.compose.runtime.LaunchedEffect",
    "import androidx.compose.runtime.DisposableEffect\n",
)

# Keep exactly one payment amount helper.
main_path = Path("app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt")
main_text = main_path.read_text(encoding="utf-8")
payment_helper = '''@Composable
private fun PaymentAmountRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = if (emphasized) 18.sp else 14.sp, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontSize = if (emphasized) 20.sp else 15.sp, fontWeight = FontWeight.Bold, color = if (emphasized) Navy else Color.Unspecified)
    }
}

'''
helper_count = main_text.count(payment_helper)
main_text = main_text.replace(payment_helper, "")
amount_anchor = "@Composable\nprivate fun AmountRow(label: String, value: String, emphasized: Boolean = false) {"
if amount_anchor not in main_text:
    raise RuntimeError("AmountRow anchor not found")
main_text = main_text.replace(amount_anchor, payment_helper + amount_anchor, 1)
main_path.write_text(main_text, encoding="utf-8")
log.append(f"PaymentAmountRow helpers: before={helper_count}, after=1")

# Keep exactly one latest-item scroll state block in OrderItemsCard.
cd_path = Path("customer-display/src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt")
cd_text = cd_path.read_text(encoding="utf-8")
scroll_block = '''    val listState = rememberLazyListState()
    val targetIndex = CustomerDisplayScrollPolicy.targetIndex(snapshot.orderItems)
    LaunchedEffect(snapshot.sequence, targetIndex, snapshot.orderItems.size) {
        if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
    }
'''
scroll_count = cd_text.count(scroll_block)
cd_text = cd_text.replace(scroll_block, "")
function_anchor = "private fun OrderItemsCard("
function_index = cd_text.find(function_anchor)
if function_index < 0:
    raise RuntimeError("OrderItemsCard anchor not found")
card_anchor = "    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {"
card_index = cd_text.find(card_anchor, function_index)
if card_index < 0:
    raise RuntimeError("OrderItemsCard Card anchor not found")
cd_text = cd_text[:card_index] + scroll_block + cd_text[card_index:]
cd_path.write_text(cd_text, encoding="utf-8")
log.append(f"latest-item scroll blocks: before={scroll_count}, after=1")

Path("docs/V013_DEV3_CLEANUP_DIAGNOSTIC.txt").write_text(
    datetime.now(timezone.utc).isoformat() + "\n" + "\n".join(log) + "\n",
    encoding="utf-8",
)
