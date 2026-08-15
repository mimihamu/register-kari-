package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V125DriveFailureProgressOwnershipTest {
    private val source = File(
        "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
    ).readText()

    @Test
    fun ownedRunFailurePreservesCommittedProgressForOuterHandler() {
        val failedForRun = source.indexOf("fun failedForRun(runToken: String")
        val ownedWrite = source.indexOf("resetProgress = false", failedForRun)
        val pendingMarker = source.indexOf("markOwnedRunFailurePending = true", ownedWrite)
        assertTrue(failedForRun >= 0)
        assertTrue(ownedWrite > failedForRun)
        assertTrue(pendingMarker > ownedWrite)
    }

    @Test
    fun externalFailureResetsOnlyWhenNoOwnedRunFailureIsPending() {
        val failed = source.indexOf("fun failed(category: GoogleDriveSyncFailureCategory")
        val pendingRead = source.indexOf(
            "preferences.getBoolean(KEY_OWNED_RUN_FAILURE_PENDING, false)",
            failed,
        )
        val resetDecision = source.indexOf("resetProgress = !ownedRunFailure", pendingRead)
        val consumeMarker = source.indexOf("markOwnedRunFailurePending = false", resetDecision)
        assertTrue(failed >= 0)
        assertTrue(pendingRead > failed)
        assertTrue(resetDecision > pendingRead)
        assertTrue(consumeMarker > resetDecision)
    }

    @Test
    fun resetProgressClearsAllRunCounters() {
        val writeFailure = source.indexOf("private fun writeFailure(")
        val resetBlock = source.indexOf("if (resetProgress)", writeFailure)
        listOf(
            "putInt(\"listed\", 0)",
            "putInt(\"downloaded\", 0)",
            "putInt(\"unchanged\", 0)",
            "putInt(\"imported\", 0)",
            "putInt(\"duplicates\", 0)",
            "putInt(\"rejected\", 0)",
            "putInt(\"errors\", 0)",
        ).forEach { token ->
            assertTrue(token, source.indexOf(token, resetBlock) > resetBlock)
        }
    }

    @Test
    fun newRunAndSuccessfulCompletionClearPendingFailureMarker() {
        val running = source.indexOf("fun running(): String")
        val complete = source.indexOf("fun complete(runToken: String")
        assertTrue(source.indexOf("remove(KEY_OWNED_RUN_FAILURE_PENDING)", running) > running)
        assertTrue(source.indexOf("remove(KEY_OWNED_RUN_FAILURE_PENDING)", complete) > complete)
    }

    @Test
    fun workerStillRoutesPreflightAndRepositoryFailuresThroughStatusStore() {
        val worker = source.indexOf("class GoogleDriveDirectSyncWorker")
        val statusStore = source.indexOf(
            "val statusStore = GoogleDriveDirectSyncStatusStore(applicationContext)",
            worker,
        )
        val acquire = source.indexOf("GoogleDriveSyncAccessTokenProvider.acquire", statusStore)
        val repository = source.indexOf("GoogleDriveDirectSyncRepository(applicationContext)", acquire)
        val outerFailure = source.indexOf("statusStore.failed(", repository)
        assertTrue(worker >= 0)
        assertTrue(statusStore > worker)
        assertTrue(acquire > statusStore)
        assertTrue(repository > acquire)
        assertTrue(outerFailure > repository)
        assertFalse(source.substring(worker).contains("putInt(\"listed\", 0)"))
    }
}
