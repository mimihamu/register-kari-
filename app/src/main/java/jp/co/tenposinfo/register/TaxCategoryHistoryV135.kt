package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate

/** TAX-001 tax rounding master value. Existing tax engine behavior maps to FLOOR. */
enum class TaxRoundingV135 {
    FLOOR,
    HALF_UP,
    CEILING,
}

/** Immutable business-date revision of a tax category. */
data class TaxCategoryRevisionV135(
    val taxKey: String,
    val label: String,
    val ratePercent: Int,
    val mode: DynamicTaxMode,
    val rounding: TaxRoundingV135,
    val reduced: Boolean,
    val enabled: Boolean,
    val symbol: String,
    val effectiveFromBusinessDate: LocalDate,
    val effectiveToBusinessDate: LocalDate? = null,
) {
    fun appliesTo(businessDate: LocalDate): Boolean =
        !businessDate.isBefore(effectiveFromBusinessDate) &&
            (effectiveToBusinessDate == null || !businessDate.isAfter(effectiveToBusinessDate))
}

/** Pure TAX-001 validation/resolution rules; intentionally independent from the wall clock. */
object TaxCategoryHistoryPolicyV135 {
    fun validate(revision: TaxCategoryRevisionV135): TaxCategoryRevisionV135 {
        val key = DynamicTaxValidation.validateKey(revision.taxKey)
        val label = revision.label.trim()
        require(label.isNotBlank()) { "税区分名を入力してください" }
        require(label.length <= 60) { "税区分名は60文字以内です" }
        require(revision.ratePercent in 0..100) { "税率は0～100%です" }
        if (revision.mode == DynamicTaxMode.NON_TAXABLE) {
            require(revision.ratePercent == 0) { "非課税の税率は0%です" }
        } else {
            require(revision.ratePercent > 0) { "課税区分の税率は1%以上です" }
        }
        require(
            revision.effectiveToBusinessDate == null ||
                !revision.effectiveToBusinessDate.isBefore(revision.effectiveFromBusinessDate),
        ) { "適用終了営業日は適用開始営業日以降です" }
        val symbol = revision.symbol.trim()
        require(symbol.isNotBlank()) { "税記号を入力してください" }
        require(symbol.length <= 4) { "税記号は4文字以内です" }
        return revision.copy(taxKey = key, label = label, symbol = symbol)
    }

    fun requireNoOverlap(
        existing: List<TaxCategoryRevisionV135>,
        candidate: TaxCategoryRevisionV135,
    ) {
        val clean = validate(candidate)
        existing
            .filter { DynamicTaxValidation.normalizeKey(it.taxKey) == clean.taxKey }
            .forEach { row ->
                val overlaps = !endsBefore(row, clean) && !endsBefore(clean, row)
                require(!overlaps) {
                    "税区分 ${clean.taxKey} の適用営業日期間が既存履歴と重複しています"
                }
            }
    }

    fun resolve(
        revisions: List<TaxCategoryRevisionV135>,
        taxKey: String,
        businessDate: LocalDate,
    ): TaxCategoryRevisionV135? {
        val key = DynamicTaxValidation.normalizeKey(taxKey)
        val applicable = revisions
            .filter { DynamicTaxValidation.normalizeKey(it.taxKey) == key && it.appliesTo(businessDate) }
            .sortedByDescending { it.effectiveFromBusinessDate }
        require(applicable.size <= 1) { "税区分 $key の適用営業日が重複しています" }
        return applicable.singleOrNull()?.takeIf { it.enabled }
    }

    private fun endsBefore(a: TaxCategoryRevisionV135, b: TaxCategoryRevisionV135): Boolean =
        a.effectiveToBusinessDate?.isBefore(b.effectiveFromBusinessDate) == true
}

/**
 * TAX-001 persistence layer.
 *
 * `dynamic_tax_rules` remains the compatibility/current table used by the existing sales runtime.
 * This layer keeps immutable business-date revisions and promotes only the revision that belongs to
 * the OPEN business date. Therefore crossing calendar midnight alone never changes the active tax.
 */
