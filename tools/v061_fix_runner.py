from pathlib import Path
import runpy

root = Path(__file__).resolve().parents[1]
apply_script = root / "tools/v061_apply.py"
text = apply_script.read_text(encoding="utf-8")
offending = '''    ("grep -q 'ReceiptVoucherLedgerActivity::class.java' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt", "grep -q 'ReceiptVoucherNavigation.ledgerIntent' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt"),\n'''
if offending not in text:
    raise RuntimeError("expected obsolete workflow replacement guard was not found")
apply_script.write_text(text.replace(offending, ""), encoding="utf-8")
runpy.run_path(str(apply_script), run_name="__main__")
Path(__file__).unlink(missing_ok=True)
