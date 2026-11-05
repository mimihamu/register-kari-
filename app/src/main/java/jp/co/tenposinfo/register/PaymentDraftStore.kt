package jp.co.tenposinfo.register

import android.content.ContentValues
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal data class PaymentDraftLoadResult(
    val state: PaymentState,
    val restored: Boolean,
    val updatedAt: Long?,
    val commitKey: String?,
    val integrityIssue: String? = null,
)

internal object PaymentDraftFingerprint {
    fun of(items: List<CartItem>): String = digest(items, exactIdentity = true)

    /** Only for detecting pre-v1.81 interrupted drafts; never use for restoring tender. */
    fun legacyV180Of(items: List<CartItem>): String = digest(items, exactIdentity = false)

    private fun digest(items: List<CartItem>, exactIdentity: Boolean): String {
        val canonical = items.mapIndexed { index, item ->
            listOf(
                index,
                item.product.id,
                item.product.name,
                item.quantity,
                *if (exactIdentity) arrayOf(item.quantityHundredths, item.lineId) else emptyArray(),
                item.unitPrice,
                item.discountAmount,
                item.note,
                item.product.taxKey,
                item.product.taxRatePercent,
                item.product.taxIncluded,
                item.product.taxable,
            ).joinToString("\u001F")
        }.joinToString("\u001E")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

internal object PaymentCommitKey {
    fun newKey(): String = UUID.randomUUID().toString()

    fun isValid(value: String?): Boolean = runCatching {
        UUID.fromString(value)
    }.isSuccess
}

/**
 * 支払入力途中の割当と会計冪等キーをSQLiteへ保存する。
 * カート指紋が一致する場合だけ復元し、別伝票の支払を誤適用しない。
 */
internal class PaymentDraftStore(
    private val database: RegisterDatabase,
) {
    fun save(items: List<CartItem>, state: PaymentState, commitKey: String) {
        require(PaymentCommitKey.isValid(commitKey)) { "会計キーが不正です" }
        if (items.isEmpty()) {
            require(state.allocations.isEmpty()) { "空の伝票へ支払を保存できません" }
            clear()
            return
        }
        require(PaymentDraftIntegrityV181.validAllocations(state.allocations, TaxEngine.calculate(items).grossAmount)) {
            "支払配分が伝票合計と一致しません"
        }
        val db = database.writableDatabase
        ensureSchema()
        db.beginTransaction()
        try {
            db.delete(TABLE_ALLOCATIONS, null, null)
            db.delete(TABLE_META, null, null)
            val updatedAt = System.currentTimeMillis()
            db.insertOrThrow(
                TABLE_META,
                null,
                ContentValues().apply {
                    put("id", SINGLETON_ID)
                    put("cart_fingerprint", PaymentDraftFingerprint.of(items))
                    put("commit_key", commitKey)
                    put("updated_at", updatedAt)
                },
            )
            state.allocations.forEachIndexed { index, allocation ->
                db.insertOrThrow(
                    TABLE_ALLOCATIONS,
                    null,
                    ContentValues().apply {
                        put("sequence_no", index + 1)
                        put("payment_method", allocation.method.name)
                        put("applied_amount", allocation.appliedAmount)
                        put("received_amount", allocation.receivedAmount)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadOrCreate(items: List<CartItem>): PaymentDraftLoadResult {
        require(items.isNotEmpty()) { "空の伝票では支払を開始できません" }
        val loaded = load(items)
        if (loaded.integrityIssue != null) return loaded
        if (PaymentCommitKey.isValid(loaded.commitKey)) return loaded
        val key = PaymentCommitKey.newKey()
        save(items, loaded.state, key)
        return loaded.copy(commitKey = key)
    }

    fun load(items: List<CartItem>): PaymentDraftLoadResult {
        ensureSchema()
        if (items.isEmpty()) {
            clear()
            return PaymentDraftLoadResult(PaymentState(), false, null, null)
        }
        val db = database.readableDatabase
        val metadata = db.query(
            TABLE_META,
            arrayOf("cart_fingerprint", "updated_at", "commit_key"),
            "id = ?",
            arrayOf(SINGLETON_ID.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                Triple(
                    cursor.getString(0),
                    cursor.getLong(1),
                    if (cursor.isNull(2)) null else cursor.getString(2),
                )
            }
        } ?: return PaymentDraftLoadResult(PaymentState(), false, null, null)

        val allocations = runCatching {
            db.query(
                TABLE_ALLOCATIONS,
                arrayOf("payment_method", "applied_amount", "received_amount"),
                null,
                null,
                null,
                null,
                "sequence_no ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            PaymentAllocation(
                                method = PaymentMethod.valueOf(cursor.getString(0)),
                                appliedAmount = cursor.getLong(1),
                                receivedAmount = cursor.getLong(2),
                            ),
                        )
                    }
                }
            }
        }.getOrElse {
            return PaymentDraftLoadResult(
                PaymentState(), false, metadata.second, metadata.third,
                PaymentDraftIntegrityV181.REVIEW_MESSAGE,
            )
        }

        if (metadata.first != PaymentDraftFingerprint.of(items)) {
            // An old interrupted draft may have a valid payment for a different fractional
            // quantity or sale line. Never silently replay it against today's cart.
            if (allocations.isNotEmpty() &&
                metadata.first == PaymentDraftFingerprint.legacyV180Of(items)
            ) {
                return PaymentDraftLoadResult(
                    PaymentState(), false, metadata.second, metadata.third,
                    PaymentDraftIntegrityV181.REVIEW_MESSAGE,
                )
            }
            clear()
            return PaymentDraftLoadResult(PaymentState(), false, null, null)
        }
        if (!PaymentDraftIntegrityV181.validAllocations(
                allocations, TaxEngine.calculate(items).grossAmount,
            ) || (allocations.isNotEmpty() && !PaymentCommitKey.isValid(metadata.third))
        ) {
            return PaymentDraftLoadResult(
                PaymentState(), false, metadata.second, metadata.third,
                PaymentDraftIntegrityV181.REVIEW_MESSAGE,
            )
        }

        return PaymentDraftLoadResult(
            state = PaymentState(allocations),
            restored = allocations.isNotEmpty(),
            updatedAt = metadata.second,
            commitKey = metadata.third,
        )
    }

    fun clear() {
        ensureSchema()
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_ALLOCATIONS, null, null)
            db.delete(TABLE_META, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun ensureSchema() {
        val db = database.writableDatabase
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_META (
                id INTEGER PRIMARY KEY,
                cart_fingerprint TEXT NOT NULL,
                commit_key TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        val metadataColumns = db.rawQuery("PRAGMA table_info($TABLE_META)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        if ("commit_key" !in metadataColumns) {
            db.execSQL("ALTER TABLE $TABLE_META ADD COLUMN commit_key TEXT")
        }
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ALLOCATIONS (
                sequence_no INTEGER PRIMARY KEY,
                payment_method TEXT NOT NULL,
                applied_amount INTEGER NOT NULL,
                received_amount INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    companion object {
        private const val SINGLETON_ID = 1L
        private const val TABLE_META = "payment_draft_meta"
        private const val TABLE_ALLOCATIONS = "payment_draft_allocations"
    }
}
