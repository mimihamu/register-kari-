package jp.co.tenposinfo.register

import android.content.Context

/**
 * TAX-001 bootstrap compatibility for the pre-existing system-tax seed.
 *
 * DynamicCatalogStore seeds the five legacy system rows with an empty valid_from on every open
 * using CONFLICT_IGNORE. SQLite BEFORE INSERT triggers run before conflict handling, so the
 * TAX-001 start-date trigger must be removed only while that legacy seed executes. The normal
 * TaxCategoryHistorySchemaV135.ensure call immediately recreates the constraint afterwards.
 */
object TaxCategoryHistoryStartupV135 {
    fun prepareLegacySeed(context: Context) {
        RegisterDatabase(context.applicationContext).use { helper ->
            helper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS trg_tax001_require_start_insert_v135")
        }
    }
}
