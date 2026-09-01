package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V144Bkp006SyncRebuildTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) File(current, "app") else current
    }

    @Test
    fun originalOutboxIdentityWinsAfterSpareTerminalMigration() {
        val old = OutboxIdentitySnapshotV136.choose(
            sourceStoreId = "STORE-A",
            sourceTerminalId = "TERMINAL-OLD",
            sourceGeneration = 4,
            fallback = SalesJournalIdentity("STORE-A", "TERMINAL-NEW", 5),
        )
        assertEquals("STORE-A", old.storeId)
        assertEquals("TERMINAL-OLD", old.terminalId)
        assertEquals(4L, old.generation)
    }

    @Test
    fun incompleteIdentitySnapshotFailsBackToPersistedRuntimeIdentity() {
        val current = SalesJournalIdentity("STORE-A", "TERMINAL-NEW", 5)
        assertEquals(current, OutboxIdentitySnapshotV136.choose("STORE-A", null, 4, current))
        assertEquals(current, OutboxIdentitySnapshotV136.choose("STORE-A", "TERMINAL-OLD", 0, current))
    }

    @Test
    fun sourceWiresRestoreRebuildBeforeTerminalSwitchAndPreservesAckContract() {
        fun source(name: String) = File(root, "src/main/java/jp/co/tenposinfo/register/$name").readText()
        val business = source("BusinessSyncFoundation.kt")
        val rebuild = source("RestoreSyncRebuildV136.kt")
        val bootstrap = source("DataRestoreBootstrapV086.kt")
        val drive = source("GoogleDriveDirectUpload.kt")

        assertTrue(business.contains("source_store_id"))
        assertTrue(business.contains("source_terminal_id"))
        assertTrue(business.contains("source_generation"))
        assertTrue(business.contains("OutboxIdentitySnapshotV136.resolve(db, record.eventId)"))
        assertTrue(rebuild.contains("LEFT JOIN sync_outbox"))
        assertTrue(rebuild.contains("WHERE o.event_id IS NULL"))
        assertTrue(rebuild.contains("SyncOutboxStatus.PENDING.name"))
        assertTrue(rebuild.contains("drive_api_uploads"))
        assertTrue(rebuild.contains("status='SUCCEEDED'"))
        assertTrue(rebuild.contains("file_id IS NOT NULL"))
        assertTrue(!rebuild.contains("GoogleDriveService.read"))
        val rebuildIndex = bootstrap.indexOf("RestoreSyncRebuildV136.rebuild(database)")
        val migrationIndex = bootstrap.indexOf("RestoreTerminalMigrationV136.apply(database, plan)")
        assertTrue(rebuildIndex >= 0)
        assertTrue(migrationIndex > rebuildIndex)
        assertTrue(bootstrap.contains("ack-preserved"))
        assertTrue(bootstrap.contains("documentId-preserved"))
        assertTrue(drive.contains("duplicateKey"))
        assertTrue(drive.contains("file_id"))
        assertTrue(drive.contains("JournalOutboxStore(applicationContext).use { it.stagePending(100) }"))
        assertTrue(bootstrap.contains("GoogleDriveDirectUploadScheduler.enqueueNow(context)"))
    }
}
