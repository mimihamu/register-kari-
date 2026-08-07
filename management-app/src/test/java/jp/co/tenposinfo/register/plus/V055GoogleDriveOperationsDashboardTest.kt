package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V055GoogleDriveOperationsDashboardTest {
    @Test
    fun schedulerSnapshotDetectsActiveWork() {
        assertTrue(
            GoogleDriveSchedulerSnapshot(periodicStates = listOf("ENQUEUED")).periodicScheduled,
        )
        assertTrue(
            GoogleDriveSchedulerSnapshot(manualStates = listOf("RUNNING")).manualScheduled,
        )
        assertFalse(
            GoogleDriveSchedulerSnapshot(periodicStates = listOf("SUCCEEDED")).periodicScheduled,
        )
    }

    @Test
    fun rejectionRetryRequiresRecoverableSource() {
        val drive = ImportRejectionSummary(
            id = 1,
            sourceName = "sale.json",
            rejectionCode = "READ_ERROR",
            message = "read failed",
            createdAt = 1,
            sourceUri = "gdrive://file-id",
        )
        val folder = drive.copy(id = 2, sourceUri = "content://provider/document/1")
        val unavailable = drive.copy(id = 3, sourceUri = null)
        assertTrue(GoogleDriveRejectedRetryPolicy.canRetry(drive))
        assertTrue(GoogleDriveRejectedRetryPolicy.canRetry(folder))
        assertFalse(GoogleDriveRejectedRetryPolicy.canRetry(unavailable))
    }

    @Test
    fun operationsDashboardKeepsRetriesSafeAndAuditable() {
        val root = File("..")
        val source = File("src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveSyncVerificationActivity.kt").readText()
        val runtime = File("src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveOperationsRuntime.kt").readText()
        val repository = File("src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportRepository.kt").readText()
        val build = File("build.gradle.kts").readText()
        val docs = File(root, "docs/V0.55_SYNC_OPERATIONS_DASHBOARD.md").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(build.contains("versionCode = 14"))
        assertTrue(build.contains("versionName = \"0.14.0-dev.1\""))
        assertTrue(source.contains("同期運用ダッシュボード"))
        assertTrue(source.contains("失敗した同期を安全に再試行"))
        assertTrue(source.contains("この1件を再試行"))
        assertTrue(source.contains("全件強制再取込はここから実行しません"))
        assertTrue(source.contains("GoogleDriveSchedulerInspector.inspect"))
        assertTrue(source.contains("GoogleDriveRejectedRetryService"))
        assertTrue(runtime.contains("getWorkInfosForUniqueWork"))
        assertTrue(runtime.contains("gdrive://"))
        assertTrue(runtime.contains("content://"))
        assertTrue(repository.contains("fun rejection(id: Long)"))
        assertTrue(repository.contains("source_uri"))
        assertFalse(runtime.contains("DELETE FROM import_rejections"))
        assertFalse(source.contains("forceReimport = true"))
        assertTrue(docs.contains("隔離履歴は削除しない"))
        assertTrue(workflow.contains("V055GoogleDriveOperationsDashboardTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.14.0_dev1_sync_operations_dashboard_debug.apk"))
    }
}
