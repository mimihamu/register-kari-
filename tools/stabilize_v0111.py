#!/usr/bin/env python3
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
SRC = APP / "src/main/java"
PKG = SRC / "jp/co/tenposinfo/register"


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"v0.11.1 stabilization failed: {label}")
    return text.replace(old, new, 1)


def regex_required(text: str, pattern: str, replacement: str, label: str, flags: int = re.S) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"v0.11.1 stabilization failed: {label} (matches={count})")
    return updated


def patch(path: Path, transform) -> None:
    source = path.read_text(encoding="utf-8")
    updated = transform(source)
    if updated == source:
        raise RuntimeError(f"v0.11.1 stabilization made no change: {path.relative_to(ROOT)}")
    path.write_text(updated, encoding="utf-8")


def integrate_generated_sources() -> None:
    generator = ROOT / "tools/generate_v010.py"
    if not generator.exists():
        raise RuntimeError("tools/generate_v010.py is missing")
    with tempfile.TemporaryDirectory(prefix="v0111-") as temp:
        generated = Path(temp) / "main"
        subprocess.run([sys.executable, str(generator), str(APP), str(generated)], check=True)
        if SRC.exists():
            shutil.rmtree(SRC)
        shutil.copytree(generated, SRC)


def write_foundation() -> None:
    (PKG / "StabilizationFoundation.kt").write_text(
        r'''package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate

/** v0.11.1で営業日・税スナップショット・Outboxリースを共通化する。 */
data class BusinessSessionLink(
    val sessionId: Long?,
    val businessDate: String,
)

data class BusinessSessionWindow(
    val id: Long,
    val businessDate: String,
    val openedAt: Long,
    val closedAt: Long?,
)

object BusinessSessionAttributionPolicy {
    fun resolve(createdAt: Long, sessions: List<BusinessSessionWindow>): BusinessSessionWindow? =
        sessions
            .asSequence()
            .filter { createdAt >= it.openedAt && (it.closedAt == null || createdAt <= it.closedAt) }
            .maxByOrNull { it.openedAt }
}

object SchemaMigration {
    fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return true
            }
            false
        }

    fun ensureColumn(db: SQLiteDatabase, table: String, column: String, definition: String) {
        if (!hasColumn(db, table, column)) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) > 0
        }
}

object BusinessSessionSchema {
    fun ensure(db: SQLiteDatabase) {
        if (!SchemaMigration.tableExists(db, "sales")) return
        SchemaMigration.ensureColumn(db, "sales", "business_session_id", "INTEGER")
        SchemaMigration.ensureColumn(db, "sales", "business_date", "TEXT")
        if (SchemaMigration.tableExists(db, "business_sessions")) {
            backfill(db, "sales")
            if (SchemaMigration.tableExists(db, "cash_movements")) {
                SchemaMigration.ensureColumn(db, "cash_movements", "business_session_id", "INTEGER")
                SchemaMigration.ensureColumn(db, "cash_movements", "business_date", "TEXT")
                backfill(db, "cash_movements")
            }
            if (SchemaMigration.tableExists(db, "reversal_transactions")) {
                SchemaMigration.ensureColumn(db, "reversal_transactions", "business_session_id", "INTEGER")
                SchemaMigration.ensureColumn(db, "reversal_transactions", "business_date", "TEXT")
                backfill(db, "reversal_transactions")
            }
            if (SchemaMigration.tableExists(db, "settlement_reports")) {
                SchemaMigration.ensureColumn(db, "settlement_reports", "business_session_id", "INTEGER")
                db.execSQL(
                    """
                    UPDATE settlement_reports
                    SET business_session_id = (
                        SELECT bs.id FROM business_sessions bs
                        WHERE bs.business_date = settlement_reports.business_date
                        ORDER BY bs.opened_at DESC LIMIT 1
                    )
                    WHERE business_session_id IS NULL
                    """.trimIndent(),
                )
            }
        }
    }

    fun current(db: SQLiteDatabase, calendarDate: LocalDate = LocalDate.now()): BusinessSessionLink {
        ensure(db)
        if (!SchemaMigration.tableExists(db, "business_sessions")) return BusinessSessionLink(null, calendarDate.toString())
        return db.rawQuery(
            """
            SELECT id, business_date
            FROM business_sessions
            WHERE status IN ('OPEN','Z_SETTLED')
            ORDER BY opened_at DESC LIMIT 1
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) BusinessSessionLink(cursor.getLong(0), cursor.getString(1))
            else BusinessSessionLink(null, calendarDate.toString())
        }
    }

    fun sessionForDate(db: SQLiteDatabase, date: LocalDate): BusinessSessionWindow? {
        ensure(db)
        if (!SchemaMigration.tableExists(db, "business_sessions")) return null
        return db.rawQuery(
            "SELECT id, business_date, opened_at, closed_at FROM business_sessions WHERE business_date=? ORDER BY opened_at DESC LIMIT 1",
            arrayOf(date.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else BusinessSessionWindow(
                id = cursor.getLong(0),
                businessDate = cursor.getString(1),
                openedAt = cursor.getLong(2),
                closedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
            )
        }
    }

    private fun backfill(db: SQLiteDatabase, table: String) {
        db.execSQL(
            """
            UPDATE $table
            SET business_session_id = (
                    SELECT bs.id FROM business_sessions bs
                    WHERE bs.opened_at <= $table.created_at
                      AND (bs.closed_at IS NULL OR $table.created_at <= bs.closed_at)
                    ORDER BY bs.opened_at DESC LIMIT 1
                ),
                business_date = (
                    SELECT bs.business_date FROM business_sessions bs
                    WHERE bs.opened_at <= $table.created_at
                      AND (bs.closed_at IS NULL OR $table.created_at <= bs.closed_at)
                    ORDER BY bs.opened_at DESC LIMIT 1
                )
            WHERE business_session_id IS NULL
            """.trimIndent(),
        )
    }
}

data class TaxSnapshot(
    val key: String,
    val label: String,
    val ratePercent: Int,
    val taxIncluded: Boolean,
    val taxable: Boolean,
    val reduced: Boolean,
    val symbol: String,
) {
    fun applyTo(product: Product): Product = product.copy(
        taxKey = key,
        taxLabel = label,
        taxRatePercent = ratePercent,
        taxIncluded = taxIncluded,
        taxable = taxable,
        reducedTax = reduced,
        taxSymbol = symbol,
    )

    companion object {
        fun from(product: Product) = TaxSnapshot(
            key = product.taxKey,
            label = product.taxLabel,
            ratePercent = product.taxRatePercent,
            taxIncluded = product.taxIncluded,
            taxable = product.taxable,
            reduced = product.reducedTax,
            symbol = product.taxSymbol,
        )

        fun from(rule: DynamicTaxRule) = TaxSnapshot(
            key = rule.key,
            label = rule.label,
            ratePercent = rule.ratePercent,
            taxIncluded = rule.taxIncluded,
            taxable = rule.taxable,
            reduced = rule.reduced,
            symbol = rule.symbol,
        )

        fun from(category: TaxCategory) = TaxSnapshot(
            key = category.name,
            label = category.displayName,
            ratePercent = category.ratePercent,
            taxIncluded = category.taxIncluded,
            taxable = category.taxable,
            reduced = category.symbol.contains("※"),
            symbol = category.symbol,
        )
    }
}

object TaxSnapshotSchema {
    fun ensureReversalColumns(db: SQLiteDatabase) {
        if (!SchemaMigration.tableExists(db, "reversal_items")) return
        SchemaMigration.ensureColumn(db, "reversal_items", "tax_key", "TEXT NOT NULL DEFAULT ''")
        SchemaMigration.ensureColumn(db, "reversal_items", "tax_label", "TEXT NOT NULL DEFAULT ''")
        SchemaMigration.ensureColumn(db, "reversal_items", "tax_rate_percent", "INTEGER NOT NULL DEFAULT 0")
        SchemaMigration.ensureColumn(db, "reversal_items", "tax_included", "INTEGER NOT NULL DEFAULT 0")
        SchemaMigration.ensureColumn(db, "reversal_items", "taxable", "INTEGER NOT NULL DEFAULT 0")
        SchemaMigration.ensureColumn(db, "reversal_items", "reduced", "INTEGER NOT NULL DEFAULT 0")
        SchemaMigration.ensureColumn(db, "reversal_items", "tax_symbol", "TEXT NOT NULL DEFAULT ''")
    }
}

object OutboxLeasePolicy {
    const val LEASE_MILLIS: Long = 10L * 60L * 1_000L
    fun isStale(leaseUntil: Long?, now: Long): Boolean = leaseUntil == null || leaseUntil <= now
}

object OperatorSessionRevisionPolicy {
    fun shouldReload(cachedRevision: Long, storedRevision: Long): Boolean = cachedRevision != storedRevision
    fun mayContinue(enabled: Boolean, permissions: Set<RegisterPermission>): Boolean =
        enabled && RegisterPermission.SALES in permissions
}

data class PayloadTaxLine(
    val productId: String,
    val name: String,
    val unitPrice: Long,
    val quantity: Int,
    val discount: Long,
    val note: String,
    val legacyCategory: TaxCategory,
    val snapshot: TaxSnapshot,
) {
    fun toCartItem(): CartItem {
        val product = snapshot.applyTo(
            Product(productId, name, unitPrice, legacyCategory, 1),
        )
        return CartItem(product, quantity, unitPrice, discount, note)
    }
}

object PayloadTaxAggregation {
    fun calculate(lines: List<PayloadTaxLine>): TaxSummary = TaxEngine.calculate(lines.map(PayloadTaxLine::toCartItem))
}
''',
        encoding="utf-8",
    )


