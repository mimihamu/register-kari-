package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Keep this source-contract test revision tied to the current v1.34 checkpoint implementation.
// A source change here also prevents an obsolete compiled test class from being reused by CI.
class V134DrivePageCommitCheckpointRecoveryTest {
    @Test
    fun recoveryPolicyAppliesOnlyMatchingRunningRun() {
        assertTrue(
            GoogleDrivePageCommitRecoveryPolicyV134.shouldApply(
                statusRunning = true,
                statusRunToken = "run-a",
                checkpointRunToken = "run-a",
            ),
        )
        assertFalse(
            GoogleDrivePageCommitRecoveryPolicyV134.shouldApply(
                statusRunning = false,
                statusRunToken = "run-a",
                checkpointRunToken = "run-a",
            ),
        )
        assertFalse(
            GoogleDrivePageCommitRecoveryPolicyV134.shouldApply(
                statusRunning = true,
                statusRunToken = "run-b",
                checkpointRunToken = "run-a",
            ),
        )
        assertFalse(
            GoogleDrivePageCommitRecoveryPolicyV134.shouldApply(
                statusRunning = true,
                statusRunToken = null,
                checkpointRunToken = "run-a",
            ),
        )
    }

    @Test
    fun pageImportFingerprintAndCheckpointShareOutermostTransaction() {
        val direct = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        val repository = direct.substring(
            direct.indexOf("class GoogleDriveDirectSyncRepository"),
            direct.indexOf("class GoogleDriveDirectSyncWorker"),
        )

        val outerBegin = repository.indexOf("pageDb.beginTransaction()")
        val import = repository.indexOf(
            "SalesJournalImportRepository(database).importDocumentsWithCommitHook(documents) { db ->",
            outerBegin,
        )
        val importedFingerprint = repository.indexOf(
            "processed.forEach { recordFingerprint(db, it.remote, it.sha256) }",
            import,
        )
        val unchangedFingerprint = repository.indexOf(
            "recordFingerprint(pageDb, it.remote, it.sha256)",
            importedFingerprint,
        )
        val checkpoint = repository.indexOf(
            "GoogleDrivePageCommitCheckpointStoreV134.persist(",
            unchangedFingerprint,
        )
        val outerSuccess = repository.indexOf("pageDb.setTransactionSuccessful()", checkpoint)
        val outerEnd = repository.indexOf("pageDb.endTransaction()", outerSuccess)
        val statusProgress = repository.indexOf(
            "GoogleDriveDirectSyncStatusDurabilityV133.progress(",
            outerEnd,
        )

        assertTrue(outerBegin >= 0)
        assertTrue(import > outerBegin)
        assertTrue(importedFingerprint > import)
        assertTrue(unchangedFingerprint > importedFingerprint)
        assertTrue(checkpoint > unchangedFingerprint)
        assertTrue(outerSuccess > checkpoint)
        assertTrue(outerEnd > outerSuccess)
        assertTrue(statusProgress > outerEnd)
    }

    @Test
    fun sameContentFingerprintIsDeferredUntilPageTransaction() {
        val direct = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        val repository = direct.substring(
            direct.indexOf("class GoogleDriveDirectSyncRepository"),
            direct.indexOf("class GoogleDriveDirectSyncWorker"),
        )
        val sameContent = repository.indexOf("known.contentSha256 == sha256")
        val queued = repository.indexOf(
            "unchangedFingerprints += ProcessedDriveFile(remote, sha256)",
            sameContent,
        )
        val outerBegin = repository.indexOf("pageDb.beginTransaction()", queued)
        val flushed = repository.indexOf("unchangedFingerprints.forEach", outerBegin)

        assertTrue(sameContent >= 0)
        assertTrue(queued > sameContent)
        assertTrue(outerBegin > queued)
        assertTrue(flushed > outerBegin)
    }

    @Test
    fun checkpointRecoveryRunsBeforeOrphanHistoryAndBootstrap() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val checkpointProvider = manifest.indexOf(".GoogleDrivePageCommitRecoveryProviderV134")
        val order400 = manifest.indexOf("android:initOrder=\"400\"", checkpointProvider)
        val orphanProvider = manifest.indexOf(".GoogleDriveOrphanedRunRecoveryProviderV131")
        val order300 = manifest.indexOf("android:initOrder=\"300\"", orphanProvider)
        val historyProvider = manifest.indexOf(".GoogleDriveVerificationHistoryRecoveryProviderV130")
        val order200 = manifest.indexOf("android:initOrder=\"200\"", historyProvider)
        val bootstrap = manifest.indexOf(".GoogleDriveDirectSyncBootstrapProvider")
        val order100 = manifest.indexOf("android:initOrder=\"100\"", bootstrap)

        assertTrue(checkpointProvider >= 0)
        assertTrue(order400 > checkpointProvider)
        assertTrue(orphanProvider > order400)
        assertTrue(order300 > orphanProvider)
        assertTrue(historyProvider > order300)
        assertTrue(order200 > historyProvider)
        assertTrue(bootstrap > order200)
        assertTrue(order100 > bootstrap)

        val checkpoint = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDrivePageCommitCheckpointV134.kt",
        ).readText()
        val orphan = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveOrphanedRunRecoveryV131.kt",
        ).readText()
        val checkpointClass = checkpoint.indexOf("class GoogleDrivePageCommitRecoveryProviderV134")
        val reset = checkpoint.indexOf(
            "GoogleDriveStartupRecoveryBarrierV132.resetForProcessStart()",
            checkpointClass,
        )
        val reconcile = checkpoint.indexOf("GoogleDrivePageCommitRecoveryV134.reconcile", reset)
        assertTrue(reset > checkpointClass)
        assertTrue(reconcile > reset)

        val orphanClass = orphan.indexOf("class GoogleDriveOrphanedRunRecoveryProviderV131")
        val orphanGuard = orphan.indexOf(
            "if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return true",
            orphanClass,
        )
        val orphanRecover = orphan.indexOf("val recovery = runCatching", orphanGuard)
        assertTrue(orphanGuard > orphanClass)
        assertTrue(orphanRecover > orphanGuard)
    }

    @Test
    fun checkpointRecoveryDurablyRepublishesBeforeDeletingCheckpoint() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDrivePageCommitCheckpointV134.kt",
        ).readText()
        val reconcile = source.indexOf("fun reconcile(context: Context)")
        val progress = source.indexOf("GoogleDriveDirectSyncStatusDurabilityV133.progress(", reconcile)
        val clear = source.indexOf("GoogleDrivePageCommitCheckpointStoreV134.clear(db)", progress)
        val barrier = source.indexOf("GoogleDriveStartupRecoveryBarrierV132.block(", clear)

        assertTrue(progress > reconcile)
        assertTrue(clear > progress)
        assertTrue(barrier > clear)
        assertTrue(source.contains("drive_sync_progress_checkpoint_v134"))
        assertTrue(source.contains("statusRunToken == checkpointRunToken"))
    }
}
