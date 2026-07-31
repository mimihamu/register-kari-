#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PKG = ROOT / "app/src/main/java/jp/co/tenposinfo/register"


def patch(path: Path, transform) -> None:
    source = path.read_text(encoding="utf-8")
    updated = transform(source)
    if updated == source:
        raise RuntimeError(f"No change applied to {path.relative_to(ROOT)}")
    path.write_text(updated, encoding="utf-8")


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Missing patch target: {label}")
    return text.replace(old, new, 1)


def regex_required(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"Missing/ambiguous patch target: {label} ({count})")
    return updated


def patch_business_sync() -> None:
    path = PKG / "BusinessSyncFoundation.kt"

    def transform(text: str) -> str:
        text = replace_required(
            text,
            '        SchemaMigration.ensureColumn(db, "sync_outbox", "processing_started_at", "INTEGER")',
            '''        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_runtime_settings (
                setting_key TEXT PRIMARY KEY,
                setting_value TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT OR IGNORE INTO sync_runtime_settings(setting_key, setting_value) VALUES('folder_name', 'つぐレジ')",
        )
        SchemaMigration.ensureColumn(db, "sync_outbox", "processing_started_at", "INTEGER")''',
            "sync runtime settings schema",
        )

        text = replace_required(
            text,
            '    fun ensureOperationAndMasterTriggers(db: SQLiteDatabase) {',
            '''    fun updateFolderName(db: SQLiteDatabase, folderName: String) {
        ensureCore(db)
        val sanitized = OutboxObjectKey.sanitizeSegment(folderName)
        db.insertWithOnConflict(
            "sync_runtime_settings",
            null,
            ContentValues().apply {
                put("setting_key", "folder_name")
                put("setting_value", sanitized)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun ensureOperationAndMasterTriggers(db: SQLiteDatabase) {''',
            "sync folder update API",
        )

        old_prefix = "'REGISTER/' || business_date"
        new_prefix = "(SELECT setting_value FROM sync_runtime_settings WHERE setting_key = 'folder_name') || '/' || business_date"
        count = text.count(old_prefix)
        if count != 6:
            raise RuntimeError(f"Expected 6 legacy trigger prefixes, found {count}")
        text = text.replace(old_prefix, new_prefix)

        text = replace_required(
            text,
            '''    private fun createTrigger(db: SQLiteDatabase, name: String, sql: String) {
        runCatching { db.execSQL(sql) }.getOrElse { error("同期トリガー $name の作成に失敗しました: ${it.message}") }
    }''',
            '''    private fun createTrigger(db: SQLiteDatabase, name: String, sql: String) {
        runCatching {
            db.execSQL("DROP TRIGGER IF EXISTS $name")
            db.execSQL(sql)
        }.getOrElse { error("同期トリガー $name の作成に失敗しました: ${it.message}") }
    }''',
            "recreate trigger definitions",
        )

        text = replace_required(
            text,
            '''    fun rewriteUnstagedObjectKeys(db: SQLiteDatabase, folderName: String): Int {
        ensureCore(db)
        val rows = db.rawQuery(''',
            '''    fun rewriteUnstagedObjectKeys(db: SQLiteDatabase, folderName: String): Int {
        ensureCore(db)
        updateFolderName(db, folderName)
        val rows = db.rawQuery(''',
            "persist folder before rewriting outbox",
        )

        text = regex_required(
            text,
            r'    private fun reversalPayload\(db: SQLiteDatabase, record: JournalOutboxRecord\): String \{.*?\n    private fun settlementPayload',
            r'''    private fun reversalPayload(db: SQLiteDatabase, record: JournalOutboxRecord): String {
        val id = record.aggregateId.toLong()
        val header = db.rawQuery(
            "SELECT original_sale_id, reversal_type, gross_amount, reason, operator_name, created_at FROM reversal_transactions WHERE id = ?",
            arrayOf(id.toString()),
        ).use { cursor ->
            require(cursor.moveToFirst()) { "返品・取消が見つかりません" }
            listOf(
                cursor.getLong(0).toString(), cursor.getString(1), cursor.getLong(2).toString(),
                cursor.getString(3), cursor.getString(4), cursor.getLong(5).toString(),
            )
        }
        val payloadLines = db.rawQuery(
            """
            SELECT product_id, product_name, unit_price, tax_category, return_quantity, discount_amount,
                   tax_key, tax_label, tax_rate_percent, tax_included, taxable, reduced, tax_symbol
            FROM reversal_items
            WHERE reversal_id = ?
            ORDER BY id
            """.trimIndent(),
            arrayOf(id.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val legacy = TaxCategory.valueOf(cursor.getString(3))
                    val hasSnapshot = cursor.getString(6).isNotBlank()
                    add(
                        PayloadTaxLine(
                            productId = cursor.getString(0),
                            name = cursor.getString(1),
                            unitPrice = cursor.getLong(2),
                            legacyCategory = legacy,
                            quantity = cursor.getInt(4),
                            discount = cursor.getLong(5),
                            note = "",
                            snapshot = if (hasSnapshot) {
                                TaxSnapshot(
                                    key = cursor.getString(6),
                                    label = cursor.getString(7).ifBlank { legacy.displayName },
                                    ratePercent = cursor.getInt(8),
                                    taxIncluded = cursor.getInt(9) != 0,
                                    taxable = cursor.getInt(10) != 0,
                                    reduced = cursor.getInt(11) != 0,
                                    symbol = cursor.getString(12).ifBlank { legacy.symbol },
                                )
                            } else {
                                TaxSnapshot.from(legacy)
                            },
                        ),
                    )
                }
            }
        }
        val items = payloadLines.joinToString(",") { line ->
            val tax = line.snapshot
            "{\"productId\":\"${escape(line.productId)}\",\"name\":\"${escape(line.name)}\",\"unitPrice\":${line.unitPrice},\"quantity\":${line.quantity},\"discount\":${line.discount},\"taxKey\":\"${escape(tax.key)}\",\"taxLabel\":\"${escape(tax.label)}\",\"taxRatePercent\":${tax.ratePercent},\"taxIncluded\":${tax.taxIncluded},\"taxable\":${tax.taxable},\"reduced\":${tax.reduced},\"taxSymbol\":\"${escape(tax.symbol)}\"}"
        }
        val taxTotals = PayloadTaxAggregation.calculate(payloadLines).buckets.joinToString(",") { bucket ->
            val keys = bucket.sourceTaxKeys.joinToString(",") { "\"${escape(it)}\"" }
            "{\"ratePercent\":${bucket.ratePercent},\"taxable\":${bucket.taxable},\"netAmount\":${bucket.netAmount},\"taxAmount\":${bucket.taxAmount},\"grossAmount\":${bucket.grossAmount},\"taxKeys\":[$keys]}"
        }
        return """{"schema":"register.reversal.v2","eventId":"${escape(record.eventId)}","businessDate":"${escape(record.businessDate)}","reversalId":$id,"originalSaleId":${header[0]},"type":"${escape(header[1])}","grossAmount":${header[2]},"reason":"${escape(header[3])}","operator":"${escape(header[4])}","createdAt":${header[5]},"items":[$items],"taxTotals":[$taxTotals]}"""
    }

    private fun settlementPayload''',
            "reversal JSON full tax snapshot",
        )

        text = replace_required(
            text,
            '''            RegisterDatabase(appContext).use { database ->
                JournalOutboxSchema.ensureCore(database.writableDatabase)
                JournalOutboxSchema.ensureOperationAndMasterTriggers(database.writableDatabase)
            }''',
            '''            RegisterDatabase(appContext).use { database ->
                val db = database.writableDatabase
                JournalOutboxSchema.ensureCore(db)
                JournalOutboxSchema.updateFolderName(db, DriveSyncSettingsStore.load(appContext).folderName)
                JournalOutboxSchema.ensureOperationAndMasterTriggers(db)
            }''',
            "bootstrap current sync folder",
        )
        return text

    patch(path, transform)