def patch_gradle_and_branding() -> None:
    def gradle(text: str) -> str:
        text = regex_required(text, r'\nval generatedV010Dir =.*?\nandroid \{', '\nandroid {', 'remove generated source task')
        text = regex_required(text, r'\n    sourceSets \{.*?\n    \}\n', '\n', 'remove generated sourceSet')
        text = regex_required(text, r'\ntasks\.matching \{ it\.name == "preBuild" \}\.configureEach \{.*?\n\}\n', '\n', 'remove preBuild dependency')
        text = text.replace('versionCode = 11', 'versionCode = 12')
        text = text.replace('versionName = "0.11.0-dev"', 'versionName = "0.11.1-dev"')
        return text
    patch(APP / "build.gradle.kts", gradle)
    patch(ROOT / "settings.gradle.kts", lambda s: s.replace('rootProject.name = "REGISTER-kari"', 'rootProject.name = "つぐレジ"'))
    patch(APP / "src/main/AndroidManifest.xml", lambda s: s.replace('android:label="REGISTER（仮）"', 'android:label="つぐレジ"'))

    workflow = ROOT / ".github/workflows/build-apk.yml"
    def workflow_patch(text: str) -> str:
        text = text.replace('name: Build REGISTER APK', 'name: Build つぐレジ APK')
        text = text.replace('REGISTER_v0.11_debug.apk', 'TSUGUREGI_v0.11.1_debug.apk')
        text = text.replace('REGISTER-v0.11-debug-apk', 'TSUGUREGI-v0.11.1-debug-apk')
        return text
    patch(workflow, workflow_patch)


def patch_register_database() -> None:
    path = PKG / "RegisterDatabase.kt"
    def transform(text: str) -> str:
        text = replace_required(
            text,
            ') {\n    override fun onCreate(db: SQLiteDatabase) {',
            ') {\n    private val applicationContext = context.applicationContext\n\n    override fun onCreate(db: SQLiteDatabase) {',
            'store application context',
        )
        text = replace_required(
            text,
            '        val summary = TaxEngine.calculate(items)\n        require(paymentState.remaining(summary.grossAmount) == 0L) { "Payment is incomplete" }\n        val createdAt = System.currentTimeMillis()\n        return writableDatabase.runInTransactionWithResult {',
            '        val summary = TaxEngine.calculate(items)\n        require(paymentState.remaining(summary.grossAmount) == 0L) { "Payment is incomplete" }\n        BusinessSessionSchema.ensure(writableDatabase)\n        val businessLink = BusinessSessionSchema.current(writableDatabase)\n        val createdAt = System.currentTimeMillis()\n        return writableDatabase.runInTransactionWithResult {',
            'capture business session at sale',
        )
        text = replace_required(
            text,
            '                    put("created_at", createdAt)\n                    put("print_count", 0)',
            '                    businessLink.sessionId?.let { put("business_session_id", it) }\n                    put("business_date", businessLink.businessDate)\n                    put("created_at", createdAt)\n                    put("print_count", 0)',
            'persist sale business session',
        )
        text = replace_required(
            text,
            '            JournalOutboxSchema.recordSale(this, saleId, summary.grossAmount, summary.taxAmount, createdAt)',
            '            JournalOutboxSchema.recordSale(\n                db = this,\n                saleId = saleId,\n                totalAmount = summary.grossAmount,\n                taxAmount = summary.taxAmount,\n                createdAt = createdAt,\n                businessDate = businessLink.businessDate,\n                folderName = DriveSyncSettingsStore.load(applicationContext).folderName,\n            )',
            'record sale with folder and business date',
        )
        return text
    patch(path, transform)


