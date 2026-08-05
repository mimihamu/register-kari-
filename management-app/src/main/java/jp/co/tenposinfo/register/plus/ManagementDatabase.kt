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
        require(newVersion <= DATABASE_VERSION) {
            "未対応のDB移行です: $oldVersion -> $newVersion"
        }
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
        const val DATABASE_VERSION = 4
    }
}
