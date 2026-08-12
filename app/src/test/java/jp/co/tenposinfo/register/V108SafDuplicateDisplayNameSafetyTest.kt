package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V108SafDuplicateDisplayNameSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun zeroOrOneMatchingChildIsUnambiguous() {
        OutboxDuplicateDisplayNameSafetyV108.requireUnique("sale.json", 0)
        OutboxDuplicateDisplayNameSafetyV108.requireUnique("sale.json", 1)
    }

    @Test
    fun secondMatchingChildIsPermanentCollision() {
        val error = try {
            OutboxDuplicateDisplayNameSafetyV108.requireUnique("sale.json", 2)
            fail("duplicate display name must fail")
            return
        } catch (error: OutboxDuplicateDisplayNameException) {
            error
        }
        assertTrue(error is OutboxDestinationCollisionException)
        val message = error.message.orEmpty()
        assertTrue(message.contains("同名"))
        assertTrue(message.contains("複数"))
        assertTrue(message.contains("自動選択・削除しません"))
        assertTrue(message.contains("sale.json"))
    }

    @Test
    fun findChildScansAllMatchingRowsInsteadOfReturningFirstMatch() {
        val source = deliverySource()
        val body = functionBody(source, "    fun findChild(", "    fun createFile(")

        assertTrue(body.contains("var matched: OutboxExternalDocument? = null"))
        assertTrue(body.contains("var matchCount = 0"))
        assertTrue(body.contains("matchCount++"))
        assertTrue(body.contains("OutboxDuplicateDisplayNameSafetyV108.requireUnique(displayName, matchCount)"))
        assertTrue(body.contains("matched = OutboxExternalDocument("))
        assertTrue(body.contains("return matched"))
        assertFalse(body.contains("return OutboxExternalDocument("))
    }

    @Test
    fun finalJsonPartialJsonAndPathDirectoriesAllUseUniqueLookup() {
        val source = deliverySource()
        val ensureBody = functionBody(source, "    fun ensureDirectory(", "    fun findChild(")
        val deliverBody = functionBody(source, "    private fun deliverOne(", "    private fun copyPartialToFinal(")

        assertTrue(ensureBody.contains("val existing = findChild(context, treeUri, parentUri, displayName)"))
        assertTrue(deliverBody.contains("val existing = OutboxExternalDocumentProvider.findChild("))
        assertTrue(deliverBody.contains("OutboxExternalDocumentProvider.findChild(appContext, treeUri, parent, partialName)"))
    }

    @Test
    fun duplicateLookupDoesNotDeleteOrOverwriteExistingCandidates() {
        val source = deliverySource()
        val findBody = functionBody(source, "    fun findChild(", "    fun createFile(")
        val deliverBody = functionBody(source, "    private fun deliverOne(", "    private fun copyPartialToFinal(")

        assertFalse(findBody.contains("delete("))
        assertFalse(findBody.contains("createDocument("))
        assertFalse(findBody.contains("renameDocument("))
        assertFalse(deliverBody.contains("OutboxExternalDocumentProvider.delete(appContext, existing.uri)"))
        assertTrue(deliverBody.contains("OutboxDestinationCollisionSafetyV106.decide("))
    }

    @Test
    fun duplicateNameReusesImmediateFailedNoAutomaticRetryCollisionPath() {
        val source = deliverySource()
        val processBody = functionBody(source, "    fun process(limit: Int = 100)", "    fun retryFailed(): Int")

        assertTrue(processBody.contains("val collision = error is OutboxDestinationCollisionException"))
        assertTrue(processBody.contains("markDeliveryFailure(record, message, forcePermanent = collision)"))
        assertTrue(processBody.contains("SYNC_OUTBOX_EXTERNAL_COLLISION"))
        assertTrue(processBody.contains("retryRecommended = !permissionLost && !collision && !permanent"))
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val v108Notes = File(root, "docs/V1.08_RELEASE_NOTES.md").readText()
        assertTrue(v108Notes.contains("versionCode `138`"))
        assertTrue(v108Notes.contains("versionName `1.08.0-dev.1`"))

        val design = File(root, "docs/V1.08_SAF_DUPLICATE_DISPLAY_NAME_SAFETY.md")
        val notes = File(root, "docs/V1.08_RELEASE_NOTES.md")
        val workflow = File(root, ".github/workflows/build-apk.yml")
        assertTrue(design.isFile)
        assertTrue(notes.isFile)
        assertTrue(workflow.isFile)

        val workflowText = workflow.readText()
        assertTrue(workflowText.contains("DRIVE_SAF_DUPLICATE_DISPLAY_NAME_BLOCKED=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_DUPLICATE_EXISTING_NONDESTRUCTIVE=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_UNIQUE_CHILD_REQUIRED=true"))
        assertTrue(workflowText.contains("REAL_ACCOUNT_GOOGLE_DRIVE_VERIFICATION=deferred-final-acceptance"))
        assertTrue(notes.readText().contains("最終総合実機試験"))
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
