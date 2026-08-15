package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V133DriveDirectSyncStatusDurabilityTest {
    @Test
    fun directStatusPersistenceFailureIsRetryableUnknown() {
        assertEquals(
            GoogleDriveSyncFailureCategory.UNKNOWN,
            GoogleDriveSyncErrorPolicy.classify(
                GoogleDriveDirectSyncStatusPersistenceException("test"),
            ),
        )
        assertTrue(GoogleDriveSyncFailureCategory.UNKNOWN.retryable)
    }

    @Test
    fun durableStatusPublisherUsesSynchronousCommitForRunLifecycle() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSyncStatusDurabilityV133.kt",
        ).readText()

        assertTrue(source.contains("fun start(context: Context): String"))
        assertTrue(source.contains("fun progress("))
        assertTrue(source.contains("fun complete("))
        assertTrue(source.contains("fun failedForRun("))
        assertTrue(source.contains("fun failed("))
        assertTrue(source.contains("if (editor.commit()) return"))
        assertTrue(source.contains("GoogleDriveStartupRecoveryBarrierV132.block("))
        assertTrue(source.contains("throw error"))
    }

    @Test
    fun repositoryUsesDurablePublicationForStartProgressSuccessAndOwnedFailure() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        val repository = source.substringAfter("class GoogleDriveDirectSyncRepository(")
            .substringBefore("class GoogleDriveDirectSyncWorker")

        val start = repository.indexOf("GoogleDriveDirectSyncStatusDurabilityV133.start(appContext)")
        val progress = repository.indexOf("GoogleDriveDirectSyncStatusDurabilityV133.progress(", start)
        val complete = repository.indexOf("GoogleDriveDirectSyncStatusDurabilityV133.complete(", progress)
        val failure = repository.indexOf("GoogleDriveDirectSyncStatusDurabilityV133.failedForRun(", start)

        assertTrue(start >= 0)
        assertTrue(progress > start)
        assertTrue(complete > progress)
        assertTrue(failure > start)
        assertTrue(repository.contains("if (error is GoogleDriveDirectSyncStatusPersistenceException) throw error"))
    }

    @Test
    fun workerDoesNotPublishHistoryForUncertainStatusPersistence() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        val worker = source.substringAfter("class GoogleDriveDirectSyncWorker")
            .substringBefore("object GoogleDriveDirectSyncScheduler")

        val persistenceGuard = worker.indexOf("error is GoogleDriveDirectSyncStatusPersistenceException")
        val failureHistory = worker.indexOf("GoogleDriveWorkerVerificationRecordV127.failure(")

        assertTrue(persistenceGuard >= 0)
        assertTrue(failureHistory > persistenceGuard)
        assertTrue(worker.contains("GoogleDriveDirectSyncStatusDurabilityV133.failed("))
    }

    @Test
    fun legacyStatusStoreSchemaAndMethodsRemainAvailable() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        val store = source.substringAfter("class GoogleDriveDirectSyncStatusStore")
            .substringBefore("data class GoogleDriveDirectSyncResult")

        assertTrue(store.contains("fun running(): String"))
        assertTrue(store.contains("fun progress(runToken: String"))
        assertTrue(store.contains("fun complete(runToken: String"))
        assertTrue(store.contains("fun failedForRun(runToken: String"))
        assertTrue(store.contains("owned_run_failure_pending_v125"))
    }
}