def patch_advanced_operations() -> None:
    path = PKG / "AdvancedOperationsStore.kt"
    def transform(text: str) -> str:
        text = text.replace('import java.time.ZoneId\n', '')
        text = regex_required(
            text,
            r'data class ReturnLineRecord\(.*?\n\}\n\ndata class ReversalSaveResult',
            '''data class ReturnLineRecord(
    val saleItemId: Long,
    val productId: String,
    val productName: String,
    val unitPrice: Long,
    val taxCategory: TaxCategory,
    val taxKey: String,
    val taxLabel: String,
    val taxRatePercent: Int,
    val taxIncluded: Boolean,
    val taxable: Boolean,
    val reduced: Boolean,
    val taxSymbol: String,
    val originalQuantity: Int,
    val originalDiscount: Long,
    val note: String,
    val returnedQuantity: Int,
    val refundedDiscount: Long,
) {
    val remainingQuantity: Int get() = (originalQuantity - returnedQuantity).coerceAtLeast(0)
    val remainingDiscount: Long get() = (originalDiscount - refundedDiscount).coerceAtLeast(0)

    fun toReturnItem(quantity: Int): CartItem {
        require(quantity in 1..remainingQuantity) { "返品数量が残数を超えています" }
        val allocatedDiscount = if (quantity == remainingQuantity) {
            remainingDiscount
        } else {
            (originalDiscount * quantity / originalQuantity).coerceAtMost(remainingDiscount)
        }
        val product = TaxSnapshot(
            key = taxKey,
            label = taxLabel,
            ratePercent = taxRatePercent,
            taxIncluded = taxIncluded,
            taxable = taxable,
            reduced = reduced,
            symbol = taxSymbol,
        ).applyTo(
            Product(productId, productName, unitPrice, taxCategory, saleItemId.toInt()),
        )
        return CartItem(product, quantity, unitPrice, allocatedDiscount, note)
    }
}

data class ReversalSaveResult''',
            'replace return line snapshot model',
        )
        text = regex_required(
            text,
            r'    fun dailySummary\(.*?\n    fun recordCashMovement',
            '''    fun dailySummary(date: LocalDate = activeSession()?.let { LocalDate.parse(it.businessDate) } ?: LocalDate.now()): AdvancedDailySummary {
        BusinessSessionSchema.ensure(db)
        val session = BusinessSessionSchema.sessionForDate(db, date)
            ?: activeSession()?.takeIf { it.businessDate == date.toString() }
            ?: error("営業日 ${date} の営業セッションが見つかりません")
        val sessionId = session.id
        val dateText = session.businessDate
        val salesGross = longQuery(
            "SELECT COALESCE(SUM(total_amount), 0) FROM sales WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        )
        val transactionCount = longQuery(
            "SELECT COUNT(*) FROM sales WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        ).toInt()
        val reversalGross = longQuery(
            "SELECT COALESCE(SUM(gross_amount), 0) FROM reversal_transactions WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        )
        val reversalCount = longQuery(
            "SELECT COUNT(*) FROM reversal_transactions WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        ).toInt()

        val paymentMap = linkedMapOf<String, Long>()
        db.rawQuery(
            """
            SELECT p.payment_method, COALESCE(SUM(p.applied_amount), 0)
            FROM sale_payments p
            INNER JOIN sales s ON s.id = p.sale_id
            WHERE s.business_session_id = ?
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) paymentMap[cursor.getString(0)] = cursor.getLong(1)
        }
        db.rawQuery(
            """
            SELECT p.payment_method, COALESCE(SUM(p.amount), 0)
            FROM reversal_payments p
            INNER JOIN reversal_transactions r ON r.id = p.reversal_id
            WHERE r.business_session_id = ?
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val method = cursor.getString(0)
                paymentMap[method] = (paymentMap[method] ?: 0L) - cursor.getLong(1)
            }
        }

        val cashIn = movementTotal(CashMovementType.IN, sessionId)
        val cashOut = movementTotal(CashMovementType.OUT, sessionId)
        val openingCash = session.openingCash
        val expectedCash = openingCash + (paymentMap[PaymentMethod.CASH.name] ?: 0L) + cashIn - cashOut
        val pendingPrints = longQuery(
            "SELECT COUNT(*) FROM print_jobs WHERE status <> ?",
            arrayOf(PrintJobStatus.COMPLETED.name),
        ).toInt() + longQuery(
            "SELECT COUNT(*) FROM document_print_jobs WHERE status <> ?",
            arrayOf(PrintJobStatus.COMPLETED.name),
        ).toInt()
        val heldTickets = longQuery("SELECT COUNT(*) FROM held_tickets").toInt()
        val settled = longQuery(
            "SELECT COUNT(*) FROM settlement_reports WHERE business_session_id = ? AND report_type = ?",
            arrayOf(sessionId.toString(), SettlementReportType.Z_SETTLEMENT.name),
        ) > 0
        return AdvancedDailySummary(
            businessDate = dateText,
            salesGross = salesGross,
            reversalGross = reversalGross,
            netSales = salesGross - reversalGross,
            transactionCount = transactionCount,
            reversalCount = reversalCount,
            paymentTotals = paymentMap.map { PaymentTotal(it.key, it.value) },
            openingCash = openingCash,
            cashIn = cashIn,
            cashOut = cashOut,
            expectedCash = expectedCash,
            pendingPrints = pendingPrints,
            heldTickets = heldTickets,
            settled = settled,
        )
    }

    fun recordCashMovement''',
            'replace calendar-day aggregation',
        )
        text = replace_required(
            text,
            '                    put("operator_name", operatorName.trim())\n                    put("created_at", now)',
            '                    put("operator_name", operatorName.trim())\n                    put("business_session_id", session.id)\n                    put("business_date", session.businessDate)\n                    put("created_at", now)',
            'cash movement business session',
        )
        text = replace_required(
            text,
            '                    put("business_date", summary.businessDate)\n                    put("report_type", type.name)',
            '                    put("business_session_id", session.id)\n                    put("business_date", summary.businessDate)\n                    put("report_type", type.name)',
            'settlement business session',
        )
        text = regex_required(
            text,
            r'    fun loadReturnableLines\(saleId: Long\): List<ReturnLineRecord> = db\.rawQuery\(.*?\n    fun createReversal',
            '''    fun loadReturnableLines(saleId: Long): List<ReturnLineRecord> = db.rawQuery(
        """
        SELECT si.id, si.product_id, si.product_name, si.unit_price, si.tax_category,
               COALESCE(lts.tax_key, si.tax_category),
               COALESCE(lts.tax_label, si.tax_category),
               COALESCE(lts.rate_percent, CASE si.tax_category WHEN 'INCLUDED_10' THEN 10 WHEN 'EXCLUDED_10' THEN 10 WHEN 'INCLUDED_8' THEN 8 WHEN 'EXCLUDED_8' THEN 8 ELSE 0 END),
               COALESCE(lts.tax_included, CASE WHEN si.tax_category IN ('INCLUDED_10','INCLUDED_8') THEN 1 ELSE 0 END),
               COALESCE(lts.taxable, CASE WHEN si.tax_category = 'NON_TAXABLE' THEN 0 ELSE 1 END),
               COALESCE(lts.reduced, CASE WHEN si.tax_category IN ('INCLUDED_8','EXCLUDED_8') THEN 1 ELSE 0 END),
               COALESCE(lts.tax_symbol, CASE si.tax_category WHEN 'INCLUDED_10' THEN '内' WHEN 'EXCLUDED_10' THEN '外' WHEN 'INCLUDED_8' THEN '内※' WHEN 'EXCLUDED_8' THEN '外※' ELSE '非' END),
               si.quantity, si.discount_amount, si.note,
               COALESCE(SUM(ri.return_quantity), 0) AS returned_quantity,
               COALESCE(SUM(ri.discount_amount), 0) AS refunded_discount
        FROM sale_items si
        LEFT JOIN line_tax_snapshots lts
          ON lts.scope = 'SALE'
         AND lts.owner_id = si.sale_id
         AND lts.line_no = (SELECT COUNT(*) FROM sale_items si2 WHERE si2.sale_id = si.sale_id AND si2.id <= si.id)
        LEFT JOIN reversal_items ri ON ri.sale_item_id = si.id
        WHERE si.sale_id = ?
        GROUP BY si.id, si.product_id, si.product_name, si.unit_price, si.tax_category,
                 lts.tax_key, lts.tax_label, lts.rate_percent, lts.tax_included, lts.taxable, lts.reduced, lts.tax_symbol,
                 si.quantity, si.discount_amount, si.note
        ORDER BY si.id ASC
        """.trimIndent(),
        arrayOf(saleId.toString()),
    ).use { cursor ->
        val result = mutableListOf<ReturnLineRecord>()
        while (cursor.moveToNext()) {
            val legacy = TaxCategory.valueOf(cursor.getString(4))
            result += ReturnLineRecord(
                saleItemId = cursor.getLong(0),
                productId = cursor.getString(1),
                productName = cursor.getString(2),
                unitPrice = cursor.getLong(3),
                taxCategory = legacy,
                taxKey = cursor.getString(5),
                taxLabel = cursor.getString(6).takeUnless { it == legacy.name } ?: legacy.displayName,
                taxRatePercent = cursor.getInt(7),
                taxIncluded = cursor.getInt(8) != 0,
                taxable = cursor.getInt(9) != 0,
                reduced = cursor.getInt(10) != 0,
                taxSymbol = cursor.getString(11),
                originalQuantity = cursor.getInt(12),
                originalDiscount = cursor.getLong(13),
                note = cursor.getString(14),
                returnedQuantity = cursor.getInt(15),
                refundedDiscount = cursor.getLong(16),
            )
        }
        result
    }

    fun createReversal''',
            'load full sale tax snapshot for returns',
        )
        text = replace_required(
            text,
            '                    put("operator_name", operatorName.trim())\n                    put("created_at", now)\n                },\n            )\n            selected.forEach',
            '                    put("operator_name", operatorName.trim())\n                    put("business_session_id", session.id)\n                    put("business_date", session.businessDate)\n                    put("created_at", now)\n                },\n            )\n            selected.forEach',
            'reversal business session',
        )
        text = replace_required(
            text,
            '                        put("tax_category", line.taxCategory.name)\n                        put("original_quantity", line.originalQuantity)',
            '                        put("tax_category", line.taxCategory.name)\n                        put("tax_key", line.taxKey)\n                        put("tax_label", line.taxLabel)\n                        put("tax_rate_percent", line.taxRatePercent)\n                        put("tax_included", if (line.taxIncluded) 1 else 0)\n                        put("taxable", if (line.taxable) 1 else 0)\n                        put("reduced", if (line.reduced) 1 else 0)\n                        put("tax_symbol", line.taxSymbol)\n                        put("original_quantity", line.originalQuantity)',
            'persist reversal tax snapshot',
        )
        text = regex_required(
            text,
            r'    private fun movementTotal\(type: CashMovementType, from: Long, to: Long\): Long = longQuery\(.*?\n    private fun longQuery',
            '''    private fun movementTotal(type: CashMovementType, sessionId: Long): Long = longQuery(
        "SELECT COALESCE(SUM(amount), 0) FROM cash_movements WHERE movement_type = ? AND business_session_id = ?",
        arrayOf(type.name, sessionId.toString()),
    )

    private fun longQuery''',
            'replace movement date bounds',
        )
        text = text.replace(
            '                operator_name TEXT NOT NULL,\n                created_at INTEGER NOT NULL\n            )',
            '                operator_name TEXT NOT NULL,\n                business_session_id INTEGER,\n                business_date TEXT,\n                created_at INTEGER NOT NULL\n            )',
            1,
        )
        text = text.replace(
            '                operator_name TEXT NOT NULL,\n                created_at INTEGER NOT NULL,\n                FOREIGN KEY(original_sale_id)',
            '                operator_name TEXT NOT NULL,\n                business_session_id INTEGER,\n                business_date TEXT,\n                created_at INTEGER NOT NULL,\n                FOREIGN KEY(original_sale_id)',
            1,
        )
        text = replace_required(
            text,
            '                tax_category TEXT NOT NULL,\n                original_quantity INTEGER NOT NULL,',
            '                tax_category TEXT NOT NULL,\n                tax_key TEXT NOT NULL DEFAULT \'\',\n                tax_label TEXT NOT NULL DEFAULT \'\',\n                tax_rate_percent INTEGER NOT NULL DEFAULT 0,\n                tax_included INTEGER NOT NULL DEFAULT 0,\n                taxable INTEGER NOT NULL DEFAULT 0,\n                reduced INTEGER NOT NULL DEFAULT 0,\n                tax_symbol TEXT NOT NULL DEFAULT \'\',\n                original_quantity INTEGER NOT NULL,',
            'reversal item tax schema',
        )
        text = replace_required(
            text,
            '                business_date TEXT NOT NULL,\n                report_type TEXT NOT NULL,',
            '                business_session_id INTEGER,\n                business_date TEXT NOT NULL,\n                report_type TEXT NOT NULL,',
            'settlement session schema',
        )
        text = replace_required(
            text,
            '        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_date ON settlement_reports(business_date, report_type)")',
            '        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_date ON settlement_reports(business_date, report_type)")\n        BusinessSessionSchema.ensure(db)\n        TaxSnapshotSchema.ensureReversalColumns(db)',
            'ensure stabilization columns',
        )
        return text
    patch(path, transform)


