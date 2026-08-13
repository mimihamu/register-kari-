package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V114OutboxExternalDeliveryLeaseLossFencingTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun leaseLossIsHandledBeforeGenericFailure() {
        val source = externalDeliverySource()
        val loopStart = source.indexOf("        for (record in records) {")
        val loopEnd = source.indexOf("        val countsAfter = counts()", loopStart)
        assertTrue(loopStart >= 0 && loopEnd > loopStart)
        val loop = source.substring(loopStart, loopEnd)
        val leaseCatch = loop.indexOf("catch (error: OutboxExternalDeliveryLeaseLostExceptionV113)")
        val genericCatch = loop.indexOf("catch (error: Throwable)")
        assertTrue(leaseCatch >= 0)
        assertTrue(genericCatch > leaseCatch)
        assertTrue(loop.contains("handleLeaseOwnershipLost(record, error)"))
    }

    @Test
    fun leaseLossDuringFailureTransitionDoesNotAttemptSecondTransition() {
        val source = externalDeliverySource()
        val loopStart = source.indexOf("        for (record in records) {")
        val loopEnd = source.indexOf("        val countsAfter = counts()", loopStart)
        val loop = source.substring(loopStart, loopEnd)
        assertTrue(loop.contains("catch (leaseLost: OutboxExternalDeliveryLeaseLostExceptionV113)"))
        assertTrue(loop.contains("handleLeaseOwnershipLost(record, leaseLost)"))
        val nestedCatch = loop.indexOf("catch (leaseLost: OutboxExternalDeliveryLeaseLostExceptionV113)")
        val afterNestedCatch = loop.substring(nestedCatch)
        assertFalse(afterNestedCatch.substringBefore("val result =").contains("markDeliveryFailure(record"))
        assertFalse(afterNestedCatch.substringBefore("val result =").contains("markDeliveryPaused(record"))
    }

    @Test
    fun allPerRecordOwnedOperationsAreInsideLeaseLossGuard() {
        val source = externalDeliverySource()
        val loopStart = source.indexOf("        for (record in records) {")
        val loopEnd = source.indexOf("        val countsAfter = counts()", loopStart)
        val loop = source.substring(loopStart, loopEnd)
        val tryPos = loop.indexOf("            try {")
        val localFilePos = loop.indexOf("val localFile = localFile(record.objectKey)")
        val moveBackPos = loop.indexOf("moveBackToPending(record")
        val deliverPos = loop.indexOf("deliverOne(treeUri")
        val sentPos = loop.indexOf("markSent(record)")
        val firstCatch = loop.indexOf("catch (error: OutboxExternalDeliveryLeaseLostExceptionV113)")
        assertTrue(tryPos >= 0)
        assertTrue(localFilePos in (tryPos + 1) until firstCatch)
        assertTrue(moveBackPos in (tryPos + 1) until firstCatch)
        assertTrue(deliverPos in (tryPos + 1) until firstCatch)
        assertTrue(sentPos in (tryPos + 1) until firstCatch)
    }

    @Test
    fun leaseLossHandlerIsAuditOnlyForOutboxOwnership() {
        val source = externalDeliverySource()
        val start = source.indexOf("    private fun handleLeaseOwnershipLost(")
        val end = source.indexOf("    private fun claimStaged(", start)
        assertTrue(start >= 0 && end > start)
        val handler = source.substring(start, end)
        assertTrue(handler.contains("SYNC_OUTBOX_EXTERNAL_LEASE_LOST"))
        assertTrue(handler.contains("statusStore.waiting"))
        assertFalse(handler.contains("markSent("))
        assertFalse(handler.contains("markDeliveryFailure("))
        assertFalse(handler.contains("markDeliveryPaused("))
        assertFalse(handler.contains("moveBackToPending("))
        assertFalse(handler.contains("delete("))
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 144"))
        assertTrue(gradle.contains("versionName = \"1.14.0-dev.1\""))
        assertTrue(File(root, "docs/V1.14_OUTBOX_EXTERNAL_DELIVERY_LEASE_LOSS_FENCING.md").isFile)
        val notes = File(root, "docs/V1.14_RELEASE_NOTES.md")
        assertTrue(notes.isFile)
        assertTrue(notes.readText().contains("最終総合実機試験"))
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains("OUTBOX_EXTERNAL_DELIVERY_LEASE_LOST_FENCING=true"))
        assertTrue(workflow.contains("OUTBOX_EXTERNAL_DELIVERY_LEASE_LOST_NO_SECOND_TRANSITION=true"))
        assertTrue(workflow.contains("OUTBOX_EXTERNAL_DELIVERY_LEASE_LOST_AUDIT=true"))
        assertTrue(workflow.contains("OUTBOX_EXTERNAL_DELIVERY_LOCALFILE_INSIDE_OWNERSHIP_GUARD=true"))
    }

    private fun externalDeliverySource(): String =
        File(root, "app/src/main/java/jp/co/tenposinfo/register/OutboxExternalDelivery.kt").readText()
}
