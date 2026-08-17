package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

internal object SaleGuestCountPolicyV135 {
    const val MIN = 1
    const val MAX = 999

    fun validate(value: Int): Int {
        require(value in MIN..MAX) { "客数は${MIN}〜${MAX}名で入力してください" }
        return value
    }
}

/**
 * v2.5 sale_txn.guestCount compatibility layer.
 *
 * Main sales persistence predates guest_count. The sales table is extended without rewriting legacy
 * rows. A one-row pending value is explicitly entered by the operator and consumed by an AFTER INSERT
 * trigger when the next finalized sale is written. The pending row is deleted in that same INSERT
 * statement, so the value cannot leak into a second sale. No transaction-count inference is used.
 */
internal class SaleGuestCountRuntimeV135(context: Context) : AutoCloseable {
    private val helper = RegisterDatabase(context.applicationContext)
    private val db = helper.writableDatabase

    init {
        ensureSchema(db)
    }

    fun current(): Int = db.query(
        PENDING_TABLE,
        arrayOf("guest_count"),
        "id = 1",
        null,
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun set(value: Int) {
        val validated = SaleGuestCountPolicyV135.validate(value)
        db.insertWithOnConflict(
            PENDING_TABLE,
            null,
            ContentValues().apply {
                put("id", 1)
                put("guest_count", validated)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clear() {
        db.delete(PENDING_TABLE, "id = 1", null)
    }

    override fun close() = helper.close()

    companion object {
        private const val PENDING_TABLE = "sale_guest_count_pending_v135"
        private const val CONSUME_TRIGGER = "trg_sale_guest_count_consume_v135"

        fun ensureSchema(db: SQLiteDatabase) {
            ensureColumn(db, "sales", "guest_count", "INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $PENDING_TABLE (
                    id INTEGER PRIMARY KEY CHECK(id = 1),
                    guest_count INTEGER NOT NULL CHECK(guest_count BETWEEN $MIN_GUESTS AND $MAX_GUESTS),
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("DROP TRIGGER IF EXISTS $CONSUME_TRIGGER")
            db.execSQL(
                """
                CREATE TRIGGER $CONSUME_TRIGGER
                AFTER INSERT ON sales
                WHEN EXISTS (SELECT 1 FROM $PENDING_TABLE WHERE id = 1)
                BEGIN
                    UPDATE sales
                    SET guest_count = (SELECT guest_count FROM $PENDING_TABLE WHERE id = 1)
                    WHERE id = NEW.id;
                    DELETE FROM $PENDING_TABLE WHERE id = 1;
                END
                """.trimIndent(),
            )
        }

        private const val MIN_GUESTS = SaleGuestCountPolicyV135.MIN
        private const val MAX_GUESTS = SaleGuestCountPolicyV135.MAX

        private fun ensureColumn(
            db: SQLiteDatabase,
            table: String,
            name: String,
            declaration: String,
        ) {
            val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == name) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $name $declaration")
        }
    }
}
