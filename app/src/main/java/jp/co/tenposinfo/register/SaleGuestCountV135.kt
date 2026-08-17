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
 * v2.5 sale_txn.guestCount / held-ticket guest-count compatibility layer.
 *
 * The existing sales and held-ticket persistence predates guest count. Rather than rewriting those
 * large legacy flows, a one-row pending value is explicitly entered by the operator and transferred
 * by SQLite triggers:
 *
 * - next `sales` INSERT -> `sales.guest_count`, then pending value is consumed;
 * - next `held_tickets` INSERT -> `held_ticket_guest_count_v135`, then pending value is consumed.
 *
 * When a held ticket is recalled, HeldTicketSafetyCoordinator restores its mapped count to pending
 * before the ticket row is removed. Therefore the count follows hold -> recall -> final sale without
 * being inferred from transaction count. Existing/unentered rows stay zero.
 */
internal class SaleGuestCountRuntimeV135(context: Context) : AutoCloseable {
    private val helper = RegisterDatabase(context.applicationContext)
    private val db = helper.writableDatabase

    init {
        ensureSchema(db)
    }

    fun current(): Int = pendingGuestCount(db)

    fun set(value: Int) {
        setPendingGuestCount(db, SaleGuestCountPolicyV135.validate(value))
    }

    fun clear() {
        clearPendingGuestCount(db)
    }

    override fun close() = helper.close()

    companion object {
        internal const val PENDING_TABLE = "sale_guest_count_pending_v135"
        internal const val HELD_TABLE = "held_ticket_guest_count_v135"
        private const val SALE_CONSUME_TRIGGER = "trg_sale_guest_count_consume_v135"
        private const val HELD_CONSUME_TRIGGER = "trg_held_guest_count_consume_v135"
        private const val MIN_GUESTS = SaleGuestCountPolicyV135.MIN
        private const val MAX_GUESTS = SaleGuestCountPolicyV135.MAX

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
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $HELD_TABLE (
                    ticket_id INTEGER PRIMARY KEY,
                    guest_count INTEGER NOT NULL CHECK(guest_count BETWEEN $MIN_GUESTS AND $MAX_GUESTS),
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(ticket_id) REFERENCES held_tickets(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )

            db.execSQL("DROP TRIGGER IF EXISTS $SALE_CONSUME_TRIGGER")
            db.execSQL(
                """
                CREATE TRIGGER $SALE_CONSUME_TRIGGER
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

            db.execSQL("DROP TRIGGER IF EXISTS $HELD_CONSUME_TRIGGER")
            db.execSQL(
                """
                CREATE TRIGGER $HELD_CONSUME_TRIGGER
                AFTER INSERT ON held_tickets
                WHEN EXISTS (SELECT 1 FROM $PENDING_TABLE WHERE id = 1)
                BEGIN
                    INSERT OR REPLACE INTO $HELD_TABLE(ticket_id, guest_count, updated_at)
                    SELECT NEW.id, guest_count, updated_at
                    FROM $PENDING_TABLE
                    WHERE id = 1;
                    DELETE FROM $PENDING_TABLE WHERE id = 1;
                END
                """.trimIndent(),
            )
        }

        internal fun pendingGuestCount(db: SQLiteDatabase): Int = db.query(
            PENDING_TABLE,
            arrayOf("guest_count"),
            "id = 1",
            null,
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

        internal fun heldGuestCount(db: SQLiteDatabase, ticketId: Long): Int = db.query(
            HELD_TABLE,
            arrayOf("guest_count"),
            "ticket_id = ?",
            arrayOf(ticketId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

        internal fun setPendingGuestCount(db: SQLiteDatabase, value: Int) {
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

        internal fun clearPendingGuestCount(db: SQLiteDatabase) {
            db.delete(PENDING_TABLE, "id = 1", null)
        }

        internal fun restoreHeldGuestCountToPending(db: SQLiteDatabase, ticketId: Long): Int {
            val count = heldGuestCount(db, ticketId)
            if (count > 0) setPendingGuestCount(db, count) else clearPendingGuestCount(db)
            return count
        }

        internal fun mergeHeldGuestCounts(db: SQLiteDatabase, sourceTicketId: Long, targetTicketId: Long) {
            val source = heldGuestCount(db, sourceTicketId)
            val target = heldGuestCount(db, targetTicketId)
            val merged = source + target
            if (merged <= 0) return
            require(merged <= SaleGuestCountPolicyV135.MAX) { "結合後の客数が999名を超えます" }
            db.insertWithOnConflict(
                HELD_TABLE,
                null,
                ContentValues().apply {
                    put("ticket_id", targetTicketId)
                    put("guest_count", merged)
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }

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
