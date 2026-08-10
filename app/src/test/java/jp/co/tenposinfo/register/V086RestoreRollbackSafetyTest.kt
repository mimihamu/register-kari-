package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V086RestoreRollbackSafetyTest {
    private fun source(): String =
        File("src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt").readText()

    @Test
    fun registeredRestoreProviderIsWalSafeImplementation() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val source = source()

        assertTrue(manifest.contains("android:name=\".DataRestoreBootstrapProviderV086\""))
        assertTrue(manifest.contains("android:initOrder=\"1000\""))
        assertFalse(manifest.contains("android:name=\".DataRestoreBootstrapProvider\""))
        assertTrue(source.contains("PendingRestoreApplierV086::applyIfPresent"))
    }

    @Test
    fun rollbackSnapshotCheckpointsWalBeforeCopyAndVerification() {
        val source = source()
        val checkpointCall = source.indexOf("checkpointAndVerifyCurrentDatabase(sourceDatabase)")
        val copyCall = source.indexOf("sourceDatabase.copyTo(temporary, overwrite = true)")
        val stagedVerify = source.indexOf("val staged = verifySnapshot(temporary, createdAt)")
        val commit = source.indexOf("DataProtectionManager.atomicReplace(temporary, target)")
        val committedVerify = source.indexOf("val committed = verifySnapshot(target, createdAt)")

        assertTrue(checkpointCall >= 0)
        assertTrue(copyCall > checkpointCall)
        assertTrue(stagedVerify > copyCall)
        assertTrue(commit > stagedVerify)
        assertTrue(committedVerify > commit)
        assertTrue(source.contains("PRAGMA wal_checkpoint(FULL)"))
        assertTrue(source.contains("require(checkpoint.first == 0)"))
        assertTrue(source.contains("require(checkpoint.second == checkpoint.third)"))
        assertTrue(source.contains("PRAGMA integrity_check"))
        assertTrue(source.contains("PRAGMA foreign_key_check"))
    }

    @Test
    fun currentDatabaseIsNotTouchedUntilVerifiedRollbackExists() {
        val source = source()
        val snapshot = source.indexOf("RestoreRollbackSafetyV086.createVerifiedSnapshot(database, restoreDir)")
        val sidecarDelete = source.indexOf("RestoreRollbackSafetyV086.deleteWalSidecars(database)")
        val replace = source.indexOf("DataProtectionManager.atomicReplace(pending, database)")

        assertTrue(snapshot >= 0)
        assertTrue(sidecarDelete > snapshot)
        assertTrue(replace > sidecarDelete)
        assertTrue(source.contains("復元中止・元DB保持。復元前ロールバックを安全に作成できません"))

        val failStart = source.indexOf("private fun failWithoutReplacement(")
        assertTrue(failStart >= 0)
        val failBody = source.substring(failStart)
        assertFalse(failBody.contains("database.delete()"))
        assertFalse(failBody.contains("deleteWalSidecars(database)"))
        assertTrue(failBody.contains("plan.delete()"))
        assertTrue(failBody.contains("pending.delete()"))
    }

    @Test
    fun failedRestoreUsesVerifiedCopyBackWithoutDestroyingRollbackSnapshot() {
        val source = source()

        assertTrue(source.contains("RestoreRollbackSafetyV086.restoreVerifiedSnapshot(rollback, database)"))
        assertTrue(source.contains("snapshot.file.copyTo(temporary, overwrite = true)"))
        assertTrue(source.contains("require(staged.sha256 == snapshot.sha256)"))
        assertTrue(source.contains("DataProtectionManager.atomicReplace(temporary, targetDatabase)"))
        assertTrue(source.contains("require(restored.sha256 == snapshot.sha256)"))
        assertFalse(source.contains("snapshot.file.delete()"))
        assertFalse(source.contains("rollback.file.delete()"))
    }

    @Test
    fun safeRestoreDoesNotMutateBusinessRowsOrExternalRecoveryData() {
        val source = source()

        assertFalse(source.contains("DELETE FROM sales"))
        assertFalse(source.contains("DELETE FROM sale_items"))
        assertFalse(source.contains("DELETE FROM sales_journal"))
        assertFalse(source.contains("DELETE FROM sync_outbox"))
        assertFalse(source.contains("UPDATE sales"))
        assertFalse(source.contains("DROP TABLE sales"))
        assertFalse(source.contains("DROP TABLE sale_items"))
        assertTrue(source.contains("DATA_RESTORE_APPLIED"))
        assertTrue(source.contains("rollback-sha256="))
    }

    @Test
    fun docsAndCumulativeWorkflowRemainPresentWithoutFutureVersionPinning() {
        val root = File("..")
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val build = File("build.gradle.kts").readText()

        assertTrue(build.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(File(root, "docs/V0.86_RESTORE_ROLLBACK_SAFETY.md").isFile)
        assertTrue(File(root, "docs/V0.86_RELEASE_NOTES.md").isFile)
    }
}