object TaxCategoryHistorySchemaV135 {
    const val TABLE = "tax_category_history_v135"
    private const val LEGACY_FROM = "0001-01-01"

    fun ensure(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tax_key TEXT NOT NULL,
                label TEXT NOT NULL,
                rate_percent INTEGER NOT NULL,
                price_mode TEXT NOT NULL,
                rounding TEXT NOT NULL DEFAULT 'FLOOR',
                reduced INTEGER NOT NULL,
                enabled INTEGER NOT NULL,
                symbol TEXT NOT NULL,
                effective_from_business_date TEXT NOT NULL,
                effective_to_business_date TEXT,
                source_updated_at INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL,
                UNIQUE(tax_key, effective_from_business_date)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_tax_category_history_effective_v135 " +
                "ON $TABLE(tax_key, effective_from_business_date, effective_to_business_date)",
        )

        backfillCurrent(db)
        installDynamicRuleTriggers(db)
        if (SchemaMigration.tableExists(db, "business_sessions")) {
            installBusinessDateTriggers(db)
            promoteForOpenBusinessDate(db)
        }
    }

    fun list(db: SQLiteDatabase, taxKey: String? = null): List<TaxCategoryRevisionV135> {
        val result = mutableListOf<TaxCategoryRevisionV135>()
        val where = taxKey?.let { "tax_key = ?" }
        val args = taxKey?.let { arrayOf(DynamicTaxValidation.validateKey(it)) }
        db.query(
            TABLE,
            arrayOf(
                "tax_key", "label", "rate_percent", "price_mode", "rounding", "reduced",
                "enabled", "symbol", "effective_from_business_date", "effective_to_business_date",
            ),
            where,
            args,
            null,
            null,
            "tax_key ASC, effective_from_business_date ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TaxCategoryRevisionV135(
                    taxKey = cursor.getString(0),
                    label = cursor.getString(1),
                    ratePercent = cursor.getInt(2),
                    mode = DynamicTaxMode.valueOf(cursor.getString(3)),
                    rounding = TaxRoundingV135.valueOf(cursor.getString(4)),
                    reduced = cursor.getInt(5) != 0,
                    enabled = cursor.getInt(6) != 0,
                    symbol = cursor.getString(7),
                    effectiveFromBusinessDate = LocalDate.parse(cursor.getString(8)),
                    effectiveToBusinessDate = cursor.getString(9)?.takeIf { it.isNotBlank() }?.let(LocalDate::parse),
                )
            }
        }
        return result
    }

    fun resolve(db: SQLiteDatabase, taxKey: String, businessDate: LocalDate): TaxCategoryRevisionV135? =
        TaxCategoryHistoryPolicyV135.resolve(list(db, taxKey), taxKey, businessDate)

    /** Explicit history API for later settings UI; rejects ambiguous overlapping periods. */
    fun append(db: SQLiteDatabase, revision: TaxCategoryRevisionV135, recordedAt: Long = System.currentTimeMillis()) {
        val clean = TaxCategoryHistoryPolicyV135.validate(revision)
        TaxCategoryHistoryPolicyV135.requireNoOverlap(list(db, clean.taxKey), clean)
        db.insertOrThrow(
            TABLE,
            null,
            ContentValues().apply {
                put("tax_key", clean.taxKey)
                put("label", clean.label)
                put("rate_percent", clean.ratePercent)
                put("price_mode", clean.mode.name)
                put("rounding", clean.rounding.name)
                put("reduced", if (clean.reduced) 1 else 0)
                put("enabled", if (clean.enabled) 1 else 0)
                put("symbol", clean.symbol)
                put("effective_from_business_date", clean.effectiveFromBusinessDate.toString())
                clean.effectiveToBusinessDate?.let { put("effective_to_business_date", it.toString()) }
                put("source_updated_at", recordedAt)
                put("recorded_at", recordedAt)
            },
        )
    }

    private fun backfillCurrent(db: SQLiteDatabase) {
        if (!SchemaMigration.tableExists(db, "dynamic_tax_rules")) return
        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE(
                tax_key, label, rate_percent, price_mode, rounding, reduced, enabled, symbol,
                effective_from_business_date, effective_to_business_date, source_updated_at, recorded_at
            )
            SELECT
                tax_key, label, rate_percent, price_mode, 'FLOOR', reduced, enabled, symbol,
                CASE WHEN trim(valid_from) = '' THEN '$LEGACY_FROM' ELSE valid_from END,
                CASE WHEN trim(valid_to) = '' THEN NULL ELSE valid_to END,
                updated_at, updated_at
            FROM dynamic_tax_rules
            """.trimIndent(),
        )
    }

    private fun installDynamicRuleTriggers(db: SQLiteDatabase) {
        if (!SchemaMigration.tableExists(db, "dynamic_tax_rules")) return

        // Seed rows predate TAX-001 and are backfilled above. From this point new/edited rows need a start business date.
        db.execSQL("DROP TRIGGER IF EXISTS trg_tax001_require_start_insert_v135")
        db.execSQL(
            """
            CREATE TRIGGER trg_tax001_require_start_insert_v135
            BEFORE INSERT ON dynamic_tax_rules
            WHEN trim(NEW.valid_from) = ''
            BEGIN
                SELECT RAISE(ABORT, '適用開始営業日は必須です');
            END
            """.trimIndent(),
        )

        db.execSQL("DROP TRIGGER IF EXISTS trg_tax001_require_start_update_v135")
        db.execSQL(
            """
            CREATE TRIGGER trg_tax001_require_start_update_v135
            BEFORE UPDATE OF valid_from ON dynamic_tax_rules
            WHEN trim(NEW.valid_from) = ''
            BEGIN
                SELECT RAISE(ABORT, '適用開始営業日は必須です');
            END
            """.trimIndent(),
        )

        // If an OPEN business date exists, a same-key future revision is staged instead of replacing today's row.
        db.execSQL("DROP TRIGGER IF EXISTS trg_tax001_stage_future_revision_v135")
        db.execSQL(
            """
            CREATE TRIGGER trg_tax001_stage_future_revision_v135
            BEFORE INSERT ON dynamic_tax_rules
            WHEN EXISTS(SELECT 1 FROM dynamic_tax_rules old WHERE old.tax_key = NEW.tax_key)
             AND EXISTS(SELECT 1 FROM business_sessions s WHERE s.status = 'OPEN')
             AND NEW.valid_from > (SELECT s.business_date FROM business_sessions s WHERE s.status = 'OPEN' ORDER BY s.opened_at DESC, s.id DESC LIMIT 1)
            BEGIN
                UPDATE $TABLE
                   SET effective_to_business_date = date(NEW.valid_from, '-1 day')
                 WHERE tax_key = NEW.tax_key
                   AND effective_from_business_date < NEW.valid_from
                   AND (effective_to_business_date IS NULL OR effective_to_business_date >= NEW.valid_from);
                INSERT OR REPLACE INTO $TABLE(
                    tax_key, label, rate_percent, price_mode, rounding, reduced, enabled, symbol,
                    effective_from_business_date, effective_to_business_date, source_updated_at, recorded_at
                ) VALUES(
                    NEW.tax_key, NEW.label, NEW.rate_percent, NEW.price_mode, 'FLOOR', NEW.reduced, NEW.enabled, NEW.symbol,
                    NEW.valid_from, NULLIF(NEW.valid_to, ''), NEW.updated_at, NEW.updated_at
                );
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )

        // Immediate/same-day inserts and replacements are also retained as history.
        db.execSQL("DROP TRIGGER IF EXISTS trg_tax001_record_current_v135")
        db.execSQL(
            """
            CREATE TRIGGER trg_tax001_record_current_v135
            AFTER INSERT ON dynamic_tax_rules
            BEGIN
                UPDATE $TABLE
                   SET effective_to_business_date = date(NEW.valid_from, '-1 day')
                 WHERE tax_key = NEW.tax_key
                   AND effective_from_business_date < NEW.valid_from
                   AND (effective_to_business_date IS NULL OR effective_to_business_date >= NEW.valid_from);
                INSERT OR REPLACE INTO $TABLE(
                    tax_key, label, rate_percent, price_mode, rounding, reduced, enabled, symbol,
                    effective_from_business_date, effective_to_business_date, source_updated_at, recorded_at
                ) VALUES(
                    NEW.tax_key, NEW.label, NEW.rate_percent, NEW.price_mode, 'FLOOR', NEW.reduced, NEW.enabled, NEW.symbol,
                    NEW.valid_from, NULLIF(NEW.valid_to, ''), NEW.updated_at, NEW.updated_at
                );
            END
            """.trimIndent(),
        )

        db.execSQL("DROP TRIGGER IF EXISTS trg_tax001_block_delete_v135")
        db.execSQL(
            """
            CREATE TRIGGER trg_tax001_block_delete_v135
            BEFORE DELETE ON dynamic_tax_rules
            BEGIN
                SELECT RAISE(ABORT, '税区分は削除できません。有効/無効で履歴管理してください');
            END
            """.trimIndent(),
        )
    }

    private fun installBusinessDateTriggers(db: SQLiteDatabase) {
        val promotionSql = promotionSql("NEW.business_date")
        db.execSQL("DROP TRIGGER IF EXISTS trg_tax001_business_open_insert_v135")
        db.execSQL(
            """
            CREATE TRIGGER trg_tax001_business_open_insert_v135
            AFTER INSERT ON business_sessions
            WHEN NEW.status = 'OPEN'
            BEGIN
                $promotionSql;
            END
            """.trimIndent(),
        )
        db.execSQL("DROP TRIGGER IF EXISTS trg_tax001_business_open_update_v135")
        db.execSQL(
            """
            CREATE TRIGGER trg_tax001_business_open_update_v135
            AFTER UPDATE OF business_date, status ON business_sessions
            WHEN NEW.status = 'OPEN'
            BEGIN
                $promotionSql;
            END
            """.trimIndent(),
        )
    }

    fun promoteForOpenBusinessDate(db: SQLiteDatabase) {
        if (!SchemaMigration.tableExists(db, "business_sessions")) return
        val businessDate = db.rawQuery(
            "SELECT business_date FROM business_sessions WHERE status = 'OPEN' ORDER BY opened_at DESC, id DESC LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return
        db.execSQL(promotionSql("'${businessDate.replace("'", "''")}'"))
    }

    private fun promotionSql(dateExpression: String): String =
        """
        INSERT OR REPLACE INTO dynamic_tax_rules(
            tax_key, label, rate_percent, price_mode, reduced, enabled, symbol,
            valid_from, valid_to, display_order, updated_at
        )
        SELECT
            h.tax_key, h.label, h.rate_percent, h.price_mode, h.reduced, h.enabled, h.symbol,
            h.effective_from_business_date, COALESCE(h.effective_to_business_date, ''),
            COALESCE((SELECT d.display_order FROM dynamic_tax_rules d WHERE d.tax_key = h.tax_key), 999),
            h.source_updated_at
        FROM $TABLE h
        WHERE h.effective_from_business_date <= $dateExpression
          AND (h.effective_to_business_date IS NULL OR h.effective_to_business_date >= $dateExpression)
          AND NOT EXISTS(
              SELECT 1 FROM $TABLE newer
              WHERE newer.tax_key = h.tax_key
                AND newer.effective_from_business_date <= $dateExpression
                AND (newer.effective_to_business_date IS NULL OR newer.effective_to_business_date >= $dateExpression)
                AND (
                    newer.effective_from_business_date > h.effective_from_business_date OR
                    (newer.effective_from_business_date = h.effective_from_business_date AND newer.id > h.id)
                )
          )
        """.trimIndent()
}

object TaxCategoryHistoryRuntimeV135 {
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        // DynamicCatalogStore creates/seeds the compatibility master before TAX-001 starts enforcing start dates.
        DynamicCatalogStore(appContext).use { }
        RegisterDatabase(appContext).use { helper ->
            val db = helper.writableDatabase
            BusinessSessionSchema.ensure(db)
            TaxCategoryHistorySchemaV135.ensure(db)
        }
    }
}
