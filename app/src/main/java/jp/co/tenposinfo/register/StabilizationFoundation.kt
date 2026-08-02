package jp.co.tenposinfo.register

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
    val openingCash: Long = 0L,
)

object BusinessSessionAttributionPolicy {
    fun resolve(createdAt: Long, sessions: List<BusinessSessionWindow>): BusinessSessionWindow? =
        sessions
            .asSequence()
            .filter { createdAt >= it.openedAt && (it.closedAt == null || createdAt <= it.closedAt) }
            .maxByOrNull { it.openedAt }
}

/**
 * 営業開始前の管理画面表示専用フォールバック。
 * 実在する営業セッションIDと衝突しない0を使用し、売上・入出金集計を0件として表示する。
 */
object BusinessSessionDisplayFallback {
    fun forDate(date: LocalDate, today: LocalDate = LocalDate.now()): BusinessSessionWindow? =
        if (date == today) {
            BusinessSessionWindow(
                id = 0L,
                businessDate = date.toString(),
                openedAt = 0L,
                closedAt = null,
                openingCash = 0L,
            )
        } else {
            null
        }
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
        BusinessSessionMultiplicityMigration.ensure(db)
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
                    SET business_session_id = COALESCE(
                        (
                            SELECT bs.id FROM business_sessions bs
                            WHERE bs.opened_at <= settlement_reports.created_at
                              AND (bs.closed_at IS NULL OR settlement_reports.created_at <= bs.closed_at)
                            ORDER BY bs.opened_at DESC LIMIT 1
                        ),
                        (
                            SELECT bs.id FROM business_sessions bs
                            WHERE bs.business_date = settlement_reports.business_date
                            ORDER BY bs.opened_at DESC LIMIT 1
                        )
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
            WHERE status = 'OPEN'
            ORDER BY opened_at DESC LIMIT 1
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) BusinessSessionLink(cursor.getLong(0), cursor.getString(1))
            else BusinessSessionLink(null, calendarDate.toString())
        }
    }

    fun currentOpen(db: SQLiteDatabase): BusinessSessionLink? {
        ensure(db)
        if (!SchemaMigration.tableExists(db, "business_sessions")) return null
        return db.rawQuery(
            "SELECT id, business_date FROM business_sessions WHERE status = 'OPEN' ORDER BY opened_at DESC LIMIT 1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) BusinessSessionLink(cursor.getLong(0), cursor.getString(1)) else null
        }
    }

    fun sessionForDate(db: SQLiteDatabase, date: LocalDate): BusinessSessionWindow? {
        ensure(db)
        if (!SchemaMigration.tableExists(db, "business_sessions")) return BusinessSessionDisplayFallback.forDate(date)
        return db.rawQuery(
            "SELECT id, business_date, opened_at, closed_at, opening_cash FROM business_sessions WHERE business_date=? ORDER BY opened_at DESC LIMIT 1",
            arrayOf(date.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                BusinessSessionDisplayFallback.forDate(date)
            } else {
                BusinessSessionWindow(
                    id = cursor.getLong(0),
                    businessDate = cursor.getString(1),
                    openedAt = cursor.getLong(2),
                    closedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
                    openingCash = cursor.getLong(4),
                )
            }
        }
    }

    fun sessionById(db: SQLiteDatabase, sessionId: Long): BusinessSessionWindow? {
        ensure(db)
        if (sessionId <= 0L || !SchemaMigration.tableExists(db, "business_sessions")) return null
        return db.rawQuery(
            "SELECT id, business_date, opened_at, closed_at, opening_cash FROM business_sessions WHERE id=? LIMIT 1",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else BusinessSessionWindow(
                id = cursor.getLong(0),
                businessDate = cursor.getString(1),
                openedAt = cursor.getLong(2),
                closedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
                openingCash = cursor.getLong(4),
            )
        }
    }

    private fun backfill(db: SQLiteDatabase, table: String) {
        db.execSQL(
            """
            UPDATE $table
            SET business_session_id = COALESCE(
                    (
                        SELECT bs.id FROM business_sessions bs
                        WHERE bs.opened_at <= $table.created_at
                          AND (bs.closed_at IS NULL OR $table.created_at <= bs.closed_at)
                        ORDER BY bs.opened_at DESC LIMIT 1
                    ),
                    (
                        SELECT bs.id FROM business_sessions bs
                        WHERE $table.business_date IS NOT NULL
                          AND bs.business_date = $table.business_date
                        ORDER BY bs.opened_at DESC LIMIT 1
                    )
                ),
                business_date = COALESCE(
                    $table.business_date,
                    (
                        SELECT bs.business_date FROM business_sessions bs
                        WHERE bs.opened_at <= $table.created_at
                          AND (bs.closed_at IS NULL OR $table.created_at <= bs.closed_at)
                        ORDER BY bs.opened_at DESC LIMIT 1
                    )
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