def patch_dynamic_catalog() -> None:
    path = PKG / "DynamicCatalogRuntime.kt"
    def transform(text: str) -> str:
        text = replace_required(
            text,
            '    val taxKey: String,\n    val buttonColor: String,',
            '    val taxKey: String,\n    val taxLabel: String,\n    val taxRatePercent: Int,\n    val taxIncluded: Boolean,\n    val taxable: Boolean,\n    val reduced: Boolean,\n    val taxSymbol: String,\n    val buttonColor: String,',
            'extend menu revision tax snapshot model',
        )
        text = replace_required(
            text,
            '            val assignments = assignmentMap(db)\n            return db.transaction {',
            '            val assignments = assignmentMap(db)\n            val taxRules = listTaxRules().associateBy { it.key }\n            return db.transaction {',
            'load tax rules while scheduling revision',
        )
        text = replace_required(
            text,
            '                    insertOrThrow(\n                        "menu_revision_products",',
            '                    val revisionTaxKey = assignments[meta.productId] ?: product.taxKey\n                    val revisionTax = taxRules[revisionTaxKey]?.let(TaxSnapshot::from) ?: TaxSnapshot.from(product)\n                    insertOrThrow(\n                        "menu_revision_products",',
            'freeze revision tax snapshot',
        )
        text = replace_required(
            text,
            '                            put("tax_key", assignments[meta.productId] ?: product.taxKey)\n                            put("button_color", meta.buttonColor)',
            '                            put("tax_key", revisionTax.key)\n                            put("tax_label", revisionTax.label)\n                            put("tax_rate_percent", revisionTax.ratePercent)\n                            put("tax_included", if (revisionTax.taxIncluded) 1 else 0)\n                            put("taxable", if (revisionTax.taxable) 1 else 0)\n                            put("reduced", if (revisionTax.reduced) 1 else 0)\n                            put("tax_symbol", revisionTax.symbol)\n                            put("button_color", meta.buttonColor)',
            'persist revision tax snapshot',
        )
        text = replace_required(
            text,
            '                    rules[snapshot.taxKey]?.takeIf { it.isEffective(date) }?.applyTo(positioned) ?: positioned',
            '                    TaxSnapshot(\n                        snapshot.taxKey, snapshot.taxLabel, snapshot.taxRatePercent, snapshot.taxIncluded,\n                        snapshot.taxable, snapshot.reduced, snapshot.taxSymbol,\n                    ).applyTo(positioned)',
            'apply frozen revision tax snapshot',
        )
        text = replace_required(
            text,
            '                "tax_key", "button_color", "page_no", "slot_no", "display_order",',
            '                "tax_key", "tax_label", "tax_rate_percent", "tax_included", "taxable", "reduced", "tax_symbol",\n                "button_color", "page_no", "slot_no", "display_order",',
            'query revision snapshot columns',
        )
        text = replace_required(
            text,
            '                    taxKey = cursor.getString(5),\n                    buttonColor = cursor.getString(6),\n                    pageNo = cursor.getInt(7),\n                    slotNo = cursor.getInt(8),\n                    displayOrder = cursor.getInt(9),',
            '                    taxKey = cursor.getString(5),\n                    taxLabel = cursor.getString(6),\n                    taxRatePercent = cursor.getInt(7),\n                    taxIncluded = cursor.getInt(8) != 0,\n                    taxable = cursor.getInt(9) != 0,\n                    reduced = cursor.getInt(10) != 0,\n                    taxSymbol = cursor.getString(11),\n                    buttonColor = cursor.getString(12),\n                    pageNo = cursor.getInt(13),\n                    slotNo = cursor.getInt(14),\n                    displayOrder = cursor.getInt(15),',
            'read revision snapshot columns',
        )
        text = replace_required(
            text,
            '                    tax_key TEXT NOT NULL,\n                    button_color TEXT NOT NULL,',
            '                    tax_key TEXT NOT NULL,\n                    tax_label TEXT NOT NULL DEFAULT \'\',\n                    tax_rate_percent INTEGER NOT NULL DEFAULT 0,\n                    tax_included INTEGER NOT NULL DEFAULT 0,\n                    taxable INTEGER NOT NULL DEFAULT 0,\n                    reduced INTEGER NOT NULL DEFAULT 0,\n                    tax_symbol TEXT NOT NULL DEFAULT \'\',\n                    button_color TEXT NOT NULL,',
            'menu revision tax schema',
        )
        text = replace_required(
            text,
            '            LineTaxSnapshotStore.ensureSchema(db)',
            '''            SchemaMigration.ensureColumn(db, "menu_revision_products", "tax_label", "TEXT NOT NULL DEFAULT ''")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "tax_rate_percent", "INTEGER NOT NULL DEFAULT 0")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "tax_included", "INTEGER NOT NULL DEFAULT 0")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "taxable", "INTEGER NOT NULL DEFAULT 0")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "reduced", "INTEGER NOT NULL DEFAULT 0")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "tax_symbol", "TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                UPDATE menu_revision_products
                SET tax_label = CASE legacy_tax_category
                        WHEN 'INCLUDED_10' THEN '10%内税' WHEN 'EXCLUDED_10' THEN '10%外税'
                        WHEN 'INCLUDED_8' THEN '8%内税' WHEN 'EXCLUDED_8' THEN '8%外税' ELSE '非課税' END,
                    tax_rate_percent = CASE legacy_tax_category
                        WHEN 'INCLUDED_10' THEN 10 WHEN 'EXCLUDED_10' THEN 10
                        WHEN 'INCLUDED_8' THEN 8 WHEN 'EXCLUDED_8' THEN 8 ELSE 0 END,
                    tax_included = CASE WHEN legacy_tax_category IN ('INCLUDED_10','INCLUDED_8') THEN 1 ELSE 0 END,
                    taxable = CASE WHEN legacy_tax_category = 'NON_TAXABLE' THEN 0 ELSE 1 END,
                    reduced = CASE WHEN legacy_tax_category IN ('INCLUDED_8','EXCLUDED_8') THEN 1 ELSE 0 END,
                    tax_symbol = CASE legacy_tax_category
                        WHEN 'INCLUDED_10' THEN '内' WHEN 'EXCLUDED_10' THEN '外'
                        WHEN 'INCLUDED_8' THEN '内※' WHEN 'EXCLUDED_8' THEN '外※' ELSE '非' END
                WHERE tax_label = ''
                """.trimIndent(),
            )
            LineTaxSnapshotStore.ensureSchema(db)''',
            'migrate revision tax snapshots',
        )
        return text
    patch(path, transform)

    editor = PKG / "MenuRevisionEditorActivity.kt"
    def editor_transform(text: str) -> str:
        text = replace_required(
            text,
            '        val taxRuleExists = db.rawQuery(\n            "SELECT COUNT(*) FROM dynamic_tax_rules WHERE tax_key = ? AND enabled = 1",\n            arrayOf(product.taxKey),\n        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }\n        require(taxRuleExists) { "有効な税区分を選択してください" }',
            '        val selectedTaxRule = DynamicCatalogStore(applicationContext).use { store ->\n            store.listTaxRules().firstOrNull { it.key == product.taxKey && it.enabled }\n        } ?: error("有効な税区分を選択してください")\n        val selectedTax = TaxSnapshot.from(selectedTaxRule)',
            'load selected revision tax rule',
        )
        text = replace_required(
            text,
            '                    put("tax_key", product.taxKey)\n                    put("button_color", color)',
            '                    put("tax_key", selectedTax.key)\n                    put("tax_label", selectedTax.label)\n                    put("tax_rate_percent", selectedTax.ratePercent)\n                    put("tax_included", if (selectedTax.taxIncluded) 1 else 0)\n                    put("taxable", if (selectedTax.taxable) 1 else 0)\n                    put("reduced", if (selectedTax.reduced) 1 else 0)\n                    put("tax_symbol", selectedTax.symbol)\n                    put("button_color", color)',
            'update edited revision tax snapshot',
        )
        return text
    patch(editor, editor_transform)


