package jp.co.tenposinfo.register.plus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

enum class ManagementTaxModeV135(val displayName: String) {
    NON_TAXABLE("非課税"),
    INCLUDED("内税"),
    EXCLUDED("外税"),
}

data class ManagementTaxCategoryV135(
    val id: Long = 0L,
    val taxKey: String,
    val label: String,
    val ratePercent: Int,
    val mode: ManagementTaxModeV135,
    val reduced: Boolean,
    val symbol: String,
    val effectiveFromBusinessDate: String,
    val effectiveToBusinessDate: String = "",
    val enabled: Boolean = true,
    val systemSeed: Boolean = false,
) {
    val taxable: Boolean get() = mode != ManagementTaxModeV135.NON_TAXABLE
    val taxIncluded: Boolean get() = mode == ManagementTaxModeV135.INCLUDED
}

object ManagementTaxCategoryPolicyV135 {
    private val keyPattern = Regex("[A-Z0-9_-]{1,30}")

    val initialCategories: List<ManagementTaxCategoryV135> = listOf(
        ManagementTaxCategoryV135(
            taxKey = "NON_TAXABLE",
            label = "非課税",
            ratePercent = 0,
            mode = ManagementTaxModeV135.NON_TAXABLE,
            reduced = false,
            symbol = "非",
            effectiveFromBusinessDate = "1970-01-01",
            systemSeed = true,
        ),
        ManagementTaxCategoryV135(
            taxKey = "INCLUDED_10",
            label = "10%内税",
            ratePercent = 10,
            mode = ManagementTaxModeV135.INCLUDED,
            reduced = false,
            symbol = "内",
            effectiveFromBusinessDate = "1970-01-01",
            systemSeed = true,
        ),
        ManagementTaxCategoryV135(
            taxKey = "EXCLUDED_10",
            label = "10%外税",
            ratePercent = 10,
            mode = ManagementTaxModeV135.EXCLUDED,
            reduced = false,
            symbol = "外",
            effectiveFromBusinessDate = "1970-01-01",
            systemSeed = true,
        ),
        ManagementTaxCategoryV135(
            taxKey = "INCLUDED_8",
            label = "8%内税",
            ratePercent = 8,
            mode = ManagementTaxModeV135.INCLUDED,
            reduced = true,
            symbol = "内※",
            effectiveFromBusinessDate = "1970-01-01",
            systemSeed = true,
        ),
        ManagementTaxCategoryV135(
            taxKey = "EXCLUDED_8",
            label = "8%外税",
            ratePercent = 8,
            mode = ManagementTaxModeV135.EXCLUDED,
            reduced = true,
            symbol = "外※",
            effectiveFromBusinessDate = "1970-01-01",
            systemSeed = true,
        ),
    )

    fun validate(record: ManagementTaxCategoryV135): ManagementTaxCategoryV135 {
        val key = record.taxKey.trim().uppercase()
        require(keyPattern.matches(key)) { "税区分キーは英数字・_・-で30文字以内です" }
        val label = record.label.trim()
        require(label.isNotBlank()) { "税区分名を入力してください" }
        require(label.length <= 60) { "税区分名は60文字以内です" }
        require(record.ratePercent in 0..100) { "税率は0～100%です" }
        when (record.mode) {
            ManagementTaxModeV135.NON_TAXABLE -> require(record.ratePercent == 0) {
                "非課税の税率は0%です"
            }
            ManagementTaxModeV135.INCLUDED,
            ManagementTaxModeV135.EXCLUDED,
            -> require(record.ratePercent > 0) { "課税区分の税率は1%以上です" }
        }
        val symbol = record.symbol.trim()
        require(symbol.isNotBlank()) { "税記号を入力してください" }
        require(symbol.length <= 4) { "税記号は4文字以内です" }
        val from = parseRequiredDate(record.effectiveFromBusinessDate, "適用開始営業日")
        val to = parseOptionalDate(record.effectiveToBusinessDate, "適用終了営業日")
        if (to != null) require(!to.isBefore(from)) { "適用終了営業日は開始営業日以降です" }
        return record.copy(
            taxKey = key,
            label = label,
            symbol = symbol,
            effectiveFromBusinessDate = from.toString(),
            effectiveToBusinessDate = to?.toString().orEmpty(),
        )
    }

