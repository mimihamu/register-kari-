package jp.co.tenposinfo.register

import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V109SafProviderQueryUnavailableSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun availableProviderQueryValuePassesThrough() {
        val marker = Any()
        assertTrue(OutboxProviderQuerySafetyV109.requireAvailable("sale.json", marker) === marker)
    }

    @Test
    fun unavailableProviderQueryIsTransientIoFailureNotCollision() {
        val error = try {
            OutboxProviderQuerySafetyV109.requireAvailable<Any>("sale.json", null)
            fail("null provider query must fail")
            return
        } catch (error: OutboxProviderQueryUnavailableException) {
            error
        }
        assertTrue(error is IOException)
        assertFalse(error is OutboxDestinationCollisionException)
        assertTrue(error.message.orEmpty().contains("書込みせず再試行"))
        assertTrue(error.message.orEmpty().contains("sale.json"))
    }

    @Test
    fun findChildRequiresNonNullCursorBeforeScanningOrReturningMissing() {
        val source = deliverySource()
        val body = functionBody(source, "    fun findChild(", "    fun createFile(")

        assertTrue(body.contains("OutboxProviderQuerySafetyV109.requireAvailable("))
        assertTrue(body.contains("resolver.query("))
        assertTrue(body.contains("cursor.use { cursor ->"))
        assertFalse(body.contains(")?.use { cursor ->"))
        assertTrue(body.contains("return matched"))
    }

    @Test
    fun queryUnavailablePathCannotCreateDeleteRenameOrWriteInsideLookup() {
        val source = deliverySource()
        val body = functionBody(source, "    fun findChild(", "    fun createFile(")

        assertFalse(body.contains("createDocument("))
        assertFalse(body.contains("deleteDocument("))
        assertFalse(body.contains("renameDocument("))
        assertFalse(body.contains("openOutputStream("))
    }

    @Test
    fun transientQueryFailureUsesExistingRetryPath() {
        val source = deliverySource()
        val processBody = functionBody(source, "    fun process(limit: Int = 100)", "    fun retryFailed(): Int")

        assertTrue(processBody.contains("val collision = error is OutboxDestinationCollisionException"))
        assertTrue(processBody.contains("markDeliveryFailure(record, message, forcePermanent = collision)"))
        assertTrue(processBody.contains("retryRecommended = !permissionLost && !collision && !permanent"))
        val queryError: Throwable = OutboxProviderQueryUnavailableException("sale.json")
        assertFalse(queryError is OutboxDestinationCollisionException)
        assertFalse(queryError is SecurityException)
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 144"))
        assertTrue(gradle.contains("versionName = \"1.14.0-dev.1\""))

        val design = File(root, "docs/V1.09_SAF_PROVIDER_QUERY_UNAVAILABLE_SAFETY.md")
        val notes = File(root, "docs/V1.09_RELEASE_NOTES.md")
        val workflow = File(root, ".github/workflows/build-apk.yml")
        assertTrue(design.isFile)
        assertTrue(notes.isFile)
        assertTrue(workflow.isFile)

        val workflowText = workflow.readText()
        assertTrue(workflowText.contains("DRIVE_SAF_QUERY_NULL_BLOCKS_WRITE=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_QUERY_UNAVAILABLE_RETRYABLE=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_QUERY_NULL_NOT_MISSING=true"))
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
