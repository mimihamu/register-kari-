from pathlib import Path
import subprocess

workflow_path = Path('.github/workflows/build-apk.yml')
script_path = Path('scripts/fix_v073_period_label_guard.py')

original = subprocess.check_output(
    ['git', 'show', 'HEAD^:.github/workflows/build-apk.yml'],
    text=True,
)
obsolete = "          grep -q '期間DB絞込' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
if original.count(obsolete) != 1:
    raise SystemExit(f'obsolete period-label guard count must be 1, got {original.count(obsolete)}')

fixed = original.replace(obsolete, '')
required = [
    "grep -q 'SaleReceiptReprintLedgerPeriod.entries' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt",
    "grep -Fq 'r.requested_at >= ?' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt",
    "grep -Fq 'r.requested_at < ?' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt",
    "grep -q '検索時点固定' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt",
]
for marker in required:
    if marker not in fixed:
        raise SystemExit(f'required semantic guard missing: {marker}')

workflow_path.write_text(fixed)
script_path.unlink()
subprocess.run(['git', 'config', 'user.name', 'github-actions[bot]'], check=True)
subprocess.run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '.github/workflows/build-apk.yml', 'scripts/fix_v073_period_label_guard.py'], check=True)
subprocess.run(['git', 'commit', '-m', 'fix(v0.73): remove obsolete period label CI guard'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FIX_COMMIT={sha}')
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.73'], check=True)
