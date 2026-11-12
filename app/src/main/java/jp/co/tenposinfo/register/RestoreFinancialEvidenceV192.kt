package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * A backup restore must not overwrite unconfirmed financial evidence.
 *
 * The old database stays untouched when payment allocations or an external
 * terminal handoff exist, even if the cart was already emptied or corrupted.
 * All probes are read-only; a broken SQLite table fails closed.
 */
internal object RestoreFinancialEvidenceV192 {
    const val REVIEW_MESSAGE =
        "現在DBに未解決の支払記録があります。復元前に売上履歴と決済端末を照合してください"

    fun mustBlock(externalPendingCount: Long, draftAllocationCount: Long): Boolean =
        externalPendingCount < 0L ||
            draftAllocationCount < 0L ||
            externalPendingCount > 0L ||
            draftAllocationCount > 0L

    fun requireSafeToReplace(databaseFile: File) {
        if (!databaseFile.exists()) return
        require(databaseFile.isFile && databaseFile.length() > 0L) { REVIEW_MESSAGE }
        val db = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val pending = countIfTablePresent(db, "payment_external_pending_v188")
            val allocations = countIfTablePresent(db, "payment_draft_allocations")
            require(!mustBlock(pending, allocations)) { REVIEW_MESSAGE }
        } finally {
            db.close()
        }
    }

    private fun countIfTablePresent(db: SQLiteDatabase, table: String): Long {
        // Restricted to two constants internal to this module.
        val exists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }
        if (!exists) return 0L // Legacy versions did not create these tables.
        return db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { cursor ->
            require(cursor.moveToFirst()) { REVIEW_MESSAGE }
            cursor.getLong(0)
        }
    }
}
