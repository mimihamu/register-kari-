package jp.co.tenposinfo.register.plus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ManagementDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val appContext = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE import_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                source_count INTEGER NOT NULL DEFAULT 0,
                imported_count INTEGER NOT NULL DEFAULT 0,
                duplicate_count INTEGER NOT NULL DEFAULT 0,
                rejected_count INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE imported_journal (
                duplicate_import_key TEXT PRIMARY KEY NOT NULL,
                schema_version INTEGER NOT NULL,
                minimum_reader_version INTEGER NOT NULL,
                duplicate_key_version INTEGER NOT NULL,
                event_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                store_id TEXT NOT NULL,
                terminal_id TEXT NOT NULL,
                business_date TEXT NOT NULL,
                aggregate_id TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                payload_schema TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                total_amount INTEGER,
                source_name TEXT NOT NULL,
                source_uri TEXT,
                raw_json TEXT NOT NULL,
                imported_at INTEGER NOT NULL,
                import_run_id INTEGER NOT NULL,
                FOREIGN KEY(import_run_id) REFERENCES import_runs(id) ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE import_rejections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                import_run_id INTEGER NOT NULL,
                source_name TEXT NOT NULL,
                source_uri TEXT,
                rejection_code TEXT NOT NULL,
                message TEXT NOT NULL,
                raw_preview TEXT,
                raw_sha256 TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(import_run_id) REFERENCES import_runs(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        createFolderImportFilesTable(db)
        createDriveSyncFilesTable(db)
        createImportedJournalReplayGuardV117(db)
        PlusAckOutboxV150.ensureSchema(db)
        createIndexes(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion < 2) {
            createIndexes(db)
        }
        if (oldVersion < 3) {
            createFolderImportFilesTable(db)
        }
        if (oldVersion < 4) {
            createDriveSyncFilesTable(db)
        }
        if (oldVersion < 5) {
            createImportedJournalReplayGuardV117(db)
        }
        if (oldVersion < 6) {
            ensureDriveRemoteVersionV119(db)
        }
        if (oldVersion < 7) {
            PlusAckOutboxV150.ensureSchema(db)
        }
        require(newVersion <= DATABASE_VERSION) {
            "未対応のDB移行です: $oldVersion -> $newVersion"
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        PlusAckOutboxV150.ensureSchema(db)
        SalesJournalImportCompatibilityResetV124.ensureCurrent(appContext, db)
    }

    private fun createFolderImportFilesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS folder_import_files (
                source_uri TEXT PRIMARY KEY NOT NULL,
                tree_uri TEXT NOT NULL,
                display_name TEXT NOT NULL,
                content_sha256 TEXT NOT NULL,
                last_processed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_folder_import_files_tree ON folder_import_files(tree_uri, last_processed_at DESC)",
        )
    }

    private fun createDriveSyncFilesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS drive_sync_files (
                file_id TEXT PRIMARY KEY NOT NULL,
                file_name TEXT NOT NULL,
                modified_time TEXT NOT NULL,
                remote_version TEXT,
                content_sha256 TEXT NOT NULL,
                store_id TEXT,
                terminal_id TEXT,
                business_date TEXT,
                last_processed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_drive_sync_files_modified ON drive_sync_files(modified_time, last_processed_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_drive_sync_files_identity ON drive_sync_files(store_id, terminal_id, business_date)",
        )
    }

    private fun ensureDriveRemoteVersionV119(db: SQLiteDatabase) {
        if (!hasColumn(db, "drive_sync_files", "remote_version")) {
            db.execSQL("ALTER TABLE drive_sync_files ADD COLUMN remote_version TEXT")
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use true
            }
            false
        }

    /**
     * 同一duplicate_import_keyの再取込は、業務内容が完全に同じ場合だけ既存のCONFLICT_IGNOREへ渡す。
     * Drive file id / source URI / import run / imported time は輸送メタデータなので比較しない。
     */
    private fun createImportedJournalReplayGuardV117(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS trg_v117_imported_journal_identity_guard
            BEFORE INSERT ON imported_journal
            WHEN EXISTS (
                SELECT 1
                FROM imported_journal existing
                WHERE existing.duplicate_import_key = NEW.duplicate_import_key
                  AND (
                    existing.schema_version IS NOT NEW.schema_version
                    OR existing.minimum_reader_version IS NOT NEW.minimum_reader_version
                    OR existing.duplicate_key_version IS NOT NEW.duplicate_key_version
                    OR existing.event_id IS NOT NEW.event_id
                    OR existing.event_type IS NOT NEW.event_type
                    OR existing.store_id IS NOT NEW.store_id
                    OR existing.terminal_id IS NOT NEW.terminal_id
                    OR existing.business_date IS NOT NEW.business_date
                    OR existing.aggregate_id IS NOT NEW.aggregate_id
                    OR existing.occurred_at IS NOT NEW.occurred_at
                    OR existing.payload_schema IS NOT NEW.payload_schema
                    OR existing.payload_json IS NOT NEW.payload_json
                    OR existing.total_amount IS NOT NEW.total_amount
                  )
            )
            BEGIN
                SELECT RAISE(ABORT, 'SYNC_IMPORT_DUPLICATE_KEY_CONTENT_MISMATCH');
            END
            """.trimIndent(),
        )
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_imported_journal_business_date ON imported_journal(business_date)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_imported_journal_event_type ON imported_journal(event_type)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_imported_journal_imported_at ON imported_journal(imported_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_imported_journal_report ON imported_journal(business_date, store_id, terminal_id, event_type, occurred_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_imported_journal_aggregate ON imported_journal(store_id, terminal_id, aggregate_id, event_type)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_import_rejections_created_at ON import_rejections(created_at DESC)",
        )
    }

    companion object {
        const val DATABASE_NAME = "tsuguregi_plus.db"
        // v0.40-v0.45 cumulative-test baseline: DATABASE_VERSION = 4
        // v1.17-v1.18 cumulative-test baseline: const val DATABASE_VERSION = 5
        const val DATABASE_VERSION = 7
    }
}
