package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** A durable manual-terminal handoff, NOT evidence that the terminal processed a payment. */
internal data class ExternalTenderPendingV188(
    val cartFingerprint: String,
    val commitKey: String,
    val allocation: PaymentAllocation,
    val createdAt: Long,
)

internal enum class ExternalTenderResolutionV188 {
    APPROVED_BY_OPERATOR,
    NOT_EXECUTED_OR_REVERSED_BY_OPERATOR,
}

internal object ExternalTenderLedgerV188 {
    private const val TABLE = "payment_external_pending_v188"
    const val REVIEW_MESSAGE =
        "決済端末の結果確認が必要です。再決済せず端末・加盟店の取引履歴を照合してください"

    fun requiresHandoff(method: PaymentMethod): Boolean = when (method) {
        PaymentMethod.CARD, PaymentMethod.ELECTRONIC_MONEY, PaymentMethod.QR -> true
        else -> false
    }

    fun requireNoDirectTerminalAppend(
        previousCount: Int,
        requested: List<PaymentAllocation>,
    ) {
        require(previousCount in 0..requested.size) { REVIEW_MESSAGE }
        require(requested.drop(previousCount).none { requiresHandoff(it.method) }) {
            "カード・電子マネー・QRは端末確認待ちを保存してから登録してください"
        }
    }

    fun ensure(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                cart_fingerprint TEXT NOT NULL,
                commit_key TEXT NOT NULL,
                payment_method TEXT NOT NULL,
                applied_amount INTEGER NOT NULL,
                received_amount INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    fun read(db: SQLiteDatabase): ExternalTenderPendingV188? {
        ensure(db)
        return db.query(
            TABLE, arrayOf(
                "cart_fingerprint", "commit_key", "payment_method",
                "applied_amount", "received_amount", "created_at",
            ),
            "id = 1", null, null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ExternalTenderPendingV188(
                cartFingerprint = cursor.getString(0),
                commitKey = cursor.getString(1),
                allocation = PaymentAllocation(
                    PaymentMethod.valueOf(cursor.getString(2)),
                    cursor.getLong(3),
                    cursor.getLong(4),
                ),
                createdAt = cursor.getLong(5),
            )
        }
    }

    fun requireAbsent(db: SQLiteDatabase) {
        require(read(db) == null) { REVIEW_MESSAGE }
    }

    fun matchesDraft(
        pending: ExternalTenderPendingV188,
        fingerprint: String,
        commitKey: String?,
        allocations: List<PaymentAllocation>,
        total: Long,
    ): Boolean =
        pending.cartFingerprint == fingerprint &&
            pending.commitKey == commitKey &&
            PaymentCommitKey.isValid(commitKey) &&
            requiresHandoff(pending.allocation.method) &&
            pending.createdAt > 0L &&
            PaymentDraftIntegrityV181.validAllocations(
                allocations + pending.allocation, total,
            )

    fun insert(db: SQLiteDatabase, pending: ExternalTenderPendingV188) {
        ensure(db)
        db.insertOrThrow(
            TABLE, null, ContentValues().apply {
                put("id", 1)
                put("cart_fingerprint", pending.cartFingerprint)
                put("commit_key", pending.commitKey)
                put("payment_method", pending.allocation.method.name)
                put("applied_amount", pending.allocation.appliedAmount)
                put("received_amount", pending.allocation.receivedAmount)
                put("created_at", pending.createdAt)
            },
        )
    }

    fun remove(db: SQLiteDatabase, pending: ExternalTenderPendingV188) {
        check(db.delete(
            TABLE,
            "id = 1 AND commit_key = ? AND cart_fingerprint = ? AND created_at = ?",
            arrayOf(
                pending.commitKey, pending.cartFingerprint, pending.createdAt.toString(),
            ),
        ) == 1) { REVIEW_MESSAGE }
    }

    fun audit(
        db: SQLiteDatabase,
        eventType: String,
        pending: ExternalTenderPendingV188,
        actor: String,
    ) {
        require(actor.isNotBlank()) { "担当者を確認してください" }
        OperationAuditSchemaV136.ensure(db)
        db.insertOrThrow(
            "operation_audit", null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", 0L)
                put(
                    "detail",
                    "commit_key=${pending.commitKey}; " +
                        "cart_fingerprint=${pending.cartFingerprint}; " +
                        "method=${pending.allocation.method.name}; " +
                        "applied_amount=${pending.allocation.appliedAmount}; " +
                        "received_amount=${pending.allocation.receivedAmount}; " +
                        "handoff_created_at=${pending.createdAt}; " +
                        "operator_attestation_only=true",
                )
                put("operator_name", actor.trim().take(100))
                put("created_at", System.currentTimeMillis())
            },
        )
    }
}
