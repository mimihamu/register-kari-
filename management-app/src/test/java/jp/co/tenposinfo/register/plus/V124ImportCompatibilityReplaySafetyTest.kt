package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V124ImportCompatibilityReplaySafetyTest {
    @Test
    fun missingOrChangedProcessorSignatureRequiresReplay() {
        val current = SalesJournalProcessorSignatureV124.current()
        assertTrue(current.matches(Regex("[0-9a-f]{64}")))
        assertTrue(SalesJournalProcessorSignatureV124.requiresReplay(null, current))
        assertTrue(SalesJournalProcessorSignatureV124.requiresReplay("", current))
        assertTrue(SalesJournalProcessorSignatureV124.requiresReplay("older", current))
        assertFalse(SalesJournalProcessorSignatureV124.requiresReplay(current, current))
    }

    @Test
    fun processorSignatureCoversDeclaredCompatibilitySurface() {
        val compatibility = File(
            "src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportCompatibilityV124.kt",
        ).readText()
        listOf(
            "RULE_VERSION",
            "SalesJournalImportContract.SCHEMA",
            "SUPPORTED_SCHEMA_VERSION",
            "READER_VERSION",
            "SUPPORTED_DUPLICATE_KEY_VERSION",
            "supportedEventTypes.sorted()",
            "supportedPayloadSchemas.sorted()",
        ).forEach { token -> assertTrue(token, compatibility.contains(token)) }
        assertNotEquals("", SalesJournalProcessorSignatureV124.current())
    }

    @Test
    fun driveAndFolderKeepIndependentSuccessfulSignatures() {
        assertNotEquals(
            SalesJournalImportChannelV124.DRIVE_API.preferenceKey,
            SalesJournalImportChannelV124.COMPATIBILITY_FOLDER.preferenceKey,
        )
    }

    @Test
    fun bothAutomaticImportPathsForceReplayOnCompatibilityChange() {
        val drive = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        val verification = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveSyncVerificationActivity.kt",
        ).readText()

        assertTrue(drive.contains("SalesJournalImportChannelV124.DRIVE_API"))
        assertTrue(drive.contains("requiresFullReplay()"))
        assertTrue(drive.contains("effectiveForceReimport"))
        assertTrue(drive.contains("compatibilityStore.markSuccessful()"))

        assertTrue(verification.contains("SalesJournalImportChannelV124.COMPATIBILITY_FOLDER"))
        assertTrue(verification.contains("effectiveForceRescan"))
        assertTrue(verification.contains("compatibilityStore.markSuccessful()"))
    }

    @Test
    fun unsupportedParserOutcomesRemainAuditedAndManualRetryStillExists() {
        val contract = File(
            "src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportContract.kt",
        ).readText()
        val runtime = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveOperationsRuntime.kt",
        ).readText()

        assertTrue(contract.contains("UNSUPPORTED_VERSION"))
        assertTrue(contract.contains("UNSUPPORTED_EVENT_TYPE"))
        assertTrue(contract.contains("UNSUPPORTED_PAYLOAD_SCHEMA"))
        assertTrue(runtime.contains("class GoogleDriveRejectedRetryService"))
        assertTrue(runtime.contains("fun retry(rejectionId: Long)"))
    }
}
