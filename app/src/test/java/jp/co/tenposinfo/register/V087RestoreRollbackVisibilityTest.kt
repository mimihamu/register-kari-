package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V087RestoreRollbackVisibilityTest {
    @Test
    fun dataProtectionScreenLoadsRollbackInventoryOffMainThread() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt").readText()

        assertTrue(source.contains("var rollbackInventory by remember"))
        assertTrue(source.contains("withContext(Dispatchers.IO) { RestoreRollbackSafetyV086.inventory(appContext) }"))
        assertTrue(source.contains("rollbackInventory = withContext(Dispatchers.IO)"))
    }

    @Test
    fun rollbackStatusShowsExistenceLatestVerificationAndHash() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt").readText()

        assertTrue(source.contains("復元前ロールバック"))
        assertTrue(source.contains("保管 ${'$'}{rollbackInventory.count}件 / 最新ロールバック検証OK"))
        assertTrue(source.contains("最新ロールバック検証NG"))
        assertTrue(source.contains("latestRollback.file.name"))
        assertTrue(source.contains("latestRollback.sizeBytes"))
        assertTrue(source.contains("latestRollback.sha256.take(16)"))
        assertTrue(source.contains("ロールバック再検証"))
    }

    @Test
    fun rollbackVisibilityIsReadOnlyAndDoesNotIntroduceDeletionOrManualRestore() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt").readText()
        val rollbackSection = source.substring(source.indexOf("Text(\"復元前ロールバック\""))

        assertFalse(rollbackSection.contains("rollbackInventory.latest?.file?.delete"))
        assertFalse(rollbackSection.contains("deleteRecursively"))
        assertFalse(rollbackSection.contains("ロールバック削除"))
        assertFalse(rollbackSection.contains("ロールバックから復元"))
        assertTrue(rollbackSection.contains("ロールバックDBは自動削除しません。"))
    }

    @Test
    fun v086InventoryStillVerifiesLatestSnapshotBeforeReportingOk() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt").readText()

        assertTrue(source.contains("RestoreRollbackInventoryV086(files.size, verifySnapshot(latest"))
        assertTrue(source.contains("RestoreRollbackInventoryV086(files.size, null, error.message"))
        assertTrue(source.contains("PRAGMA integrity_check"))
        assertTrue(source.contains("PRAGMA foreign_key_check"))
        assertTrue(source.contains("DataProtectionManager.sha256(file)"))
    }

    @Test
    fun docsAndCumulativeTestsRemainPresentWithoutPinningFutureReleaseIdentity() {
        val root = File("..")
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(File(root, "docs/V0.87_RESTORE_ROLLBACK_VISIBILITY.md").isFile)
        assertTrue(File(root, "docs/V0.87_RELEASE_NOTES.md").isFile)
    }
}
