from pathlib import Path
import subprocess

workflow_path = Path('.github/workflows/build-apk.yml')
script_path = Path('scripts/fix_v073_ci_guard.py')

original = subprocess.check_output(
    ['git', 'show', 'HEAD^:.github/workflows/build-apk.yml'],
    text=True,
)
obsolete = "          grep -q 'store.search(appliedCriteria, pageOffset)' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
if original.count(obsolete) != 1:
    raise SystemExit(f'obsolete guard count must be 1, got {original.count(obsolete)}')

fixed = original.replace(obsolete, '')
if "! grep -q 'pageOffset' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt" not in fixed:
    raise SystemExit('v0.73 no-pageOffset guard missing')
if "grep -Fq 'LIMIT ? OFFSET ?' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt" not in fixed:
    raise SystemExit('v0.70 legacy API guard missing')

workflow_path.write_text(fixed)
script_path.unlink()
subprocess.run(['git', 'config', 'user.name', 'github-actions[bot]'], check=True)
subprocess.run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '.github/workflows/build-apk.yml', 'scripts/fix_v073_ci_guard.py'], check=True)
subprocess.run(['git', 'commit', '-m', 'fix(v0.73): remove obsolete offset UI CI guard'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FIX_COMMIT={sha}')
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.73'], check=True)
