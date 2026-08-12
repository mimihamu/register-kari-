package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V111SafRenameFallbackSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun renameNullIsTheOnlyCopyFallbackSignal() {
        val source = deliverySource()
        val body = functionBody(source, "    private fun deliverOne(", "    private fun copyPartialToFinal(")
        val renameStart = body.indexOf("val renamed =")
        val finalStart = body.indexOf("val finalUri =", renameStart)
        assertTrue(renameStart >= 0)
        assertTrue(finalStart > renameStart)
        val renameBlock = body.substring(renameStart, finalStart)

        assertTrue(renameBlock.contains("OutboxExternalDocumentProvider.rename(appContext, partialUri, fileName)"))
        assertFalse(renameBlock.contains("catch"))
        assertFalse(renameBlock.contains("Throwable"))
        assertTrue(body.contains("val finalUri = renamed ?: copyPartialToFinal("))
    }

    @Test
    fun providerRenameReturnsNullOnlyWhenDocumentsContractDoes() {
        val source = deliverySource()
        val body = functionBody(source, "    fun rename(", "    fun delete(")
        assertTrue(body.contains("DocumentsContract.renameDocument("))
        assertTrue(body.contains(") ?: return null"))
        assertTrue(body.contains("return verifyExactDisplayName(context, renamed, displayName)"))
        assertFalse(body.contains("runCatching"))
    }

    @Test
    fun renameMetadataAndCollisionErrorsCanPropagate() {
        val source = deliverySource()
        val body = functionBody(source, "    private fun deliverOne(", "    private fun copyPartialToFinal(")
        val renameStart = body.indexOf("val renamed =")
        val finalStart = body.indexOf("val finalUri =", renameStart)
        val renameBlock = body.substring(renameStart, finalStart)

        assertFalse(renameBlock.contains("OutboxProviderMetadataUnavailableException"))
        assertFalse(renameBlock.contains("OutboxProviderNameMismatchException"))
        assertFalse(renameBlock.contains("SecurityException"))
        assertFalse(renameBlock.contains("IOException"))
        assertFalse(renameBlock.contains("FileNotFoundException"))
    }

    @Test
    fun existingRetryAndCollisionClassificationRemainsCentralized() {
        val source = deliverySource()
        val processBody = functionBody(source, "    fun process(limit: Int = 100)", "    fun retryFailed(): Int")
        assertTrue(processBody.contains("val permissionLost = error is SecurityException"))
        assertTrue(processBody.contains("val collision = error is OutboxDestinationCollisionException"))
        assertTrue(processBody.contains("markDeliveryFailure(record, message, forcePermanent = collision)"))
        assertTrue(processBody.contains("retryRecommended = !permissionLost && !collision && !permanent"))
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 141"))
        assertTrue(gradle.contains("versionName = \"1.11.0-dev.1\""))

        val design = File(root, "docs/V1.11_SAF_RENAME_FALLBACK_SAFETY.md")
        val notes = File(root, "docs/V1.11_RELEASE_NOTES.md")
        val workflow = File(root, ".github/workflows/build-apk.yml")
        assertTrue(design.isFile)
        assertTrue(notes.isFile)
        assertTrue(workflow.isFile)

        val workflowText = workflow.readText()
        assertTrue(workflowText.contains("DRIVE_SAF_RENAME_NULL_ONLY_FALLBACK=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_RENAME_ERRORS_PROPAGATE=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_RENAME_METADATA_FAILURE_NOT_COPIED=true"))
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