def patch_dynamic_catalog() -> None:
    path = PKG / "DynamicCatalogRuntime.kt"

    def transform(text: str) -> str:
        text = regex_required(
            text,
            r'''                UPDATE menu_revision_products
                SET tax_label = CASE legacy_tax_category.*?                WHERE tax_label = ''
''',
            '''                UPDATE menu_revision_products
                SET tax_label = COALESCE(
                        NULLIF((SELECT t.label FROM dynamic_tax_rules t WHERE t.tax_key = menu_revision_products.tax_key), ''),
                        CASE legacy_tax_category
                            WHEN 'INCLUDED_10' THEN '10%内税' WHEN 'EXCLUDED_10' THEN '10%外税'
                            WHEN 'INCLUDED_8' THEN '8%内税' WHEN 'EXCLUDED_8' THEN '8%外税' ELSE '非課税' END
                    ),
                    tax_rate_percent = COALESCE(
                        (SELECT t.rate_percent FROM dynamic_tax_rules t WHERE t.tax_key = menu_revision_products.tax_key),
                        CASE legacy_tax_category
                            WHEN 'INCLUDED_10' THEN 10 WHEN 'EXCLUDED_10' THEN 10
                            WHEN 'INCLUDED_8' THEN 8 WHEN 'EXCLUDED_8' THEN 8 ELSE 0 END
                    ),
                    tax_included = COALESCE(
                        (SELECT CASE WHEN t.price_mode = 'INCLUDED' THEN 1 ELSE 0 END FROM dynamic_tax_rules t WHERE t.tax_key = menu_revision_products.tax_key),
                        CASE WHEN legacy_tax_category IN ('INCLUDED_10','INCLUDED_8') THEN 1 ELSE 0 END
                    ),
                    taxable = COALESCE(
                        (SELECT CASE WHEN t.price_mode = 'NON_TAXABLE' THEN 0 ELSE 1 END FROM dynamic_tax_rules t WHERE t.tax_key = menu_revision_products.tax_key),
                        CASE WHEN legacy_tax_category = 'NON_TAXABLE' THEN 0 ELSE 1 END
                    ),
                    reduced = COALESCE(
                        (SELECT t.reduced FROM dynamic_tax_rules t WHERE t.tax_key = menu_revision_products.tax_key),
                        CASE WHEN legacy_tax_category IN ('INCLUDED_8','EXCLUDED_8') THEN 1 ELSE 0 END
                    ),
                    tax_symbol = COALESCE(
                        NULLIF((SELECT t.symbol FROM dynamic_tax_rules t WHERE t.tax_key = menu_revision_products.tax_key), ''),
                        CASE legacy_tax_category
                            WHEN 'INCLUDED_10' THEN '内' WHEN 'EXCLUDED_10' THEN '外'
                            WHEN 'INCLUDED_8' THEN '内※' WHEN 'EXCLUDED_8' THEN '外※' ELSE '非' END
                    )
                WHERE tax_label = ''
''',
            "preserve custom tax snapshots for existing scheduled revisions",
        )
        return text

    patch(path, transform)


def patch_operator_session() -> None:
    path = PKG / "OperatorSession.kt"

    def transform(text: str) -> str:
        return replace_required(
            text,
            '''        if (current == null || current.id != operatorId || OperatorSessionRevisionPolicy.shouldReload(current.revision, stored.revision)) {
            cached = stored
            return stored
        }''',
            '''        if (current == null || current.id != operatorId || current != stored) {
            cached = stored
            return stored
        }''',
            "refresh any permission/name/role change even without revision bump",
        )

    patch(path, transform)


def main() -> None:
    patch_business_sync()
    patch_dynamic_catalog()
    patch_operator_session()
    Path(__file__).unlink()
    print("v0.11.1 finalization patch complete")


if __name__ == "__main__":
    main()
