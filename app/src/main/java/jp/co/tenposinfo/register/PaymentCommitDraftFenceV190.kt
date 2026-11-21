package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

/**
 * Checks exactly what was durably accepted before the sale is recorded.
 * Must run inside the sale INSERT transaction. No writes or schema creation:
 * missing/corrupt tables fail closed instead of fabricating a fresh draft.
 */
internal object PaymentCommitDraftFenceV190 {
    const val REVIEW_MESSAGE =
        "保存済みの支払と会計画面が一致しません。再決済せず売上履歴・決済端末を確認してください"

    fun matches(
        savedFingerprint: String?,
        savedCommitKey: String?,
        storedSequenceNos: List<Int>,
        storedAllocations: List<PaymentAllocation>,
        requestedFingerprint: String,
        requestedCommitKey: String,
        requestedAllocations: List<PaymentAllocation>,
        total: Long,
    ): Boolean =
        PaymentCommitKey.isValid(requestedCommitKey) &&
            savedFingerprint == requestedFingerprint &&
            savedCommitKey == requestedCommitKey &&
            SaleCommitPaymentMatchV189.contiguousSequenceNumbers(storedSequenceNos) &&
            PaymentDraftIntegrityV181.validAllocations(storedAllocations, total) &&
            PaymentDraftIntegrityV181.validAllocations(requestedAllocations, total) &&
            storedAllocations == requestedAllocations &&
            requestedAllocations.sumOf { it.appliedAmount } == total

    fun requireMatches(
        db: SQLiteDatabase,
        commitKey: String,
        fingerprint: String,
        requestedAllocations: List<PaymentAllocation>,
        total: Long,
    ) {
        check(db.inTransaction()) { REVIEW_MESSAGE }
        val identity = db.query(
            "payment_draft_meta", arrayOf("cart_fingerprint", "commit_key"),
            "id = ?", arrayOf("1"), null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else (
                cursor.getString(0) to
                    (if (cursor.isNull(1)) null else cursor.getString(1))
            )
        }
        val sequences = mutableListOf<Int>()
        val persisted = db.query(
            "payment_draft_allocations",
            arrayOf("sequence_no", "payment_method", "applied_amount", "received_amount"),
            null, null, null, null, "sequence_no ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    sequences.add(cursor.getInt(0))
                    add(PaymentAllocation(
                        PaymentMethod.valueOf(cursor.getString(1)),
                        cursor.getLong(2),
                        cursor.getLong(3),
                    ))
                }
            }
        }
        require(matches(
            identity?.first, identity?.second, sequences, persisted,
            fingerprint, commitKey, requestedAllocations, total,
        )) { REVIEW_MESSAGE }
    }

    fun requireNoActiveDraftForLegacy(db: SQLiteDatabase) {
        check(db.inTransaction()) { REVIEW_MESSAGE }
        // The optional null-key legacy caller may operate only when there is
        // no active payment draft. An old install may have no table yet.
        val exists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='payment_draft_meta'",
            null,
        ).use { it.moveToFirst() }
        if (exists) {
            val active = db.query(
                "payment_draft_meta", arrayOf("id"), "id = ?",
                arrayOf("1"), null, null, null,
            ).use { it.moveToFirst() }
            require(!active) { REVIEW_MESSAGE }
        }
        val allocExists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='payment_draft_allocations'",
            null,
        ).use { it.moveToFirst() }
        if (allocExists) {
            val anyAllocations = db.query(
                "payment_draft_allocations", arrayOf("sequence_no"),
                null, null, null, null, null, "1",
            ).use { it.moveToFirst() }
            require(!anyAllocations) { REVIEW_MESSAGE }
        }
    }
}
