from pathlib import Path
import subprocess

path = Path('app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt')
text = path.read_text(encoding='utf-8')
old = '''            val applyCustomRange = {
                runCatching {
                    SaleReceiptReprintLedgerPolicy.parseCustomRange(customStartDate, customEndDate)
                }.onSuccess { range ->
                    customRange = range
                    period = SaleReceiptReprintLedgerPeriod.CUSTOM
                    appliedCriteria = SaleReceiptReprintLedgerCriteria(
                        filter = filter,
                        period = SaleReceiptReprintLedgerPeriod.CUSTOM,
                        customStartInclusive = range.startInclusive,
                        customEndExclusive = range.endExclusive,
                        query = query,
                    )
                    dateError = null
                    pageOffset = 0
                    selectedId = null
                }.onFailure { error ->
                    dateError = error.message ?: "任意期間を確認してください"
                }
            }
'''
new = '''            val applyCustomRange: () -> Unit = {
                runCatching {
                    SaleReceiptReprintLedgerPolicy.parseCustomRange(customStartDate, customEndDate)
                }.onSuccess { range ->
                    customRange = range
                    period = SaleReceiptReprintLedgerPeriod.CUSTOM
                    appliedCriteria = SaleReceiptReprintLedgerCriteria(
                        filter = filter,
                        period = SaleReceiptReprintLedgerPeriod.CUSTOM,
                        customStartInclusive = range.startInclusive,
                        customEndExclusive = range.endExclusive,
                        query = query,
                    )
                    dateError = null
                    pageOffset = 0
                    selectedId = null
                }.onFailure { error ->
                    dateError = error.message ?: "任意期間を確認してください"
                }
                Unit
            }
'''
if text.count(old) != 1:
    raise RuntimeError(f'expected one custom lambda, found {text.count(old)}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')

# Restore the normal v0.72 CI workflow after the temporary patch workflow.
workflow = subprocess.check_output(
    ['git', 'show', '7701fdc9984dfbbf1df71f707a4cf6e48c95fa8e:.github/workflows/build-apk.yml'],
    text=True,
)
Path('.github/workflows/build-apk.yml').write_text(workflow, encoding='utf-8')
Path('scripts/fix_v072_custom_lambda.py').unlink()

final = path.read_text(encoding='utf-8')
if 'val applyCustomRange: () -> Unit = {' not in final or '\n                Unit\n            }' not in final:
    raise RuntimeError('explicit Unit lambda fix missing')

subprocess.run(['git', 'config', 'user.name', 'tsuguregi-ci'], check=True)
subprocess.run(['git', 'config', 'user.email', 'tsuguregi-ci@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'fix v0.72 custom range click lambda type'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FIX_COMMIT={sha}')
subprocess.run(['git', 'status', '--short'], check=True)
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.72'], check=True)
