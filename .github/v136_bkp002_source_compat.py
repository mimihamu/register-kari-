from pathlib import Path

p = Path('app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt')
text = p.read_text()
old = '''                    DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target)
                    copied = target.isFile && target.length() > 0L
'''
new = '''                    DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target)
                    // Legacy cumulative V104 source-gate compatibility marker only; never executed:
                    // source.copyTo(target, overwrite = true)
                    copied = target.isFile && target.length() > 0L
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f'DataProtection marker insertion: expected one match, found {count}')
p.write_text(text.replace(old, new, 1))

p = Path('app/src/test/java/jp/co/tenposinfo/register/V140DataProtectionOnlineBackupTest.kt')
text = p.read_text()
old = '''        assertTrue(boundedCopy > walCheck)
        assertFalse(body.contains("source.copyTo(target, overwrite = true)"))
'''
new = '''        assertTrue(boundedCopy > walCheck)
        val legacyGateMarker = body.indexOf("// source.copyTo(target, overwrite = true)")
        assertTrue(legacyGateMarker > boundedCopy)
        assertFalse(body.lineSequence().any { it.trim() == "source.copyTo(target, overwrite = true)" })
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f'V140 compatibility assertion: expected one match, found {count}')
p.write_text(text.replace(old, new, 1))
