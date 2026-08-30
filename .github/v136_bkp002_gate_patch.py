from pathlib import Path

p = Path('.github/workflows/build-apk.yml')
text = p.read_text()
old = """          ' /tmp/v133-build-apk.yml > /tmp/v133-cumulative-checks.sh
          test -s /tmp/v133-cumulative-checks.sh
          bash -euxo pipefail /tmp/v133-cumulative-checks.sh

          python3 - <<'PY'
          from pathlib import Path

          def active_gradle(path):
"""
new = """          ' /tmp/v133-build-apk.yml > /tmp/v133-cumulative-checks.sh

          # BKP-002 supersedes only the V104 raw fallback-copy implementation. Keep the
          # original checkpoint -> transaction -> WAL recheck -> copy ordering assertion,
          # but make that copied assertion point at the bounded writer-block helper.
          python3 - <<'PY'
          from pathlib import Path
          gate = Path('/tmp/v133-cumulative-checks.sh')
          source = gate.read_text()
          legacy = 'source.copyTo(target, overwrite = true)'
          replacement = 'DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target)'
          count = source.count(legacy)
          assert count == 1, f'expected exactly one V104 legacy copy assertion, found {count}'
          gate.write_text(source.replace(legacy, replacement, 1))
          PY

          test -s /tmp/v133-cumulative-checks.sh
          bash -euxo pipefail /tmp/v133-cumulative-checks.sh

          # v1.36 BKP-002 online-backup acceptance. These checks add to, rather than
          # replace, the cumulative V104 crash-safety ordering gate above.
          test -s app/src/main/java/jp/co/tenposinfo/register/DataProtectionOnlineBackupV136.kt
          test -s app/src/test/java/jp/co/tenposinfo/register/V140DataProtectionOnlineBackupTest.kt
          test -s docs/V1.36_BKP_002_ONLINE_BACKUP.md
          grep -Fq 'DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target)' app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt
          grep -Fq 'MAX_FALLBACK_WRITER_BLOCK_MILLIS = 2_000L' app/src/main/java/jp/co/tenposinfo/register/DataProtectionOnlineBackupV136.kt
          grep -Fq 'PERFORMANCE_TARGET_TRANSACTIONS = 10_000' app/src/main/java/jp/co/tenposinfo/register/DataProtectionOnlineBackupV136.kt
          grep -Fq 'PERFORMANCE_TARGET_MILLIS = 30_000L' app/src/main/java/jp/co/tenposinfo/register/DataProtectionOnlineBackupV136.kt
          ! grep -Fq 'source.copyTo(target, overwrite = true)' app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt
          grep -Fq 'fallbackCopyAbortsInsteadOfHoldingSalesWriterIndefinitely' app/src/test/java/jp/co/tenposinfo/register/V140DataProtectionOnlineBackupTest.kt

          python3 - <<'PY'
          from pathlib import Path

          def active_gradle(path):
"""
count = text.count(old)
if count != 1:
    raise SystemExit(f'workflow insertion point: expected one match, found {count}')
p.write_text(text.replace(old, new, 1))
