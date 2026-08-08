package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V038OutboxDeliveryOperationsTest {
    @Test
    fun dashboardCountsEveryOutboxStateIndependently() {
        val counts = OutboxDeliveryDashboardCounts.from(
            mapOf(
                SyncOutboxStatus.PENDING.name to 2,
                SyncOutboxStatus.PROCESSING.name to 1,
                SyncOutboxStatus.RETRY.name to 3,
                SyncOutboxStatus.STAGED.name to 4,
                SyncOutboxStatus.SENT.name to 20,
                SyncOutboxStatus.FAILED.name to 5,
            ),
        )

        assertEquals(2, counts.pending)
        assertEquals(1, counts.processing)
        assertEquals(3, counts.retry)
        assertEquals(4, counts.staged)
        assertEquals(20, counts.sent)
        assertEquals(5, counts.failed)
        assertEquals(10, counts.unsent)
    }

    @Test
    fun individualRetryIsRestrictedToFailedAndChoosesSafeTarget() {
        assertTrue(OutboxItemRetryPolicy.canRetry(SyncOutboxStatus.FAILED))
        assertFalse(OutboxItemRetryPolicy.canRetry(SyncOutboxStatus.STAGED))
        assertFalse(OutboxItemRetryPolicy.canRetry(SyncOutboxStatus.SENT))
        assertEquals(SyncOutboxStatus.STAGED, OutboxItemRetryPolicy.targetStatus(localJsonExists = true))
        assertEquals(SyncOutboxStatus.PENDING, OutboxItemRetryPolicy.targetStatus(localJsonExists = false))
    }

    @Test
    fun previewPolicyReportsTruncationWithoutChangingBytes() {
        val bytes = "{\"ok\":true}".toByteArray()
        val full = OutboxJsonPreviewPolicy.decode(bytes, bytes.size.toLong())
        assertEquals("{\"ok\":true}", full.first)
        assertFalse(full.second)

        val truncated = OutboxJsonPreviewPolicy.decode(bytes.copyOf(5), bytes.size.toLong())
        assertTrue(truncated.second)
    }

    @Test
    fun operationsRepositoryUiDocsAndVersionStayConnected() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val repository = File(root, "OutboxDeliveryOperations.kt").readText()
        val panel = File(root, "OutboxDeliveryOperationsPanel.kt").readText()
        val settings = File(root, "OutboxDeliverySettingsActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val docs = File("../docs/V0.38_OUTBOX_DELIVERY_OPERATIONS.md").readText()
        val notes = File("../docs/V0.38_RELEASE_NOTES.md").readText()

        for (token in listOf(
            "OutboxDeliveryDashboardCounts",
            "fun retryItem",
            "fun preview",
            "fun recentAudit",
            "fun testDestination",
            "SYNC_OUTBOX_ITEM_RETRY_REQUESTED",
            "SYNC_OUTBOX_DESTINATION_TEST_SUCCEEDED",
        )) assertTrue(repository.contains(token))
        assertTrue(panel.contains("送信運用ダッシュボード"))
        assertTrue(panel.contains("この1件を再試行"))
        assertTrue(panel.contains("端末内JSONプレビュー"))
        assertTrue(settings.contains("OutboxDeliveryOperationsPanel"))
        assertTrue(build.contains("versionCode = 103"))
        assertTrue(build.contains("versionName = \"0.73.0-dev.1\""))
        assertTrue(docs.contains("送信先テスト"))
        assertTrue(notes.contains("個別再試行"))
        assertFalse(File("../tools/v038_apply.py").exists())
        assertFalse(File("../.github/workflows/v038-apply.yml").exists())
    }
}