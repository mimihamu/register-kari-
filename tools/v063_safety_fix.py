from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt"
text = path.read_text(encoding="utf-8")
old = '''                                sale == null -> {
                                    localMessage = "売上No.$saleId は見つかりません"
                                }
                                sale.id in reversedSaleIds -> {
                                    localMessage = "売上No.${sale.id} は全量返品・取消済みです"
                                }
'''
new = '''                                sale == null -> {
                                    selectedSaleId = null
                                    directSaleOverride = null
                                    lines = emptyList()
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = "売上No.$saleId は見つかりません。元売上の選択を解除しました"
                                }
                                sale.id in reversedSaleIds -> {
                                    selectedSaleId = null
                                    directSaleOverride = null
                                    lines = emptyList()
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = "売上No.${sale.id} は全量返品・取消済みです。元売上の選択を解除しました"
                                }
'''
if old not in text:
    raise RuntimeError("direct lookup branches not found")
path.write_text(text.replace(old, new, 1), encoding="utf-8")

test = root / "app/src/test/java/jp/co/tenposinfo/register/V063ReversalSaleLookupTest.kt"
t = test.read_text(encoding="utf-8")
needle = '        assertTrue(activity.contains("全量返品・取消済みです"))\n'
replacement = needle + '        assertTrue(activity.contains("元売上の選択を解除しました"))\n'
if needle not in t:
    raise RuntimeError("v0.63 test anchor not found")
test.write_text(t.replace(needle, replacement, 1), encoding="utf-8")

workflow = root / ".github/workflows/build-apk.yml"
w = workflow.read_text(encoding="utf-8")
anchor = "          grep -q '全量返品・取消済みです' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n"
if anchor not in w:
    raise RuntimeError("workflow reversal anchor not found")
w = w.replace(anchor, anchor + "          grep -q '元売上の選択を解除しました' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n", 1)
workflow.write_text(w, encoding="utf-8")

for relative in ["tools/v063_safety_fix.py", ".github/workflows/v063-safety-fix.yml"]:
    p = root / relative
    if p.exists():
        p.unlink()
print("v0.63 fail-closed lookup safety patch applied")
