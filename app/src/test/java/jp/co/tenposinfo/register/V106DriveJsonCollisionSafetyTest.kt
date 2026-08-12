package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V106DriveJsonCollisionSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun onlyIdenticalRegularFileIsAlreadySent() {
        assertEquals(
            OutboxExistingDestinationDecisionV106.ALREADY_SENT,
            OutboxDestinationCollisionSafetyV106.decide(
                existingIsDirectory = false,
                sameSize = true,
                sameSha256 = true,
            ),
        )

        listOf(
            Triple(false, false, false),
            Triple(false, false, true),
            Triple(false, true, false),
            Triple(true, true, true),
            Triple(true, false, false),
        ).forEach { (directory, sameSize, sameSha) ->
            assertEquals(
                OutboxExistingDestinationDecisionV106.COLLISION,
                OutboxDestinationCollisionSafetyV106.decide(directory, sameSize, sameSha),
            )
        }
    }

    @Test
    fun collisionMessageExplicitlyPromisesNonDestructiveHandling() {
        val message = OutboxDestinationCollisionException("sale.json").message.orEmpty()
        assertTrue(message.contains("同名JSON"))
        assertTrue(message.contains("既存JSONは保護"))
        assertTrue(message.contains("自動置換しません"))
        assertTrue(message.contains("sale.json"))
    }

    @Test
    fun deliveryNeverDeletesOrOverwritesPreExistingFinalJson() {
        val source = deliverySource()
        val body = functionBody(source, "private fun deliverOne(", "private fun copyPartialToFinal(")

        assertTrue(body.contains("OutboxDestinationCollisionSafetyV106.decide("))
        assertTrue(body.contains("OutboxExistingDestinationDecisionV106.ALREADY_SENT -> return true"))
        assertTrue(body.contains("throw OutboxDestinationCollisionException(fileName)"))
        assertFalse(body.contains("OutboxExternalDocumentProvider.delete(appContext, existing.uri)"))
        assertFalse(body.contains("送信先の同名ファイルを置き換えられません"))
    }

    @Test
    fun hashIsCheckedOnlyAfterRegularFileAndSizeMatch() {
        val source = deliverySource()
        val body = functionBody(source, "private fun deliverOne(", "private fun copyPartialToFinal(")

        assertTrue(body.contains("val existingIsDirectory = existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR"))
        assertTrue(body.contains("val sameSize = !existingIsDirectory && existing.size == localFile.length()"))
        assertTrue(body.contains("val sameSha256 = if (sameSize)"))
        assertTrue(body.contains("送信済みJSONを検証できません"))
    }

    @Test
    fun collisionFailsImmediatelyWithoutAutomaticRetry() {
        val source = deliverySource()
        val processBody = functionBody(source, "fun process(limit: Int = 100)", "fun retryFailed(): Int")
        val failureBody = functionBody(source, "private fun markDeliveryFailure(", "private fun copyWithLimit(")

        assertTrue(processBody.contains("val collision = error is OutboxDestinationCollisionException"))
        assertTrue(processBody.contains("markDeliveryFailure(record, message, forcePermanent = collision)"))
        assertTrue(processBody.contains("SYNC_OUTBOX_EXTERNAL_COLLISION"))
        assertTrue(processBody.contains("retryRecommended = !permissionLost && !collision && !permanent"))
        assertTrue(processBody.contains("else if (countsAfter.second == 0)"))

        assertTrue(failureBody.contains("forcePermanent: Boolean = false"))
        assertTrue(failureBody.contains("val permanent = forcePermanent || OutboxDeliveryRetryPolicy.permanent(attempts)"))
        assertTrue(failureBody.contains("SyncOutboxStatus.FAILED.name"))
        assertTrue(failureBody.contains("Long.MAX_VALUE"))
    }

    @Test
    fun manualRetryRemainsAvailableAfterOperatorResolvesCollision() {
        val source = deliverySource()
        val body = functionBody(source, "fun retryFailed(): Int", "fun currentCounts()")

        assertTrue(body.contains("WHERE status='FAILED'"))
        assertTrue(body.contains("SyncOutboxStatus.STAGED.name"))
        assertTrue(body.contains("SyncOutboxStatus.PENDING.name"))
        assertTrue(body.contains("SYNC_OUTBOX_RETRY_REQUESTED"))
        assertTrue(body.contains("put(\"attempt_count\", 0)"))
    }

    @Test
    fun partialCleanupIsRetainedButCollisionPathKeepsLocalEvidence() {
        val source = deliverySource()
        val deliverBody = functionBody(source, "private fun deliverOne(", "private fun copyPartialToFinal(")
        val failureBody = functionBody(source, "private fun markDeliveryFailure(", "private fun copyWithLimit(")

        assertTrue(deliverBody.contains("val partialName = OutboxDeliveryPathPolicy.partialName(fileName)"))
        assertTrue(deliverBody.contains("前回の一時ファイルを削除できません"))
        assertFalse(failureBody.contains("localFile("))
        assertFalse(failureBody.contains("delete("))
        assertFalse(failureBody.uppercase().contains("DELETE FROM"))
        assertFalse(failureBody.uppercase().contains("DROP TABLE"))
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val v106Notes = File(root, "docs/V1.06_RELEASE_NOTES.md").readText()
        assertTrue(v106Notes.contains("versionCode `136`"))
        assertTrue(v106Notes.contains("versionName `1.06.0-dev.1`"))

        val design = File(root, "docs/V1.06_DRIVE_JSON_COLLISION_SAFETY.md")
        val notes = File(root, "docs/V1.06_RELEASE_NOTES.md")
        val workflow = File(root, ".github/workflows/build-apk.yml")
        assertTrue(design.isFile)
        assertTrue(notes.isFile)
        assertTrue(workflow.isFile)

        val designText = design.readText()
        val notesText = notes.readText()
        val workflowText = workflow.readText()
        assertTrue(designText.contains(OutboxDestinationCollisionSafetyV106.COLLISION_MESSAGE))
        assertTrue(notesText.contains("最終総合実機試験"))
        assertTrue(workflowText.contains("DRIVE_JSON_COLLISION_SAFETY=true"))
        assertTrue(workflowText.contains("DRIVE_EXISTING_JSON_NEVER_OVERWRITTEN=true"))
        assertTrue(workflowText.contains("REAL_ACCOUNT_GOOGLE_DRIVE_VERIFICATION=deferred-final-acceptance"))
        assertTrue(workflowText.contains("REAL_DEVICE_JAPANESE_PRINT_VERIFICATION=required-user-retest"))
    }

    private fun deliverySource(): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/OutboxExternalDelivery.kt",
    ).readText()

    private fun functionBody(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("missing start marker: $startMarker", start >= 0)
        val end = source.indexOf(endMarker, start).takeIf { it > start } ?: source.length
        return source.substring(start, end)
    }
}
