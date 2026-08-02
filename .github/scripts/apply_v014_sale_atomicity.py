from pathlib import Path

path = Path("app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt")
text = path.read_text(encoding="utf-8")
old = '''            JournalOutboxSchema.recordSale(
                db = this,
                saleId = saleId,
                totalAmount = summary.grossAmount,
                taxAmount = summary.taxAmount,
                createdAt = createdAt,
                businessDate = businessLink.businessDate,
                folderName = DriveSyncSettingsStore.load(applicationContext).folderName,
            )
            saleId
'''
new = '''            JournalOutboxSchema.recordSale(
                db = this,
                saleId = saleId,
                totalAmount = summary.grossAmount,
                taxAmount = summary.taxAmount,
                createdAt = createdAt,
                businessDate = businessLink.businessDate,
                folderName = DriveSyncSettingsStore.load(applicationContext).folderName,
            )
            // 売上確定と作業中カート消去を同一トランザクションに含める。
            // 確定直後にプロセスが停止しても、確定済み明細を未会計として復元しない。
            delete("cart_items", null, null)
            delete(
                "line_tax_snapshots",
                "scope = ? AND owner_id = ?",
                arrayOf(LineTaxSnapshotStore.SCOPE_CART, "0"),
            )
            saleId
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"saveSale insertion: expected 1 match, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("atomic sale/cart commit applied")
