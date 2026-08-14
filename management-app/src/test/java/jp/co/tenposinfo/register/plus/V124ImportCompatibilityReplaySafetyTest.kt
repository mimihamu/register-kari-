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
    fun compatibilityChangeInvalidatesOnlyTransportFingerprints() {
        val compatibility = File(
            "src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportCompatibilityV124.kt",
        ).readText()
        val database = File(
            "src/main/java/jp/co/tenposinfo/register/plus/ManagementDatabase.kt",
        ).readText()

        assertTrue(database.contains("SalesJournalImportCompatibilityResetV124.ensureCurrent(appContext, db)"))
        assertTrue(compatibility.contains("db.delete(\"drive_sync_files\", null, null)"))
        assertTrue(compatibility.contains("db.delete(\"folder_import_files\", null, null)"))
        assertTrue(compatibility.contains("db.setTransactionSuccessful()"))
        assertTrue(compatibility.contains("preferences.edit().putString(KEY_PROCESSOR_SIGNATURE, currentSignature).commit()"))

        assertFalse(compatibility.contains("db.delete(\"imported_journal\""))
        assertFalse(compatibility.contains("db.delete(\"import_rejections\""))
        assertFalse(compatibility.contains("db.delete(\"import_runs\""))
    }

    @Test
    fun fingerprintResetHappensBeforeSignatureIsPersisted() {
        val compatibility = File(
            "src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportCompatibilityV124.kt",
        ).readText()
        val driveDelete = compatibility.indexOf("db.delete(\"drive_sync_files\", null, null)")
        val folderDelete = compatibility.indexOf("db.delete(\"folder_import_files\", null, null)", driveDelete)
        val transactionSuccess = compatibility.indexOf("db.setTransactionSuccessful()", folderDelete)
        val endTransaction = compatibility.indexOf("db.endTransaction()", transactionSuccess)
        val signatureCommit = compatibility.indexOf(
            "preferences.edit().putString(KEY_PROCESSOR_SIGNATURE, currentSignature).commit()",
            endTransaction,
        )

        assertTrue(driveDelete >= 0)
        assertTrue(folderDelete > driveDelete)
        assertTrue(transactionSuccess > folderDelete)
        assertTrue(endTransaction > transactionSuccess)
        assertTrue(signatureCommit > endTransaction)
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
