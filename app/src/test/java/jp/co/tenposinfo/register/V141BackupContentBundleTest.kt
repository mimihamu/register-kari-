package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V141BackupContentBundleTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun typedPreferenceCodecRoundTripsEverySharedPreferencesValueType() {
        val original: Map<String, Any> = linkedMapOf(
            "text" to "店舗\n固定文=OK",
            "enabled" to true,
            "count" to 37,
            "timestamp" to 9_876_543_210L,
            "ratio" to 1.25f,
            "labels" to setOf("内税", "外税", "非課税"),
        )
        val encoded = BackupPreferenceCodecV136.encode(original)
        val decoded = BackupPreferenceCodecV136.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun typedPreferenceCodecRejectsDuplicateKeys() {
        val encoded = BackupPreferenceCodecV136.encode(mapOf("same" to "first")).toString(Charsets.UTF_8)
        val record = encoded.lineSequence().drop(1).first { it.isNotBlank() }
        val duplicate = (encoded.trimEnd() + "\n" + record + "\n").toByteArray(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            BackupPreferenceCodecV136.decode(duplicate)
        }
    }

    @Test
    fun allowlistCoversOperationalSettingsAndExcludesRuntimeAuthorizationState() {
        val names = BackupContentBundleV136.PREFERENCE_NAMES.toSet()
        assertTrue("initial_release_settings_v135" in names)
        assertTrue("tax_invoice_settings" in names)
        assertTrue("document_print_settings_v136" in names)
        assertTrue("customer_display_server" in names)
        assertTrue("receipt_stamp_settings_v136" in names)
        assertTrue("auto_backup_settings_v2" in names)
        assertTrue("drive_sync_foundation" in names)
        assertTrue("outbox_delivery_settings_v1" in names)
        assertTrue("external_backup_settings_v1" in names)

        assertFalse("tsuguregi_google_drive_account" in names)
        assertFalse("tsuguregi_drive_api_upload_status" in names)
        assertFalse("outbox_delivery_status_v1" in names)
        assertFalse("external_backup_status_v1" in names)
        assertFalse("operator_session" in names)
    }

    @Test
    fun productionBackupWritesVerifiesAndStagesEncryptedContentBundle() {
        val source = File(root, "app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt").readText()
        assertTrue(source.contains("BackupContentBundleV136.writeTo(appContext, zip, manifest.createdAt)"))
        assertTrue(source.contains("BackupContentBundleV136.extractAndVerify(innerArchive"))
        assertTrue(source.contains("content_bundle\" to if (hasContent) BackupContentBundleV136.FORMAT else \"LEGACY_DB_ONLY\""))
        assertTrue(source.contains("BackupContentBundleV136.CONTENT_MANIFEST_ENTRY in contentNames"))
    }

    @Test
    fun startupRestoreKeepsExternalContentInsideDatabaseRollbackBoundary() {
        val source = File(root, "app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt").readText()
        val finalVerify = source.indexOf("DatabaseRecoveryIntegrityV116.verifyFinal(context)")
        val applyContent = source.indexOf("BackupContentBundleV136.applyStagedWithRollback")
        val deletePlan = source.indexOf("planFile.delete()", applyContent)
        assertTrue(finalVerify >= 0)
        assertTrue(applyContent > finalVerify)
        assertTrue(deletePlan > applyContent)
        assertTrue(source.contains("contentRollback?.restore()"))
        assertTrue(source.contains("BKP-003復元contentがありません"))
        assertTrue(source.contains("recoverStaleProcessing(Long.MAX_VALUE)"))
        assertTrue(source.contains("requeueStaged()"))
    }

    @Test
    fun formalContinuationStateLivesInDatabaseOrExplicitContentBundle() {
        val sync = File(root, "app/src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt").readText()
        val catalog = File(root, "app/src/main/java/jp/co/tenposinfo/register/DynamicCatalogRuntime.kt").readText()
        val admin = File(root, "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsStore.kt").readText()
        assertTrue(sync.contains("sync_outbox"))
        assertTrue(sync.contains("sync_runtime_settings"))
        assertTrue(sync.contains("OutboxPayloadAssembler.build"))
        assertTrue(catalog.contains("menu_revisions"))
        assertTrue(catalog.contains("menu_revision_products"))
        assertTrue(admin.contains("printer_settings"))
    }
}