def patch_operator_session_and_main() -> None:
    session = PKG / "OperatorSession.kt"
    def session_transform(text: str) -> str:
        text = replace_required(
            text,
            '    val permissions: Set<RegisterPermission>,\n) {',
            '    val permissions: Set<RegisterPermission>,\n    val revision: Long,\n) {',
            'operator revision field',
        )
        text = replace_required(
            text,
            'arrayOf("id", "operator_code", "operator_name", "role", "pin_salt", "pin_hash")',
            'arrayOf("id", "operator_code", "operator_name", "role", "pin_salt", "pin_hash", "updated_at")',
            'authenticate updated_at query',
        )
        text = replace_required(
            text,
            '                hash = cursor.getString(5),\n            )',
            '                hash = cursor.getString(5),\n                revision = cursor.getLong(6),\n            )',
            'authenticate revision read',
        )
        text = replace_required(
            text,
            'arrayOf("id", "operator_code", "operator_name", "role")',
            'arrayOf("id", "operator_code", "operator_name", "role", "updated_at")',
            'restore updated_at query',
        )
        text = replace_required(
            text,
            '            role = OperatorRole.valueOf(cursor.getString(3)),\n            permissions = loadPermissions(cursor.getLong(0)),',
            '            role = OperatorRole.valueOf(cursor.getString(3)),\n            permissions = loadPermissions(cursor.getLong(0)),\n            revision = cursor.getLong(4),',
            'restore revision read',
        )
        text = replace_required(
            text,
            '        val hash: String,\n    ) {',
            '        val hash: String,\n        val revision: Long,\n    ) {',
            'pin row revision',
        )
        text = replace_required(
            text,
            '            permissions = permissions,\n        )',
            '            permissions = permissions,\n            revision = revision,\n        )',
            'authenticated revision',
        )
        text = regex_required(
            text,
            r'    fun current\(context: Context\): AuthenticatedOperator\? \{.*?\n    fun login',
            '''    fun current(context: Context): AuthenticatedOperator? {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val operatorId = prefs.getLong(KEY_OPERATOR_ID, -1L)
        val lastActivity = prefs.getLong(KEY_LAST_ACTIVITY, 0L)
        if (operatorId <= 0L || isExpired(lastActivity, System.currentTimeMillis())) {
            clear(appContext)
            return null
        }
        val stored = OperatorAuthenticationStore(appContext).use { it.loadEnabledOperator(operatorId) }
        if (stored == null || !OperatorSessionRevisionPolicy.mayContinue(true, stored.permissions)) {
            clear(appContext)
            return null
        }
        val current = cached
        if (current == null || current.id != operatorId || OperatorSessionRevisionPolicy.shouldReload(current.revision, stored.revision)) {
            cached = stored
            return stored
        }
        return current
    }

    internal fun isExpired(lastActivity: Long, now: Long): Boolean =
        lastActivity <= 0L || now - lastActivity > SESSION_TIMEOUT_MILLIS

    fun login''',
            'refresh session from operator revision',
        )
        text = regex_required(
            text,
            r'    fun touch\(context: Context\) \{.*?\n    fun logout',
            '''    fun touch(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_OPERATOR_ID, -1L) <= 0L) return
        prefs.edit().putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis()).apply()
    }

    fun logout''',
            'lightweight session touch',
        )
        text = replace_required(
            text,
            '    fun lastKnownName(): String? = cached?.name\n\n    private fun clear',
            '    fun lastKnownName(): String? = cached?.name\n\n    fun invalidate(context: Context) = clear(context.applicationContext)\n\n    private fun clear',
            'public session invalidation',
        )
        return text
    patch(session, session_transform)

    main = PKG / "MainActivity.kt"
    def main_transform(text: str) -> str:
        text = replace_required(
            text,
            'import androidx.compose.ui.graphics.Color\n',
            'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.input.pointer.pointerInput\n',
            'pointer input import',
        )
        text = replace_required(
            text,
            '            kotlinx.coroutines.delay(15_000L)\n            catalogEpoch++',
            '''            kotlinx.coroutines.delay(5_000L)
            val refreshed = OperatorSessionRegistry.current(context.applicationContext)
            when {
                currentOperator != null && refreshed == null -> {
                    currentOperator = null
                    operatorName = "未選択"
                    loginMessage = "セッションが失効したか、担当者が停止・権限変更されました"
                    screen = AppScreen.LOGIN
                }
                refreshed != null -> {
                    currentOperator = refreshed
                    operatorName = refreshed.name
                }
            }
            catalogEpoch++''',
            'periodic session validation',
        )
        text = replace_required(
            text,
            '    Surface(modifier = Modifier.fillMaxSize(), color = Background) {',
            '''    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    OperatorSessionRegistry.touch(context.applicationContext)
                }
            }
        },
        color = Background,
    ) {''',
            'touch activity tracking',
        )
        return text
    patch(main, main_transform)


