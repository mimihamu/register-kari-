from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one follow-up anchor, found {count}")
    p.write_text(text.replace(old, new, 1))

rebuild = "app/src/main/java/jp/co/tenposinfo/register/RestoreSyncRebuildV136.kt"
replace_once(
    rebuild,
    '''import android.database.sqlite.SQLiteDatabase
''',
    '''import android.database.sqlite.SQLiteDatabase
import java.io.File
''',
)
replace_once(
    rebuild,
    '''    fun rebuild(db: SQLiteDatabase): RestoreSyncRebuildResultV136 {
''',
    '''    fun rebuild(databaseFile: File): RestoreSyncRebuildResultV136 {
        val db = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        return try {
            rebuild(db)
        } finally {
            db.close()
        }
    }

    fun rebuild(db: SQLiteDatabase): RestoreSyncRebuildResultV136 {
''',
)

drive = "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveDirectUpload.kt"
replace_once(
    drive,
    '''        diagnosticLog.append("UPLOAD_WORKER", "STARTED", "Drive API直接送信開始")
        return runCatching {
            val token = GoogleDriveAccessTokenProvider.acquire(applicationContext)
''',
    '''        diagnosticLog.append("UPLOAD_WORKER", "STARTED", "Drive API直接送信開始")
        return runCatching {
            // BKP-006/BKP-018: direct reconciliation must be able to start from a restored
            // PENDING outbox without waiting for the independent staging worker.
            JournalOutboxStore(applicationContext).use { it.stagePending(100) }
            val token = GoogleDriveAccessTokenProvider.acquire(applicationContext)
''',
)

bootstrap = "app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt"
replace_once(
    bootstrap,
    '''            contentRollback?.discard()
            contentRollback = null
''',
    '''            contentRollback?.discard()
            contentRollback = null
            // BKP-006: restore is committed. Reconcile the rebuilt local outbox against
            // Drive immediately when network/account conditions allow. This path uploads/
            // deduplicates only; it never imports Drive sales into REGISTER.
            runCatching { GoogleDriveDirectUploadScheduler.enqueueNow(context) }
''',
)

test = "app/src/test/java/jp/co/tenposinfo/register/V144Bkp006SyncRebuildTest.kt"
replace_once(
    test,
    '''        assertTrue(drive.contains("duplicateKey"))
        assertTrue(drive.contains("file_id"))
''',
    '''        assertTrue(drive.contains("duplicateKey"))
        assertTrue(drive.contains("file_id"))
        assertTrue(drive.contains("JournalOutboxStore(applicationContext).use { it.stagePending(100) }"))
        assertTrue(bootstrap.contains("GoogleDriveDirectUploadScheduler.enqueueNow(context)"))
''',
)

print("BKP-006 follow-up patch applied")