    fun effectiveRules(
        rules: List<ManagementTaxCategoryV135>,
        businessDate: LocalDate,
    ): List<ManagementTaxCategoryV135> = rules
        .groupBy { it.taxKey }
        .mapNotNull { (_, revisions) ->
            revisions
                .asSequence()
                .filter { revision ->
                    val from = LocalDate.parse(revision.effectiveFromBusinessDate)
                    val to = revision.effectiveToBusinessDate.takeIf(String::isNotBlank)?.let(LocalDate::parse)
                    !businessDate.isBefore(from) && (to == null || !businessDate.isAfter(to))
                }
                .maxByOrNull { LocalDate.parse(it.effectiveFromBusinessDate) }
        }
        .sortedWith(compareBy<ManagementTaxCategoryV135> { it.ratePercent }.thenBy { it.taxKey })

    fun snapshotJson(
        rules: List<ManagementTaxCategoryV135>,
        businessDate: LocalDate,
    ): String {
        val categories = JSONArray()
        effectiveRules(rules, businessDate).forEach { rule ->
            categories.put(
                JSONObject()
                    .put("taxCategoryId", rule.taxKey)
                    .put("label", rule.label)
                    .put("ratePercent", rule.ratePercent)
                    .put("priceMode", rule.mode.name)
                    .put("taxIncluded", rule.taxIncluded)
                    .put("taxable", rule.taxable)
                    .put("reduced", rule.reduced)
                    .put("symbol", rule.symbol)
                    .put("enabled", rule.enabled)
                    .put("effectiveFromBusinessDate", rule.effectiveFromBusinessDate)
                    .put("effectiveToBusinessDate", rule.effectiveToBusinessDate),
            )
        }
        return JSONObject()
            .put("schema", "register.tax-categories.v1")
            .put("effectiveBusinessDate", businessDate.toString())
            .put("categories", categories)
            .toString()
    }

    private fun parseRequiredDate(value: String, label: String): LocalDate {
        val clean = value.trim()
        require(clean.isNotBlank()) { "${label}を入力してください" }
        return runCatching { LocalDate.parse(clean) }
            .getOrElse { throw IllegalArgumentException("${label}はyyyy-MM-dd形式です") }
    }

    private fun parseOptionalDate(value: String, label: String): LocalDate? {
        val clean = value.trim()
        if (clean.isBlank()) return null
        return runCatching { LocalDate.parse(clean) }
            .getOrElse { throw IllegalArgumentException("${label}はyyyy-MM-dd形式です") }
    }
}

class ManagementTaxCategoryStoreV135(context: Context) : AutoCloseable {
    private val helper = ManagementDatabase(context.applicationContext)
    private val db: SQLiteDatabase = helper.writableDatabase

    init {
        ensureSchema(db)
        seedDefaults(db)
    }

    override fun close() = helper.close()

    fun listAll(): List<ManagementTaxCategoryV135> = loadRules(db)

