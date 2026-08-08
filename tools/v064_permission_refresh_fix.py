from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt"
text = path.read_text(encoding="utf-8")
old = '''    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            activeOperator = OperatorSessionRegistry.current(appContext)
        }
    }
'''
new = '''    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            val refreshed = OperatorSessionRegistry.current(appContext)
            activeOperator = refreshed
            if (screen == OperationsScreen.REVERSAL && refreshed?.allows(RegisterPermission.REVERSAL) != true) {
                reversalContextSaleId = null
                message = "返品・取消の権限が失効したため管理メニューへ戻りました"
                screen = OperationsScreen.MENU
            }
        }
    }
'''
if old not in text:
    raise RuntimeError("operator refresh loop not found")
path.write_text(text.replace(old, new, 1), encoding="utf-8")

test = root / "app/src/test/java/jp/co/tenposinfo/register/V064SaleDetailReversalNavigationTest.kt"
t = test.read_text(encoding="utf-8")
anchor = '        assertTrue(operations.contains("current?.allows(RegisterPermission.REVERSAL) == true"))\n'
insert = anchor + '        assertTrue(operations.contains("返品・取消の権限が失効したため管理メニューへ戻りました"))\n'
if anchor not in t:
    raise RuntimeError("v0.64 test permission anchor missing")
test.write_text(t.replace(anchor, insert, 1), encoding="utf-8")

workflow = root / ".github/workflows/build-apk.yml"
w = workflow.read_text(encoding="utf-8")
anchor = "          grep -q 'current?.allows(RegisterPermission.REVERSAL) == true' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n"
if anchor not in w:
    raise RuntimeError("workflow permission anchor missing")
w = w.replace(anchor, anchor + "          grep -q '返品・取消の権限が失効したため管理メニューへ戻りました' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n", 1)
summary = "          REVERSAL_CONTEXT_PERMISSION_RECHECK=true\n"
if summary not in w:
    raise RuntimeError("workflow permission summary missing")
w = w.replace(summary, summary + "          REVERSAL_CONTEXT_LIVE_PERMISSION_RECHECK=true\n", 1)
workflow.write_text(w, encoding="utf-8")

for relative in ["tools/v064_permission_refresh_fix.py", ".github/workflows/v064-permission-refresh-fix.yml"]:
    p = root / relative
    if p.exists():
        p.unlink()
print("v0.64 live permission recheck applied")
