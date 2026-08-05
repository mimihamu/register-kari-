package jp.co.tenposinfo.register.plus

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class FolderImportRepository(
    private val database: ManagementDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun knownFingerprints(treeUri: String): Map<String, String> {
        val rows = linkedMapOf<String, String>()
        database.readableDatabase.rawQuery(
            """
            SELECT source_uri, content_sha256
            FROM folder_import_files
            WHERE tree_uri=?
            """.trimIndent(),
            arrayOf(treeUri),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return rows
    }

    fun recordProcessedFiles(
        treeUri: String,
        files: List<FolderImportFileMark>,
    ) {
        if (files.isEmpty()) return
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val processedAt = nowMillis()
            files.forEach { file ->
                db.insertWithOnConflict(
                    "folder_import_files",
                    null,
                    ContentValues().apply {
                        put("source_uri", file.sourceUri)
                        put("tree_uri", treeUri)
                        put("display_name", file.displayName)
                        put("content_sha256", file.contentSha256)
                        put("last_processed_at", processedAt)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearTreeHistory(treeUri: String): Int = database.writableDatabase.delete(
        "folder_import_files",
        "tree_uri=?",
        arrayOf(treeUri),
    )
}
