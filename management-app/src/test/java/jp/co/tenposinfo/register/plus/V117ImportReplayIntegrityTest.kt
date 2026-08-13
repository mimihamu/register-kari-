package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V117ImportReplayIntegrityTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "management-app").isDirectory) current else current.parentFile
    }

    @Test
    fun logicalDuplicateGuardCoversBusinessEnvelopeButNotTransportMetadata() {
        val source = File(root, "management-app/src/main/java/jp/co/tenposinfo/register/plus/ManagementDatabase.kt").readText()
        val start = source.indexOf("private fun createImportedJournalReplayGuardV117")
        val end = source.indexOf("private fun createIndexes", start)
        val guard = source.substring(start, end)

        assertTrue(guard.contains("trg_v117_imported_journal_identity_guard"))
        assertTrue(guard.contains("existing.duplicate_import_key = NEW.duplicate_import_key"))
        listOf(
            "schema_version",
            "minimum_reader_version",
            "duplicate_key_version",
            "event_id",
            "event_type",
            "store_id",
            "terminal_id",
            "business_date",
            "aggregate_id",
            "occurred_at",
            "payload_schema",
            "payload_json",
            "total_amount",
        ).forEach { field -> assertTrue("missing $field", guard.contains("existing.$field")) }

        assertFalse(guard.contains("existing.source_name"))
        assertFalse(guard.contains("existing.source_uri"))
        assertFalse(guard.contains("existing.raw_json"))
        assertFalse(guard.contains("existing.imported_at"))
        assertFalse(guard.contains("existing.import_run_id"))
        assertTrue(source.contains("const val DATABASE_VERSION = 5"))
    }

    @Test
    fun v118DeterministicConflictIsQuarantinedInsideImportTransaction() {
        val policy = File(root, "management-app/src/main/java/jp/co/tenposinfo/register/plus/FolderImportRepository.kt").readText()
        val repository = File(root, "management-app/src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportRepository.kt").readText()

        assertTrue(policy.contains("SalesJournalReplayDecisionV118"))
        assertTrue(policy.contains("SalesJournalReplayConflictPolicyV118"))
        assertTrue(policy.contains("SalesJournalReplayDecisionV118.NEW"))
        assertTrue(policy.contains("SalesJournalReplayDecisionV118.IDENTICAL"))
        assertTrue(policy.contains("SalesJournalReplayDecisionV118.CONFLICT"))
        assertTrue(policy.contains("duplicate_import_key=?"))
        listOf(
            "schema_version",
            "minimum_reader_version",
            "duplicate_key_version",
            "event_id",
            "event_type",
            "store_id",
            "terminal_id",
            "business_date",
            "aggregate_id",
            "occurred_at",
            "payload_schema",
            "payload_json",
            "total_amount",
        ).forEach { field -> assertTrue("missing v1.18 compare field $field", policy.contains("\"$field\"")) }

        val decision = repository.indexOf("SalesJournalReplayConflictPolicyV118.decide")
        val insert = repository.indexOf("val rowId = insertEnvelope", decision)
        val conflict = repository.indexOf("SalesJournalReplayDecisionV118.CONFLICT", decision)
        val rejection = repository.indexOf("code = ImportRejectionCode.DUPLICATE_KEY_MISMATCH", conflict)
        assertTrue(decision >= 0)
        assertTrue(insert > decision)
        assertTrue(conflict > decision)
        assertTrue(rejection > conflict)
        assertTrue(repository.indexOf("rejected += 1", rejection) > rejection)
        assertTrue(repository.contains("STATUS_COMPLETED_WITH_ERRORS"))
    }

    @Test
    fun driveFingerprintIsRecordedOnlyAfterLogicalImportTransaction() {
        val source = File(root, "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt").readText()
        val apply = source.indexOf("SalesJournalImportRepository(database).importDocuments(documents)")
        val fingerprint = source.indexOf("processed.forEach { recordFingerprint(it.remote, it.sha256) }")

        assertTrue(apply >= 0)
        assertTrue(fingerprint > apply)
    }

    @Test
    fun plusVersionTracksCurrentSyncIntegrityRelease() {
        val build = File(root, "management-app/build.gradle.kts").readLines()
            .map(String::trim)
            .filterNot { it.startsWith("//") }
            .joinToString("\n")

        assertTrue(build.contains("versionCode = 17"))
        assertTrue(build.contains("versionName = \"0.17.0-dev.1\""))
    }
}
