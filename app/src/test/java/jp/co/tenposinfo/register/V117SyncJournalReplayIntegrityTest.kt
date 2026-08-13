package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V117SyncJournalReplayIntegrityTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun journalReplayGuardCoversAllImmutableBusinessFields() {
        val source = File(root, "app/src/main/java/jp/co/tenposinfo/register/SyncJournalReplayIntegrityV117.kt").readText()

        assertTrue(source.contains("trg_v117_sales_journal_identity_guard"))
        assertTrue(source.contains("existing.event_id = NEW.event_id"))
        assertTrue(source.contains("existing.business_date"))
        assertTrue(source.contains("existing.event_type"))
        assertTrue(source.contains("existing.aggregate_id"))
        assertTrue(source.contains("existing.payload_json"))
        assertTrue(source.contains("existing.created_at"))
        assertTrue(source.contains("SYNC_JOURNAL_EVENT_ID_CONTENT_MISMATCH"))
        assertTrue(source.contains("trg_v117_sync_outbox_destination_guard"))
        assertTrue(source.contains("SYNC_OUTBOX_EVENT_ID_DESTINATION_MISMATCH"))
    }

    @Test
    fun guardIsInstalledBeforeJournalOutboxBootstrap() {
        val provider = File(root, "app/src/main/java/jp/co/tenposinfo/register/SyncJournalReplayIntegrityBootstrapProviderV117.kt").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(provider.contains("SyncJournalReplayIntegrityV117.ensure(database.writableDatabase)"))
        assertTrue(manifest.contains(".SyncJournalReplayIntegrityBootstrapProviderV117"))
        assertTrue(manifest.contains("android:initOrder=\"140\""))
        assertTrue(manifest.indexOf(".SyncJournalReplayIntegrityBootstrapProviderV117") < manifest.indexOf(".JournalOutboxBootstrapProvider"))
    }

    @Test
    fun v117ReleaseEvidenceExists() {
        val build = File(root, "app/build.gradle.kts").readLines()
            .map(String::trim)
            .filterNot { it.startsWith("//") }
            .joinToString("\n")
        assertTrue(build.contains("versionCode = 147"))
        assertTrue(build.contains("versionName = \"1.17.0-dev.1\""))
        assertTrue(File(root, "docs/V1.17_SYNC_REPLAY_INTEGRITY.md").isFile)
        assertTrue(File(root, "docs/V1.17_RELEASE_NOTES.md").isFile)
    }
}
