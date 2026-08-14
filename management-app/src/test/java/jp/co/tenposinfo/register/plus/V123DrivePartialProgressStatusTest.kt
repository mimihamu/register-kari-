package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V123DrivePartialProgressStatusTest {
    private val source = File(
        "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
    ).readText()

    @Test
    fun newRunClearsPreviousCountersBeforeAnyDriveWork() {
        val runningAt = source.indexOf("fun running(): String")
        val progressAt = source.indexOf("fun progress(runToken:", runningAt)
        val runningBlock = source.substring(runningAt, progressAt)

        listOf(
            "listed",
            "downloaded",
            "unchanged",
            "imported",
            "duplicates",
            "rejected",
            "errors",
        ).forEach { key -> assertTrue(key, runningBlock.contains(".putInt(\"$key\", 0)")) }
        assertTrue(runningBlock.contains("putString(\"run_token\", runToken)"))
    }

    @Test
    fun pageProgressBelongsToActiveRunAndIsWrittenAfterAtomicPageCommit() {
        val progressMethod = source.indexOf("fun progress(runToken: String, result: GoogleDriveDirectSyncResult)")
        val completeMethod = source.indexOf("fun complete(runToken:", progressMethod)
        val progressBlock = source.substring(progressMethod, completeMethod)
        assertTrue(progressBlock.contains("preferences.getString(\"run_token\", null) != runToken"))
        assertTrue(progressBlock.contains(".putInt(\"listed\", result.listedCount)"))
        assertTrue(progressBlock.contains(".putInt(\"errors\", result.errorCount)"))

        val atomicImport = source.indexOf(
            "SalesJournalImportRepository(database).importDocumentsWithCommitHook(documents) { db ->",
        )
        val fingerprint = source.indexOf(
            "processed.forEach { recordFingerprint(db, it.remote, it.sha256) }",
            atomicImport,
        )
        val progressCall = source.indexOf("statusStore.progress(", fingerprint)
        val nextPage = source.indexOf("pageToken = page.nextPageToken", progressCall)
        assertTrue(atomicImport >= 0)
        assertTrue(fingerprint > atomicImport)
        assertTrue(progressCall > fingerprint)
        assertTrue(nextPage > progressCall)
    }

    @Test
    fun failedRunPreservesLastCommittedProgressCounters() {
        val writeFailureAt = source.indexOf("private fun writeFailure(")
        val formatAt = source.indexOf("private fun formatSyncTime", writeFailureAt)
        val failureBlock = source.substring(writeFailureAt, formatAt)

        assertFalse(failureBlock.contains(".putInt(\"listed\""))
        assertFalse(failureBlock.contains(".putInt(\"downloaded\""))
        assertFalse(failureBlock.contains(".putInt(\"imported\""))
        assertTrue(failureBlock.contains(".putBoolean(\"running\", false)"))
        assertTrue(failureBlock.contains(".remove(\"run_token\")"))
    }

    @Test
    fun v121AndV122OwnershipAndAtomicityRemainIntact() {
        assertTrue(source.contains("GoogleDriveSyncSingleFlightV121.run"))
        assertTrue(source.contains("failedForRun"))
        assertTrue(source.contains("importDocumentsWithCommitHook(documents) { db ->"))
        assertTrue(source.contains("recordFingerprint(db, it.remote, it.sha256)"))
    }
}
