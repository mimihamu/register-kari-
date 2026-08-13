package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V113OutboxExternalDeliveryLeaseSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun activeLeaseCannotBeClaimedButExpiredLeaseCan() {
        val now = 10_000L
        assertTrue(OutboxExternalDeliveryLeaseV113.claimable(null, null, now))
        assertTrue(OutboxExternalDeliveryLeaseV113.claimable("worker-a", now, now))
        assertTrue(OutboxExternalDeliveryLeaseV113.claimable("worker-a", now - 1, now))
        assertFalse(OutboxExternalDeliveryLeaseV113.claimable("worker-a", now + 1, now))
    }

    @Test
    fun ownershipTransitionRequiresExactlyOneRow() {
        OutboxExternalDeliveryLeaseV113.requireOwnedTransition(1L, 1)
        listOf(0, 2).forEach { changed ->
            try {
                OutboxExternalDeliveryLeaseV113.requireOwnedTransition(1L, changed)
                fail("changed=$changed must fail")
            } catch (_: OutboxExternalDeliveryLeaseLostExceptionV113) {
            }
        }
    }

    @Test
    fun stagedRowsAreAtomicallyClaimedBeforeExternalIo() {
        val source = externalDeliverySource()
        assertTrue(source.contains("val records = claimStaged(limit)"))
        assertFalse(source.contains("val records = loadStaged(limit)"))
        val start = source.indexOf("    private fun claimStaged(")
        val end = source.indexOf("    private fun counts()", start)
        assertTrue(start >= 0 && end > start)
        val body = source.substring(start, end)
        assertTrue(body.contains("db.beginTransaction()"))
        assertTrue(body.contains("val workerToken = UUID.randomUUID().toString()"))
        assertTrue(body.contains("worker_token IS NULL OR lease_until IS NULL OR lease_until <= ?"))
        assertTrue(body.contains("OutboxExternalDeliveryLeaseV113.LEASE_MILLIS"))
        assertTrue(body.contains("""put("worker_token", workerToken)"""))
        assertTrue(body.contains("if (changed == 1) record else null"))
    }

    @Test
    fun deliveryTransitionsRequireWorkerOwnershipAndReleaseLease() {
        val source = externalDeliverySource()
        val markers = listOf(
            "private fun markSent(",
            "private fun moveBackToPending(",
            "private fun markDeliveryPaused(",
            "private fun markDeliveryFailure(",
        )
        val nextMarkers = listOf(
            "private fun moveBackToPending(",
            "private fun markDeliveryPaused(",
            "private fun markDeliveryFailure(",
            "private fun copyWithLimit(",
        )
        markers.forEachIndexed { index, marker ->
            val start = source.indexOf(marker)
            assertTrue("missing $marker", start >= 0)
            val end = source.indexOf(nextMarkers[index], start + marker.length)
            assertTrue(end > start)
            val body = source.substring(start, end)
            assertTrue("ownership missing in $marker", body.contains("worker_token=?"))
            assertTrue("token arg missing in $marker", body.contains("record.workerToken"))
            assertTrue("lease clear missing in $marker", body.contains("""putNull("lease_until")"""))
            assertTrue("token clear missing in $marker", body.contains("""putNull("worker_token")"""))
            assertTrue("row-count guard missing in $marker", body.contains("requireOwnedTransition"))
        }
    }

    @Test
    fun requeueDoesNotStealActiveDeliveryLease() {
        val source = File(root, "app/src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt").readText()
        val start = source.indexOf("    fun requeueStaged(): Int {")
        val end = source.indexOf("    fun stagingRoot(): File", start)
        val body = source.substring(start, end)
        assertTrue(body.contains("worker_token IS NULL OR lease_until IS NULL OR lease_until <= ?"))
        assertTrue(body.contains("""putNull("lease_until")"""))
        assertTrue(body.contains("""putNull("worker_token")"""))
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 145"))
        assertTrue(gradle.contains("versionName = \"1.15.0-dev.1\""))
        assertTrue(File(root, "docs/V1.13_OUTBOX_EXTERNAL_DELIVERY_LEASE_SAFETY.md").isFile)
        val notes = File(root, "docs/V1.13_RELEASE_NOTES.md")
        assertTrue(notes.isFile)
        assertTrue(notes.readText().contains("最終総合実機試験"))
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains("OUTBOX_EXTERNAL_DELIVERY_LEASE=true"))
        assertTrue(workflow.contains("OUTBOX_EXTERNAL_DELIVERY_OWNER_TOKEN=true"))
        assertTrue(workflow.contains("OUTBOX_EXTERNAL_DELIVERY_STALE_RECLAIM=true"))
        assertTrue(workflow.contains("OUTBOX_EXTERNAL_DELIVERY_ACTIVE_REQUEUE_GUARD=true"))
    }

    private fun externalDeliverySource(): String =
        File(root, "app/src/main/java/jp/co/tenposinfo/register/OutboxExternalDelivery.kt").readText()
}
