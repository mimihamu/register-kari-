package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V107SafObjectKeyNameIntegrityTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun exactProviderDisplayNameIsRequired() {
        assertTrue(OutboxProviderNameSafetyV107.isExact("sale.json", "sale.json"))
        assertFalse(OutboxProviderNameSafetyV107.isExact("sale.json", "sale (1).json"))
        assertFalse(OutboxProviderNameSafetyV107.isExact("sale.json", "SALE.JSON"))
        assertFalse(OutboxProviderNameSafetyV107.isExact("sale.json", null))
    }

    @Test
    fun providerNameMismatchUsesPermanentCollisionPath() {
        val error = OutboxProviderNameMismatchException("sale.json", "sale (1).json")
        assertTrue(error is OutboxDestinationCollisionException)
        val message = error.message.orEmpty()
        assertTrue(message.contains("object key"))
        assertTrue(message.contains("送信成功扱いにしません"))
        assertTrue(message.contains("sale.json"))
        assertTrue(message.contains("sale (1).json"))
    }

    @Test
    fun createdDirectoriesFilesAndRenamesVerifyReturnedDisplayName() {
        val source = deliverySource()
        val body = functionBody(
            source,
            "internal object OutboxExternalDocumentProvider",
            "private data class OutboxDeliveryRecord",
        )

        assertTrue(body.contains("DocumentsContract.Document.COLUMN_DISPLAY_NAME"))
        assertTrue(body.countOccurrences("return verifyExactDisplayName(context, created, displayName)") >= 2)
        assertTrue(body.contains("return verifyExactDisplayName(context, renamed, displayName)"))
        assertTrue(body.contains("if (!OutboxProviderNameSafetyV107.isExact(requestedName, actualName))"))
        assertTrue(body.contains("delete(context, documentUri)"))
        assertTrue(body.contains("throw OutboxProviderNameMismatchException(requestedName, actualName)"))
    }

    @Test
    fun renameMismatchIsNotSwallowedIntoOrdinaryRenameFallback() {
        val source = deliverySource()
        val body = functionBody(source, "private fun deliverOne(", "private fun copyPartialToFinal(")
        val renameStart = body.indexOf("val renamed =")
        val finalStart = body.indexOf("val finalUri =", renameStart)
        assertTrue(renameStart >= 0)
        assertTrue(finalStart > renameStart)
        val renameBlock = body.substring(renameStart, finalStart)

        assertTrue(renameBlock.contains("OutboxExternalDocumentProvider.rename(appContext, partialUri, fileName)"))
        assertFalse(renameBlock.contains("catch"))
        assertFalse(renameBlock.contains("Throwable"))
        assertTrue(body.contains("val finalUri = renamed ?: copyPartialToFinal("))
        assertFalse(body.contains("val renamed = runCatching"))
    }

    @Test
    fun appCreatedRenamedObjectIsCleanedButPreExistingFinalStillIsNotDeleted() {
        val source = deliverySource()
        val providerBody = functionBody(
            source,
            "internal object OutboxExternalDocumentProvider",
            "private data class OutboxDeliveryRecord",
        )
        val deliverBody = functionBody(source, "private fun deliverOne(", "private fun copyPartialToFinal(")

        assertTrue(providerBody.contains("delete(context, documentUri)"))
        assertFalse(deliverBody.contains("OutboxExternalDocumentProvider.delete(appContext, existing.uri)"))
        assertTrue(deliverBody.contains("OutboxDestinationCollisionSafetyV106.decide("))
        assertTrue(deliverBody.contains("OutboxExistingDestinationDecisionV106.ALREADY_SENT -> return true"))
    }

    @Test
    fun mismatchInheritsExistingImmediateFailedNoAutomaticRetrySemantics() {
        val source = deliverySource()
        val processBody = functionBody(source, "fun process(limit: Int = 100)", "fun retryFailed(): Int")

        assertTrue(processBody.contains("val collision = error is OutboxDestinationCollisionException"))
        assertTrue(processBody.contains("markDeliveryFailure(record, message, forcePermanent = collision)"))
        assertTrue(processBody.contains("SYNC_OUTBOX_EXTERNAL_COLLISION"))
        assertTrue(processBody.contains("retryRecommended = !permissionLost && !collision && !permanent"))
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val v107Notes = File(root, "docs/V1.07_RELEASE_NOTES.md").readText()
        assertTrue(v107Notes.contains("versionCode `137`"))
        assertTrue(v107Notes.contains("versionName `1.07.0-dev.1`"))

        val design = File(root, "docs/V1.07_SAF_OBJECT_KEY_NAME_INTEGRITY.md")
        val notes = File(root, "docs/V1.07_RELEASE_NOTES.md")
        val workflow = File(root, ".github/workflows/build-apk.yml")
        assertTrue(design.isFile)
        assertTrue(notes.isFile)
        assertTrue(workflow.isFile)

        val workflowText = workflow.readText()
        assertTrue(workflowText.contains("DRIVE_SAF_EXACT_OBJECT_KEY_NAME=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_PROVIDER_RENAMING_REJECTED=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_EXISTING_FINAL_NONDESTRUCTIVE=true"))
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

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = indexOf(needle, index)
            if (index < 0) return count
            count++
            index += needle.length
        }
    }
}
