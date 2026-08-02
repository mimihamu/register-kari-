package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

internal object SaleCommitIdempotencySchema {
    private const val TABLE = "sale_commit_keys"
    private const val RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L

    data class ExistingCommit(
        val saleId: Long,
        val cartFingerprint: String,
        val totalAmount: Long,
    )

    fun ensure(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                commit_key TEXT PRIMARY KEY,
                sale_id INTEGER NOT NULL UNIQUE,
                cart_fingerprint TEXT NOT NULL,
                total_amount INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_sale_commit_keys_created ON $TABLE(created_at)",
        )
    }

    fun find(db: SQLiteDatabase, commitKey: String): ExistingCommit? {
        ensure(db)
        return db.query(
            TABLE,
            arrayOf("sale_id", "cart_fingerprint", "total_amount"),
            "commit_key = ?",
            arrayOf(commitKey),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ExistingCommit(
                saleId = cursor.getLong(0),
                cartFingerprint = cursor.getString(1),
                totalAmount = cursor.getLong(2),
            )
        }
    }

    fun record(
        db: SQLiteDatabase,
        commitKey: String,
        saleId: Long,
        cartFingerprint: String,
        totalAmount: Long,
        createdAt: Long,
    ) {
        ensure(db)
        db.insertOrThrow(
            TABLE,
            null,
            ContentValues().apply {
                put("commit_key", commitKey)
                put("sale_id", saleId)
                put("cart_fingerprint", cartFingerprint)
                put("total_amount", totalAmount)
                put("created_at", createdAt)
            },
        )
    }

    fun requireCompatible(
        existing: ExistingCommit,
        cartFingerprint: String,
        totalAmount: Long,
    ) {
        require(existing.cartFingerprint == cartFingerprint) {
            "同じ会計キーが異なる伝票へ使用されています"
        }
        require(existing.totalAmount == totalAmount) {
            "同じ会計キーの合計金額が一致しません"
        }
    }

    fun cleanup(db: SQLiteDatabase, now: Long = System.currentTimeMillis()) {
        ensure(db)
        db.delete(
            TABLE,
            "created_at < ?",
            arrayOf((now - RETENTION_MILLIS).toString()),
        )
    }
}