def patch_outbox_and_payloads() -> None:
    path = PKG / "BusinessSyncFoundation.kt"
    def transform(text: str) -> str:
        text = text.replace('import java.time.LocalDate\n', 'import java.time.LocalDate\nimport java.util.UUID\n')
        text = replace_required(
            text,
            '                last_error TEXT,\n                created_at INTEGER NOT NULL,',
            '                last_error TEXT,\n                processing_started_at INTEGER,\n                lease_until INTEGER,\n                worker_token TEXT,\n                created_at INTEGER NOT NULL,',
            'outbox lease schema',
        )
        text = replace_required(
            text,
            '        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_outbox_status ON sync_outbox(status, next_attempt_at, created_at)")',
            '''        SchemaMigration.ensureColumn(db, "sync_outbox", "processing_started_at", "INTEGER")
        SchemaMigration.ensureColumn(db, "sync_outbox", "lease_until", "INTEGER")
        SchemaMigration.ensureColumn(db, "sync_outbox", "worker_token", "TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_outbox_status ON sync_outbox(status, next_attempt_at, created_at)")''',
            'migrate outbox lease columns',
        )
        text = replace_required(
            text,
            '        createdAt: Long,\n    ) {\n        ensureCore(db)\n        val businessDate = BusinessDateResolver.current(db).toString()',
            '        createdAt: Long,\n        businessDate: String = BusinessDateResolver.current(db).toString(),\n        folderName: String = "つぐレジ",\n    ) {\n        ensureCore(db)',
            'recordSale explicit business date and folder',
        )
        text = replace_required(
            text,
            '            createdAt = createdAt,\n        )',
            '            createdAt = createdAt,\n            folderName = folderName,\n        )',
            'pass folder to outbox insert',
        )
        text = replace_required(
            text,
            '        payloadJson: String,\n        createdAt: Long,\n    ) {',
            '        payloadJson: String,\n        createdAt: Long,\n        folderName: String = "つぐレジ",\n    ) {',
            'insert outbox folder argument',
        )
        text = replace_required(
            text,
            '                put("object_key", OutboxObjectKey.build("REGISTER", businessDate, eventType, aggregateId))',
            '                put("object_key", OutboxObjectKey.build(folderName, businessDate, eventType, aggregateId))',
            'dynamic outbox object key',
        )
        text = replace_required(
            text,
            '    init {\n        JournalOutboxSchema.ensureCore(db)\n    }',
            '''    init {
        JournalOutboxSchema.ensureCore(db)
        recoverStaleProcessing()
        JournalOutboxSchema.rewriteUnstagedObjectKeys(db, DriveSyncSettingsStore.load(applicationContext).folderName)
    }''',
            'outbox startup recovery',
        )
        text = regex_required(
            text,
            r'    fun stagePending\(limit: Int = 100\): Int \{.*?\n    fun requeueStaged',
            '''    fun stagePending(limit: Int = 100): Int {
        val folder = stagingRoot()
        folder.mkdirs()
        val now = System.currentTimeMillis()
        recoverStaleProcessing(now)
        val token = UUID.randomUUID().toString()
        val candidates = db.runInTransactionWithResult {
            val selected = rawQuery(
                """
                SELECT o.id, o.event_id, j.business_date, j.event_type, j.aggregate_id,
                       o.object_key, o.status, o.attempt_count, o.last_error, o.created_at, o.updated_at
                FROM sync_outbox o
                INNER JOIN sales_journal j ON j.event_id = o.event_id
                WHERE o.status IN ('PENDING','RETRY') AND o.next_attempt_at <= ?
                ORDER BY o.created_at ASC, o.id ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(now.toString(), limit.coerceIn(1, 500).toString()),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toOutboxRecord()) } }
            selected.mapNotNull { record ->
                val changed = update(
                    "sync_outbox",
                    ContentValues().apply {
                        put("status", SyncOutboxStatus.PROCESSING.name)
                        put("attempt_count", record.attemptCount + 1)
                        put("processing_started_at", now)
                        put("lease_until", now + OutboxLeasePolicy.LEASE_MILLIS)
                        put("worker_token", token)
                        put("updated_at", now)
                    },
                    "id = ? AND status IN ('PENDING','RETRY')",
                    arrayOf(record.id.toString()),
                )
                if (changed == 1) record.copy(status = SyncOutboxStatus.PROCESSING, attemptCount = record.attemptCount + 1) else null
            }
        }
        var completed = 0
        candidates.forEach { record ->
            runCatching {
                val payload = OutboxPayloadAssembler.build(db, record)
                val target = File(folder, record.objectKey)
                target.parentFile?.mkdirs()
                target.writeText(payload, Charsets.UTF_8)
                markStaged(record.id, token)
                completed++
            }.onFailure { error ->
                markRetry(record, token, error)
            }
        }
        return completed
    }

    fun recoverStaleProcessing(now: Long = System.currentTimeMillis()): Int = db.update(
        "sync_outbox",
        ContentValues().apply {
            put("status", SyncOutboxStatus.RETRY.name)
            put("next_attempt_at", 0)
            put("last_error", "前回処理が中断されたため再試行します")
            putNull("processing_started_at")
            putNull("lease_until")
            putNull("worker_token")
            put("updated_at", now)
        },
        "status = ? AND (lease_until IS NULL OR lease_until <= ?)",
        arrayOf(SyncOutboxStatus.PROCESSING.name, now.toString()),
    )

    fun requeueStaged''',
            'transactional outbox claim and recovery',
        )
        text = regex_required(
            text,
            r'    private fun markProcessing\(record: JournalOutboxRecord\) \{.*?\n    private fun retryDelay',
            '''    private fun markStaged(id: Long, token: String) {
        db.update(
            "sync_outbox",
            ContentValues().apply {
                put("status", SyncOutboxStatus.STAGED.name)
                putNull("last_error")
                putNull("processing_started_at")
                putNull("lease_until")
                putNull("worker_token")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND status = ? AND worker_token = ?",
            arrayOf(id.toString(), SyncOutboxStatus.PROCESSING.name, token),
        )
    }

    private fun markRetry(record: JournalOutboxRecord, token: String, error: Throwable) {
        val attempts = record.attemptCount
        val permanent = attempts >= 5
        db.update(
            "sync_outbox",
            ContentValues().apply {
                put("status", if (permanent) SyncOutboxStatus.FAILED.name else SyncOutboxStatus.RETRY.name)
                put("next_attempt_at", if (permanent) Long.MAX_VALUE else System.currentTimeMillis() + retryDelay(attempts))
                put("last_error", (error.message ?: error.javaClass.simpleName).take(500))
                putNull("processing_started_at")
                putNull("lease_until")
                putNull("worker_token")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND status = ? AND worker_token = ?",
            arrayOf(record.id.toString(), SyncOutboxStatus.PROCESSING.name, token),
        )
    }

    private fun retryDelay''',
            'lease-aware outbox completion',
        )
        text = regex_required(
            text,
            r'    private fun salePayload\(db: SQLiteDatabase, record: JournalOutboxRecord\): String \{.*?\n    private fun reversalPayload',
            r'''    private fun salePayload(db: SQLiteDatabase, record: JournalOutboxRecord): String {
        val saleId = record.aggregateId.toLong()
        val header = db.rawQuery(
            "SELECT operator_name, payment_method, net_amount, tax_amount, total_amount, deposit_amount, change_amount, created_at FROM sales WHERE id = ?",
            arrayOf(saleId.toString()),
        ).use { cursor ->
            require(cursor.moveToFirst()) { "売上が見つかりません" }
            listOf(
                cursor.getString(0), cursor.getString(1), cursor.getLong(2).toString(), cursor.getLong(3).toString(),
                cursor.getLong(4).toString(), cursor.getLong(5).toString(), cursor.getLong(6).toString(), cursor.getLong(7).toString(),
            )
        }
        val payloadLines = db.rawQuery(
            """
            SELECT si.product_id, si.product_name, si.unit_price, si.tax_category, si.quantity, si.discount_amount, si.note,
                   COALESCE(lts.tax_key, si.tax_category),
                   COALESCE(lts.tax_label, si.tax_category),
                   COALESCE(lts.rate_percent, CASE si.tax_category WHEN 'INCLUDED_10' THEN 10 WHEN 'EXCLUDED_10' THEN 10 WHEN 'INCLUDED_8' THEN 8 WHEN 'EXCLUDED_8' THEN 8 ELSE 0 END),
                   COALESCE(lts.tax_included, CASE WHEN si.tax_category IN ('INCLUDED_10','INCLUDED_8') THEN 1 ELSE 0 END),
                   COALESCE(lts.taxable, CASE WHEN si.tax_category = 'NON_TAXABLE' THEN 0 ELSE 1 END),
                   COALESCE(lts.reduced, CASE WHEN si.tax_category IN ('INCLUDED_8','EXCLUDED_8') THEN 1 ELSE 0 END),
                   COALESCE(lts.tax_symbol, CASE si.tax_category WHEN 'INCLUDED_10' THEN '内' WHEN 'EXCLUDED_10' THEN '外' WHEN 'INCLUDED_8' THEN '内※' WHEN 'EXCLUDED_8' THEN '外※' ELSE '非' END)
            FROM sale_items si
            LEFT JOIN line_tax_snapshots lts
              ON lts.scope='SALE' AND lts.owner_id=si.sale_id
             AND lts.line_no=(SELECT COUNT(*) FROM sale_items si2 WHERE si2.sale_id=si.sale_id AND si2.id<=si.id)
            WHERE si.sale_id=? ORDER BY si.id
            """.trimIndent(),
            arrayOf(saleId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val legacy = TaxCategory.valueOf(cursor.getString(3))
                    val label = cursor.getString(8).takeUnless { it == legacy.name } ?: legacy.displayName
                    add(
                        PayloadTaxLine(
                            productId = cursor.getString(0), name = cursor.getString(1), unitPrice = cursor.getLong(2),
                            legacyCategory = legacy, quantity = cursor.getInt(4), discount = cursor.getLong(5), note = cursor.getString(6),
                            snapshot = TaxSnapshot(
                                key = cursor.getString(7), label = label, ratePercent = cursor.getInt(9),
                                taxIncluded = cursor.getInt(10) != 0, taxable = cursor.getInt(11) != 0,
                                reduced = cursor.getInt(12) != 0, symbol = cursor.getString(13),
                            ),
                        ),
                    )
                }
            }
        }
        val items = payloadLines.joinToString(",") { line ->
            val tax = line.snapshot
            "{\"productId\":\"${escape(line.productId)}\",\"name\":\"${escape(line.name)}\",\"unitPrice\":${line.unitPrice},\"quantity\":${line.quantity},\"discount\":${line.discount},\"note\":\"${escape(line.note)}\",\"taxKey\":\"${escape(tax.key)}\",\"taxLabel\":\"${escape(tax.label)}\",\"taxRatePercent\":${tax.ratePercent},\"taxIncluded\":${tax.taxIncluded},\"taxable\":${tax.taxable},\"reduced\":${tax.reduced},\"taxSymbol\":\"${escape(tax.symbol)}\"}"
        }
        val taxTotals = PayloadTaxAggregation.calculate(payloadLines).buckets.joinToString(",") { bucket ->
            val keys = bucket.sourceTaxKeys.joinToString(",") { "\"${escape(it)}\"" }
            "{\"ratePercent\":${bucket.ratePercent},\"taxable\":${bucket.taxable},\"netAmount\":${bucket.netAmount},\"taxAmount\":${bucket.taxAmount},\"grossAmount\":${bucket.grossAmount},\"taxKeys\":[$keys]}"
        }
        val payments = db.rawQuery(
            "SELECT payment_method, applied_amount, received_amount FROM sale_payments WHERE sale_id = ? ORDER BY sequence_no",
            arrayOf(saleId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add("{\"method\":\"${escape(cursor.getString(0))}\",\"applied\":${cursor.getLong(1)},\"received\":${cursor.getLong(2)}}")
            }.joinToString(",")
        }
        return """{"schema":"register.sale.v2","eventId":"${escape(record.eventId)}","businessDate":"${escape(record.businessDate)}","saleId":$saleId,"operator":"${escape(header[0])}","paymentLabel":"${escape(header[1])}","netAmount":${header[2]},"taxAmount":${header[3]},"totalAmount":${header[4]},"depositAmount":${header[5]},"changeAmount":${header[6]},"createdAt":${header[7]},"items":[$items],"taxTotals":[$taxTotals],"payments":[$payments]}"""
    }

    private fun reversalPayload''',
            'sale JSON full tax snapshots',
        )
        text = replace_required(
            text,
            '"SELECT product_id, product_name, enabled, unit_price, tax_key, button_color, page_no, slot_no FROM menu_revision_products WHERE revision_id = ? ORDER BY display_order, product_id"',
            '"SELECT product_id, product_name, enabled, unit_price, tax_key, tax_label, tax_rate_percent, tax_included, taxable, reduced, tax_symbol, button_color, page_no, slot_no FROM menu_revision_products WHERE revision_id = ? ORDER BY display_order, product_id"',
            'menu revision JSON snapshot query',
        )
        text = replace_required(
            text,
            'add("{\\"productId\\":\\"${escape(cursor.getString(0))}\\",\\"name\\":\\"${escape(cursor.getString(1))}\\",\\"enabled\\":${cursor.getInt(2) != 0},\\"unitPrice\\":${cursor.getLong(3)},\\"taxKey\\":\\"${escape(cursor.getString(4))}\\",\\"buttonColor\\":\\"${escape(cursor.getString(5))}\\",\\"pageNo\\":${cursor.getInt(6)},\\"slotNo\\":${cursor.getInt(7)}}")',
            'add("{\\"productId\\":\\"${escape(cursor.getString(0))}\\",\\"name\\":\\"${escape(cursor.getString(1))}\\",\\"enabled\\":${cursor.getInt(2) != 0},\\"unitPrice\\":${cursor.getLong(3)},\\"taxKey\\":\\"${escape(cursor.getString(4))}\\",\\"taxLabel\\":\\"${escape(cursor.getString(5))}\\",\\"taxRatePercent\\":${cursor.getInt(6)},\\"taxIncluded\\":${cursor.getInt(7) != 0},\\"taxable\\":${cursor.getInt(8) != 0},\\"reduced\\":${cursor.getInt(9) != 0},\\"taxSymbol\\":\\"${escape(cursor.getString(10))}\\",\\"buttonColor\\":\\"${escape(cursor.getString(11))}\\",\\"pageNo\\":${cursor.getInt(12)},\\"slotNo\\":${cursor.getInt(13)}}")',
            'menu revision JSON snapshot fields',
        )
        text = replace_required(
            text,
            '            folderName = preferences.getString(KEY_FOLDER, "REGISTER") ?: "REGISTER",',
            '            folderName = preferences.getString(KEY_FOLDER, "つぐレジ") ?: "つぐレジ",',
            'formal default sync folder',
        )
        text = replace_required(
            text,
            '            .putString(KEY_FOLDER, folder)\n            .apply()',
            '''            .putString(KEY_FOLDER, folder)
            .apply()
        RegisterDatabase(context.applicationContext).use { database ->
            JournalOutboxSchema.ensureCore(database.writableDatabase)
            JournalOutboxSchema.rewriteUnstagedObjectKeys(database.writableDatabase, folder)
        }''',
            'apply folder setting to existing unstaged outbox',
        )
        insert_point = '    private fun insertJournalAndOutbox('
        helper = '''    /**
     * フォルダー変更時、未ステージのOutboxだけを新しい保存先へ付け替える。
     * STAGED/SENTは既に生成・送信済みのイミュータブル成果物として旧キーを保持する。
     */
    fun rewriteUnstagedObjectKeys(db: SQLiteDatabase, folderName: String): Int {
        ensureCore(db)
        val rows = db.rawQuery(
            """
            SELECT o.id, j.business_date, j.event_type, j.aggregate_id
            FROM sync_outbox o INNER JOIN sales_journal j ON j.event_id=o.event_id
            WHERE o.status IN ('PENDING','RETRY','FAILED')
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(arrayOf(cursor.getLong(0).toString(), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
            }
        }
        var changed = 0
        rows.forEach { row ->
            changed += db.update(
                "sync_outbox",
                ContentValues().apply { put("object_key", OutboxObjectKey.build(folderName, row[1], row[2], row[3])) },
                "id=?",
                arrayOf(row[0]),
            )
        }
        return changed
    }

'''
        if insert_point not in text:
            raise RuntimeError('v0.11.1 stabilization failed: outbox rewrite insertion')
        text = text.replace(insert_point, helper + insert_point, 1)
        return text
    patch(path, transform)