    fun appendRevision(record: ManagementTaxCategoryV135): Long {
        val clean = ManagementTaxCategoryPolicyV135.validate(record.copy(id = 0L))
        db.beginTransaction()
        try {
            val existing = loadRules(db).filter { it.taxKey == clean.taxKey }
            if (existing.isNotEmpty()) {
                val latest = existing.maxBy { LocalDate.parse(it.effectiveFromBusinessDate) }
                val newFrom = LocalDate.parse(clean.effectiveFromBusinessDate)
                val latestFrom = LocalDate.parse(latest.effectiveFromBusinessDate)
                require(newFrom.isAfter(latestFrom)) {
                    "同じ税区分キーの改定日は、最新の適用開始営業日より後にしてください"
                }
                val previousTo = latest.effectiveToBusinessDate
                    .takeIf(String::isNotBlank)
                    ?.let(LocalDate::parse)
                if (previousTo == null || !previousTo.isBefore(newFrom)) {
                    db.update(
                        TABLE,
                        ContentValues().apply {
                            put("effective_to_business_date", newFrom.minusDays(1).toString())
                            put("updated_at", System.currentTimeMillis())
                        },
                        "id = ?",
                        arrayOf(latest.id.toString()),
                    )
                }
            }
            val now = System.currentTimeMillis()
            val id = db.insertOrThrow(
                TABLE,
                null,
                ContentValues().apply {
                    put("tax_key", clean.taxKey)
                    put("label", clean.label)
                    put("rate_percent", clean.ratePercent)
                    put("price_mode", clean.mode.name)
                    put("reduced", if (clean.reduced) 1 else 0)
                    put("symbol", clean.symbol)
                    put("effective_from_business_date", clean.effectiveFromBusinessDate)
                    put("effective_to_business_date", clean.effectiveToBusinessDate)
                    put("enabled", if (clean.enabled) 1 else 0)
                    put("system_seed", if (clean.systemSeed) 1 else 0)
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun snapshotJson(businessDate: LocalDate): String =
        ManagementTaxCategoryPolicyV135.snapshotJson(listAll(), businessDate)

    companion object {
        const val TABLE = "tax_category_master_v135"
        const val DELETE_GUARD_TRIGGER = "trg_v135_plus_tax_category_no_delete"

        fun ensureSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tax_key TEXT NOT NULL,
                    label TEXT NOT NULL,
                    rate_percent INTEGER NOT NULL,
                    price_mode TEXT NOT NULL,
                    reduced INTEGER NOT NULL,
                    symbol TEXT NOT NULL,
                    effective_from_business_date TEXT NOT NULL,
                    effective_to_business_date TEXT NOT NULL DEFAULT '',
                    enabled INTEGER NOT NULL,
                    system_seed INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(tax_key, effective_from_business_date)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_v135_plus_tax_category_effective ON $TABLE(tax_key, effective_from_business_date DESC)",
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS $DELETE_GUARD_TRIGGER
                BEFORE DELETE ON $TABLE
                BEGIN
                    SELECT RAISE(ABORT, 'TAX_CATEGORY_HISTORY_MUST_NOT_BE_DELETED');
                END
                """.trimIndent(),
            )
        }

        private fun seedDefaults(db: SQLiteDatabase) {
            val now = System.currentTimeMillis()
            ManagementTaxCategoryPolicyV135.initialCategories.forEach { rule ->
                db.insertWithOnConflict(
                    TABLE,
                    null,
                    ContentValues().apply {
                        put("tax_key", rule.taxKey)
                        put("label", rule.label)
                        put("rate_percent", rule.ratePercent)
                        put("price_mode", rule.mode.name)
                        put("reduced", if (rule.reduced) 1 else 0)
                        put("symbol", rule.symbol)
                        put("effective_from_business_date", rule.effectiveFromBusinessDate)
                        put("effective_to_business_date", rule.effectiveToBusinessDate)
                        put("enabled", if (rule.enabled) 1 else 0)
                        put("system_seed", 1)
                        put("created_at", now)
                        put("updated_at", now)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
        }

        private fun loadRules(db: SQLiteDatabase): List<ManagementTaxCategoryV135> {
            val result = mutableListOf<ManagementTaxCategoryV135>()
            db.query(
                TABLE,
                arrayOf(
                    "id",
                    "tax_key",
                    "label",
                    "rate_percent",
                    "price_mode",
                    "reduced",
                    "symbol",
                    "effective_from_business_date",
                    "effective_to_business_date",
                    "enabled",
                    "system_seed",
                ),
                null,
                null,
                null,
                null,
                "tax_key ASC, effective_from_business_date ASC, id ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += ManagementTaxCategoryV135(
                        id = cursor.getLong(0),
                        taxKey = cursor.getString(1),
                        label = cursor.getString(2),
                        ratePercent = cursor.getInt(3),
                        mode = ManagementTaxModeV135.valueOf(cursor.getString(4)),
                        reduced = cursor.getInt(5) != 0,
                        symbol = cursor.getString(6),
                        effectiveFromBusinessDate = cursor.getString(7),
                        effectiveToBusinessDate = cursor.getString(8),
                        enabled = cursor.getInt(9) != 0,
                        systemSeed = cursor.getInt(10) != 0,
                    )
                }
            }
            return result
        }
    }
}
