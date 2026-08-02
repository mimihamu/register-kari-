from pathlib import Path

path = Path("tools/apply_v029.py")
text = path.read_text()
old = '''    text = replace_once(
        text,
        "        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {",
        "        Row(\\n            Modifier.weight(1f).padding(responsive.screenPaddingDp.dp),\\n            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),\\n        ) {",
        "payment main row",
    )

    payment_function_start = text.find("private fun PaymentScreen(")
'''
new = '''    payment_function_start = text.find("private fun PaymentScreen(")
    payment_row_old = "        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {"
    payment_row_new = "        Row(\\n            Modifier.weight(1f).padding(responsive.screenPaddingDp.dp),\\n            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),\\n        ) {"
    payment_row_index = text.find(payment_row_old, payment_function_start)
    if payment_row_index < 0:
        raise RuntimeError("payment main row not found")
    text = text[:payment_row_index] + payment_row_new + text[payment_row_index + len(payment_row_old):]

'''
count = text.count(old)
if count != 1:
    raise RuntimeError(f"payment row applicator block: expected one match, found {count}")
path.write_text(text.replace(old, new, 1))
