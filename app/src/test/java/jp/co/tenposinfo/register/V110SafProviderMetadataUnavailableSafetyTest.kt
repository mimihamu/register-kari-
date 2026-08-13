package jp.co.tenposinfo.register

import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V110SafProviderMetadataUnavailableSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun availableMetadataPassesThrough() {
        val marker = Any()
        assertTrue(OutboxProviderMetadataSafetyV110.requireAvailable("displayName", marker) === marker)
    }

    @Test
    fun unavailableMetadataIsTransientIoFailureNotCollision() {
        val error = try {
            OutboxProviderMetadataSafetyV110.requireAvailable<Any>("displayName", null)
            fail("null metadata query must fail")
            return
        } catch (error: OutboxProviderMetadataUnavailableException) {
            error
        }
        assertTrue(error is IOException)
        assertFalse(error is OutboxDestinationCollisionException)
        assertTrue(error.message.orEmpty().contains("確定せず再試行"))
        assertTrue(error.message.orEmpty().contains("displayName"))
    }

    @Test
    fun sizeQueryNullIsNotAcceptedAsUnknownSize() {
        val source = deliverySource()
        val body = functionBody(source, "    fun size(", "    private fun displayName(")
        assertTrue(body.contains("OutboxProviderMetadataSafetyV110.requireAvailable("))
        assertTrue(body.contains("context.contentResolver.query("))
        assertTrue(body.contains("return cursor.use {"))
        assertFalse(body.contains(")?.use {"))
    }

    @Test
    fun displayNameQueryNullIsRetryableBeforeNameMismatchDecision() {
        val source = deliverySource()
        val displayBody = functionBody(source, "    private fun displayName(", "    private fun verifyExactDisplayName(")
        val verifyBody = functionBody(source, "    private fun verifyExactDisplayName(", "    private fun Cursor.longOrNull")

        assertTrue(displayBody.contains("OutboxProviderMetadataSafetyV110.requireAvailable("))
        assertTrue(displayBody.contains("return cursor.use {"))
        assertFalse(displayBody.contains(")?.use {"))
        assertTrue(verifyBody.contains("catch (error: OutboxProviderMetadataUnavailableException)"))
        assertTrue(verifyBody.contains("delete(context, documentUri)"))
        assertTrue(verifyBody.contains("throw error"))
        assertTrue(verifyBody.indexOf("catch (error: OutboxProviderMetadataUnavailableException)") < verifyBody.indexOf("OutboxProviderNameSafetyV107.isExact"))
    }

    @Test
    fun metadataUnavailableUsesNormalRetryPath() {
        val source = deliverySource()
        val processBody = functionBody(source, "    fun process(limit: Int = 100)", "    fun retryFailed(): Int")
        val error: Throwable = OutboxProviderMetadataUnavailableException("size")

        assertFalse(error is OutboxDestinationCollisionException)
        assertFalse(error is SecurityException)
        assertTrue(processBody.contains("markDeliveryFailure(record, message, forcePermanent = collision)"))
        assertTrue(processBody.contains("retryRecommended = !permissionLost && !collision && !permanent"))
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 144"))
        assertTrue(gradle.contains("versionName = \"1.14.0-dev.1\""))

        val design = File(root, "docs/V1.10_SAF_PROVIDER_METADATA_UNAVAILABLE_SAFETY.md")
        val notes = File(root, "docs/V1.10_RELEASE_NOTES.md")
        val workflow = File(root, ".github/workflows/build-apk.yml")
        assertTrue(design.isFile)
        assertTrue(notes.isFile)
        assertTrue(workflow.isFile)

        val workflowText = workflow.readText()
        assertTrue(workflowText.contains("DRIVE_SAF_METADATA_QUERY_NULL_BLOCKS_COMMIT=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_METADATA_UNAVAILABLE_RETRYABLE=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_UNVERIFIED_APP_OBJECT_CLEANUP=true"))
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
