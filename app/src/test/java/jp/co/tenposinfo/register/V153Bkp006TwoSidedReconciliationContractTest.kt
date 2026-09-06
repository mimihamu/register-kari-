package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formal v2.5 BKP-006 cross-app acceptance contract.
 *
 * REGISTER rebuilds local unsent work and reconciles it against Drive without importing sales
 * backwards into the register DB. Tsuguregi+ owns the complementary "not yet imported" side:
 * when its durable Changes cursor is absent it performs a full baseline, then advances through
 * Drive Changes, while duplicateImportKey makes replay idempotent.
 */
class V153Bkp006TwoSidedReconciliationContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun source(relativePath: String): String = File(repositoryRoot, relativePath).readText()

    @Test
    fun restoreRebuildCoversUnsentAndPlusCoversUnimportedWithoutRegisterReverseImport() {
        val rebuild = source("app/src/main/java/jp/co/tenposinfo/register/RestoreSyncRebuildV136.kt")
        val restore = source("app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt")
        val registerDrive = source("app/src/main/java/jp/co/tenposinfo/register/GoogleDriveDirectUpload.kt")
        val plusDrive = source("management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt")
        val plusImport = source("management-app/src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportRepository.kt")

        // BKP-006 local unsent reconstruction: journal is authoritative and only missing outbox is rebuilt.
        assertTrue(rebuild.contains("LEFT JOIN sync_outbox"))
        assertTrue(rebuild.contains("WHERE o.event_id IS NULL"))
        assertTrue(rebuild.contains("SyncOutboxStatus.PENDING.name"))
        assertTrue(restore.contains("RestoreSyncRebuildV136.rebuild(database)"))
        assertTrue(restore.contains("GoogleDriveDirectUploadScheduler.enqueueNow(context)"))

        // Drive index reconciliation must be idempotent on the REGISTER upload side.
        assertTrue(registerDrive.contains("propertyQuery(\"duplicateKey\", envelope.duplicateKey)"))
        assertTrue(registerDrive.contains("existing?.appProperties?.get(\"contentSha256\") == sha256"))
        assertTrue(registerDrive.contains("markSuccess(candidate, remote, sha256)"))

        // BKP-006 "not yet imported" side belongs to Tsuguregi+: no cursor means a complete baseline,
        // then the captured start token enters Changes mode so concurrent Drive writes are not lost.
        assertTrue(plusDrive.contains("val persistedChangesToken = if (forceReimport) null else loadChangesPageToken(initialDb)"))
        assertTrue(plusDrive.contains("val baselineChangesToken = if (changesMode) null else client.getStartPageToken()"))
        assertTrue(plusDrive.contains("client.listJournalPage(pageToken)"))
        assertTrue(plusDrive.contains("client.listJournalChangesPage(checkNotNull(pageToken))"))
        assertTrue(plusDrive.contains("pageToken = baselineChangesToken"))
        assertTrue(plusDrive.contains("persistChangesPageToken(pageDb, page.newStartPageToken)"))

        // Re-reading Drive must not duplicate business rows.
        assertTrue(plusImport.contains("SalesJournalReplayConflictPolicyV118.decide"))
        assertTrue(plusImport.contains("SalesJournalReplayDecisionV118.IDENTICAL"))
        assertTrue(plusImport.contains("SQLiteDatabase.CONFLICT_IGNORE"))
        assertTrue(plusImport.contains("duplicateImportKey"))

        // Guard the architecture: REGISTER must never reverse-import Drive sales into its business DB.
        assertTrue(!rebuild.contains("download("))
        assertTrue(!rebuild.contains("importDocuments"))
    }
}
