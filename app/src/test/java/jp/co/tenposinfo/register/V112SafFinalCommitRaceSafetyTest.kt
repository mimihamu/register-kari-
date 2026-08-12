package jp.co.tenposinfo.register

import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V112SafFinalCommitRaceSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun committedDocumentIdentityMustMatchVisibleFinal() {
        OutboxFinalCommitRaceSafetyV112.requireSameDocument("sale.json", "doc-1", "doc-1")
        val error = try {
            OutboxFinalCommitRaceSafetyV112.requireSameDocument("sale.json", "doc-1", "doc-2")
            fail("different visible document must collide")
            return
        } catch (error: OutboxFinalCommitIdentityMismatchException) {
            error
        }
        assertTrue(error is OutboxDestinationCollisionException)
        assertTrue(error.message.orEmpty().contains("別の同名ファイル"))
        assertTrue(error.message.orEmpty().contains("既存ファイルは保護"))
    }

    @Test
    fun finalVisibilityFailureIsRetryableNotCollision() {
        val error: Throwable = OutboxFinalCommitVisibilityUnavailableException("sale.json")
        assertTrue(error is IOException)
        assertFalse(error is OutboxDestinationCollisionException)
        assertTrue(error.message.orEmpty().contains("成功扱いせず再試行"))
    }

    @Test
    fun verifiedPartialRechecksFinalBeforeRenameOrCopy() {
        val body = deliverOneBody()
        val partialSha = body.indexOf("require(partialSha == localSha256)")
        val raceLookup = body.indexOf("val preCommitExisting = OutboxExternalDocumentProvider.findChild(", partialSha)
        val rename = body.indexOf("val renamed = OutboxExternalDocumentProvider.rename", raceLookup)

        assertTrue(partialSha >= 0)
        assertTrue(raceLookup > partialSha)
        assertTrue(rename > raceLookup)
        val raceBlock = body.substring(raceLookup, rename)
        assertTrue(raceBlock.contains("OutboxDestinationCollisionSafetyV106.decide("))
        assertTrue(raceBlock.contains("OutboxExistingDestinationDecisionV106.ALREADY_SENT"))
        assertTrue(raceBlock.contains("OutboxExistingDestinationDecisionV106.COLLISION"))
        assertTrue(raceBlock.contains("OutboxExternalDocumentProvider.delete(appContext, partialUri)"))
        assertFalse(raceBlock.contains("delete(appContext, preCommitExisting.uri)"))
    }

    @Test
    fun committedFinalIsRecheckedBeforeSuccessFlag() {
        val body = deliverOneBody()
        val finalSha = body.indexOf("require(finalSha == localSha256)")
        val visibilityCheck = body.indexOf("val visibleFinal = OutboxExternalDocumentProvider.findChild(", finalSha)
        val identityCheck = body.indexOf("OutboxFinalCommitRaceSafetyV112.requireSameDocument(", visibilityCheck)
        val committed = body.indexOf("externalCommitted = true", identityCheck)

        assertTrue(finalSha >= 0)
        assertTrue(visibilityCheck > finalSha)
        assertTrue(identityCheck > visibilityCheck)
        assertTrue(committed > identityCheck)
        val postCommitBlock = body.substring(visibilityCheck, committed)
        assertTrue(postCommitBlock.contains("OutboxFinalCommitVisibilityUnavailableException(fileName)"))
        assertTrue(postCommitBlock.contains("DocumentsContract.getDocumentId(finalUri)"))
        assertTrue(postCommitBlock.contains("DocumentsContract.getDocumentId(visibleFinal.uri)"))
    }

    @Test
    fun failedPostCommitVerificationDeletesOnlyAppOwnedCommittedUri() {
        val body = deliverOneBody()
        val catchBlock = body.substring(body.indexOf("} catch (error: Throwable)"))
        assertTrue(catchBlock.contains("committedUri?.let { OutboxExternalDocumentProvider.delete(appContext, it) }"))
        assertTrue(catchBlock.contains("OutboxExternalDocumentProvider.delete(appContext, partialUri)"))
        assertFalse(catchBlock.contains("delete(appContext, visibleFinal.uri)"))
        assertFalse(catchBlock.contains("delete(appContext, preCommitExisting.uri)"))
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 142"))
        assertTrue(gradle.contains("versionName = \"1.12.0-dev.1\""))

        val design = File(root, "docs/V1.12_SAF_FINAL_COMMIT_RACE_SAFETY.md")
        val notes = File(root, "docs/V1.12_RELEASE_NOTES.md")
        val workflow = File(root, ".github/workflows/build-apk.yml")
        assertTrue(design.isFile)
        assertTrue(notes.isFile)
        assertTrue(workflow.isFile)

        val workflowText = workflow.readText()
        assertTrue(workflowText.contains("DRIVE_SAF_FINAL_PRECOMMIT_RECHECK=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_FINAL_POSTCOMMIT_IDENTITY=true"))
        assertTrue(workflowText.contains("DRIVE_SAF_FINAL_RACE_EXISTING_NONDESTRUCTIVE=true"))
        assertTrue(workflowText.contains("REAL_ACCOUNT_GOOGLE_DRIVE_VERIFICATION=deferred-final-acceptance"))
        assertTrue(notes.readText().contains("最終総合実機試験"))
    }

    private fun deliverOneBody(): String {
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/OutboxExternalDelivery.kt",
        ).readText()
        val start = source.indexOf("    private fun deliverOne(")
        assertTrue(start >= 0)
        val end = source.indexOf("    private fun copyPartialToFinal(", start)
        assertTrue(end > start)
        return source.substring(start, end)
    }
}
