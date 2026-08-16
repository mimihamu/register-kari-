package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V122DriveImportFingerprintAtomicityTest {
    @Test
    fun driveFingerprintRunsInsideImportTransactionBeforeCommit() {
        val importSource = File(
            "src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportRepository.kt",
        ).readText()
        val driveSource = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()

        assertTrue(importSource.contains("fun importDocumentsWithCommitHook("))
        assertTrue(importSource.contains("beforeCommit: (SQLiteDatabase) -> Unit"))

        val runUpdate = importSource.indexOf("db.update(\n                \"import_runs\"")
        val hook = importSource.indexOf("beforeCommit(db)", runUpdate)
        val successful = importSource.indexOf("db.setTransactionSuccessful()", hook)
        val transactionEnd = importSource.indexOf("db.endTransaction()", successful)
        assertTrue(runUpdate >= 0)
        assertTrue(hook > runUpdate)
        assertTrue(successful > hook)
        assertTrue(transactionEnd > successful)

        val atomicImport = driveSource.indexOf(
            "SalesJournalImportRepository(database).importDocumentsWithCommitHook(documents) { db ->",
        )
        val fingerprint = driveSource.indexOf(
            "processed.forEach { recordFingerprint(db, it.remote, it.sha256) }",
            atomicImport,
        )
        val nextPage = driveSource.indexOf("pageToken = page.nextPageToken", fingerprint)
        assertTrue(atomicImport >= 0)
        assertTrue(fingerprint > atomicImport)
        assertTrue(nextPage > fingerprint)
        assertFalse(
            driveSource.contains(
                "SalesJournalImportRepository(database).importDocuments(documents)\n                    processed.forEach",
            ),
        )
    }

    @Test
    fun normalImportApiKeepsExistingBehaviorWithoutDriveHook() {
        val importSource = File(
            "src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportRepository.kt",
        ).readText()
        assertTrue(
            importSource.contains(
                "fun importDocuments(documents: List<SalesJournalImportDocument>): ImportBatchResult =\n        importDocumentsWithCommitHook(documents) { }",
            ),
        )
    }

    @Test
    fun fingerprintWriterUsesCallerTransactionDatabase() {
        val driveSource = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        assertTrue(driveSource.contains("private fun recordFingerprint(\n        db: SQLiteDatabase,"))
        assertTrue(driveSource.contains("processed.forEach { recordFingerprint(db, it.remote, it.sha256) }"))
        assertTrue(driveSource.contains("recordFingerprint(pageDb, it.remote, it.sha256)"))
        assertFalse(driveSource.contains("private fun recordFingerprint(remote:"))
    }

    @Test
    fun v120AndV121SafetyBoundariesRemainPresent() {
        val driveSource = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        assertTrue(driveSource.contains("GoogleDriveSyncSingleFlightV121.run"))
        assertTrue(driveSource.contains("visitedPageTokens"))
        assertTrue(driveSource.contains("page.incompleteSearch"))
        assertTrue(driveSource.contains("GoogleDriveRemoteVersionPolicyV119.canSkipDownload"))
        assertTrue(driveSource.contains("failedForRun"))
    }
}
