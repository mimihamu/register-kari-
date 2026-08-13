package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

/**
 * v1.17: 同じ同期識別子を使った「内容だけ異なる再投入」をDB境界で拒否する。
 *
 * sales_journal.event_id は業務イベントの不変識別子。従来の INSERT OR IGNORE / CONFLICT_IGNORE は
 * 完全一致の再実行を安全に冪等化できる一方、event_id が同じで内容が異なる破損も黙って無視する。
 * このBEFORE INSERTトリガは不変フィールドが食い違う場合だけABORTし、完全一致の再投入は従来どおり許す。
 *
 * sync_outbox.object_key は未送信中にフォルダー変更で書き換え可能なため、不変値として比較しない。
 * outboxのevent_idはFK先sales_journalの不変性で守り、destinationだけ同一event_idでの食い違いを拒否する。
 */
internal object SyncJournalReplayIntegrityV117 {
    private const val JOURNAL_TRIGGER = "trg_v117_sales_journal_identity_guard"
    private const val OUTBOX_TRIGGER = "trg_v117_sync_outbox_destination_guard"

    fun ensure(db: SQLiteDatabase) {
        JournalOutboxSchema.ensureCore(db)
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS $JOURNAL_TRIGGER
            BEFORE INSERT ON sales_journal
            WHEN EXISTS (
                SELECT 1
                FROM sales_journal existing
                WHERE existing.event_id = NEW.event_id
                  AND (
                    existing.business_date IS NOT NEW.business_date
                    OR existing.event_type IS NOT NEW.event_type
                    OR existing.aggregate_id IS NOT NEW.aggregate_id
                    OR existing.payload_json IS NOT NEW.payload_json
                    OR existing.created_at IS NOT NEW.created_at
                  )
            )
            BEGIN
                SELECT RAISE(ABORT, 'SYNC_JOURNAL_EVENT_ID_CONTENT_MISMATCH');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS $OUTBOX_TRIGGER
            BEFORE INSERT ON sync_outbox
            WHEN EXISTS (
                SELECT 1
                FROM sync_outbox existing
                WHERE existing.event_id = NEW.event_id
                  AND existing.destination IS NOT NEW.destination
            )
            BEGIN
                SELECT RAISE(ABORT, 'SYNC_OUTBOX_EVENT_ID_DESTINATION_MISMATCH');
            END
            """.trimIndent(),
        )
    }
}
