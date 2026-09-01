from pathlib import Path

path = Path("app/src/main/java/jp/co/tenposinfo/register/RestoreTerminalMigrationV136.kt")
text = path.read_text()
old = '''            RestoreTerminalModeV136.SPARE_TERMINAL -> {
                val trustedStoreName = backupStoreName
'''
new = '''            RestoreTerminalModeV136.SPARE_TERMINAL -> {
                require(currentIdentity.storeId == "STORE-UNCONFIGURED") {
                    "予備端末移行は店舗未設定の新品端末でのみ実行できます"
                }
                require(currentKnownMaxSaleId == 0L) {
                    "予備端末に既存の売上または採番履歴があるため移行できません"
                }
                require(currentIdentity.generation == 1L) {
                    "予備端末に既存の端末世代情報があるため移行できません"
                }
                require(backupIdentity.storeId.isNotBlank() && backupIdentity.storeId != "STORE-UNCONFIGURED") {
                    "バックアップ元のstoreIdを安全に確認できません"
                }
                val trustedStoreName = backupStoreName
'''
if text.count(old) != 1:
    raise SystemExit(f"BKP-005 follow-up anchor count={text.count(old)}")
path.write_text(text.replace(old, new, 1))
print("BKP-005 fail-closed follow-up applied")
