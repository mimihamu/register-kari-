from pathlib import Path

path = Path('app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt')
s = path.read_text(encoding='utf-8')
old = '''                return failWithoutReplacement(
                    planFile,
                    pending,
                    resultFile,
                    database,
                    "復元中止・元DB保持。復元前ロールバックを安全に作成できません: ${error.message}",
                )'''
new = '''                return failWithoutReplacement(
                    context,
                    planFile,
                    pending,
                    resultFile,
                    database,
                    "復元中止・元DB保持。復元前ロールバックを安全に作成できません: ${error.message}",
                )'''
if old not in s:
    raise SystemExit('BKP-010 multiline failWithoutReplacement call not found')
s = s.replace(old, new, 1)
path.write_text(s, encoding='utf-8')
