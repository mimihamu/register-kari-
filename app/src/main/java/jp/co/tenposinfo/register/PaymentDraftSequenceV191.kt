package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

/**
 * The stored sequence is payment evidence. Never silently renumber a damaged
 * draft during save/cancel/external handoff/recovery/cleanup.
 */
internal object PaymentDraftSequenceV191 {
    const val REVIEW_MESSAGE =
        "保存された支払明細番号に欠落があります。再決済せず売上履歴と決済端末を確認してください"

    /** SQLite INTEGER is 64-bit. Avoid getInt() overflow accepting forged ids. */
    fun contiguous(sequences: List<Long>): Boolean =
        sequences.withIndex().all { (index, sequence) -> sequence == index.toLong() + 1L }

    fun requireContiguous(db: SQLiteDatabase) {
        val sequences = db.query(
            "payment_draft_allocations",
            arrayOf("sequence_no"),
            null, null, null, null, "sequence_no ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        require(contiguous(sequences)) { REVIEW_MESSAGE }
    }
}