def add_tests() -> None:
    test = APP / "src/test/java/jp/co/tenposinfo/register/V0111StabilizationPolicyTest.kt"
    test.parent.mkdir(parents=True, exist_ok=True)
    test.write_text(
        r'''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V0111StabilizationPolicyTest {
    @Test
    fun saleAfterMidnightBelongsToTheOpenBusinessSession() {
        val sessions = listOf(
            BusinessSessionWindow(30, "2026-07-30", 1_000L, 10_000L),
            BusinessSessionWindow(31, "2026-07-31", 20_000L, null),
        )
        val resolved = BusinessSessionAttributionPolicy.resolve(8_000L, sessions)
        assertEquals(30L, resolved?.id)
        assertEquals("2026-07-30", resolved?.businessDate)
    }

    @Test
    fun twelvePercentIncludedReturnKeepsOriginalSnapshot() {
        val line = ReturnLineRecord(
            saleItemId = 1,
            productId = "I12",
            productName = "12%内税商品",
            unitPrice = 1120,
            taxCategory = TaxCategory.INCLUDED_10,
            taxKey = "INCLUDED_12",
            taxLabel = "12%内税",
            taxRatePercent = 12,
            taxIncluded = true,
            taxable = true,
            reduced = false,
            taxSymbol = "内12",
            originalQuantity = 2,
            originalDiscount = 0,
            note = "",
            returnedQuantity = 0,
            refundedDiscount = 0,
        )
        val item = line.toReturnItem(1)
        assertEquals(12, item.product.taxRatePercent)
        assertTrue(item.product.taxIncluded)
        assertEquals("INCLUDED_12", item.product.taxKey)
        assertEquals(120L, TaxEngine.calculate(listOf(item)).taxAmount)
    }

    @Test
    fun twelvePercentExcludedPartialReturnKeepsOriginalSnapshot() {
        val line = ReturnLineRecord(
            saleItemId = 2,
            productId = "E12",
            productName = "12%外税商品",
            unitPrice = 1000,
            taxCategory = TaxCategory.EXCLUDED_10,
            taxKey = "EXCLUDED_12",
            taxLabel = "12%外税",
            taxRatePercent = 12,
            taxIncluded = false,
            taxable = true,
            reduced = false,
            taxSymbol = "外12",
            originalQuantity = 3,
            originalDiscount = 0,
            note = "",
            returnedQuantity = 0,
            refundedDiscount = 0,
        )
        val item = line.toReturnItem(2)
        assertFalse(item.product.taxIncluded)
        assertEquals(240L, TaxEngine.calculate(listOf(item)).taxAmount)
        assertEquals(2240L, TaxEngine.calculate(listOf(item)).grossAmount)
    }

    @Test
    fun reservedTaxSnapshotDoesNotDependOnLaterMasterChanges() {
        val original = TaxSnapshot("TAX12", "12%内税", 12, true, true, false, "内12")
        val changedMaster = DynamicTaxRule("TAX12", "15%内税", 15, DynamicTaxMode.INCLUDED, false, true, "内15", "", "")
        val base = Product("P", "商品", 1120, TaxCategory.INCLUDED_10, 1)
        assertEquals(12, original.applyTo(base).taxRatePercent)
        assertEquals(15, TaxSnapshot.from(changedMaster).applyTo(base).taxRatePercent)
    }

    @Test
    fun staleProcessingLeaseIsRecovered() {
        assertTrue(OutboxLeasePolicy.isStale(null, 1000L))
        assertTrue(OutboxLeasePolicy.isStale(999L, 1000L))
        assertFalse(OutboxLeasePolicy.isStale(1001L, 1000L))
    }

    @Test
    fun folderSettingIsUsedInNewObjectKey() {
        val key = OutboxObjectKey.build("店舗A 同期", "2026-07-30", "SALE", "99")
        assertTrue(key.startsWith("店舗A_同期/2026-07-30/"))
    }

    @Test
    fun salePayloadTaxTotalsSupportArbitraryRate() {
        val lines = listOf(
            PayloadTaxLine(
                "P12", "12%外税", 1000, 2, 0, "", TaxCategory.EXCLUDED_10,
                TaxSnapshot("E12", "12%外税", 12, false, true, false, "外12"),
            ),
        )
        val summary = PayloadTaxAggregation.calculate(lines)
        assertEquals(2000L, summary.netAmount)
        assertEquals(240L, summary.taxAmount)
        assertEquals(2240L, summary.grossAmount)
    }

    @Test
    fun operatorPermissionRevisionForcesReloadAndSalesRemovalStopsSession() {
        assertTrue(OperatorSessionRevisionPolicy.shouldReload(10L, 11L))
        assertFalse(OperatorSessionRevisionPolicy.mayContinue(true, setOf(RegisterPermission.VIEW_SALES)))
        assertTrue(OperatorSessionRevisionPolicy.mayContinue(true, setOf(RegisterPermission.SALES)))
    }
}
''',
        encoding="utf-8",
    )


def cleanup_legacy_generation() -> None:
    for path in [ROOT / "tools/generate_v010.py", ROOT / "tools/v08"]:
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()
    transient_workflow = ROOT / ".github/workflows/stabilize-v0111.yml"
    if transient_workflow.exists():
        transient_workflow.unlink()
    if Path(__file__).exists():
        Path(__file__).unlink()


def main() -> None:
    integrate_generated_sources()
    write_foundation()
    patch_gradle_and_branding()
    patch_register_database()
    patch_advanced_operations()
    patch_dynamic_catalog()
    patch_operator_session_and_main()
    patch_outbox_and_payloads()
    add_tests()
    cleanup_legacy_generation()
    print("v0.11.1 stabilization transformation completed")


if __name__ == "__main__":
    main()
