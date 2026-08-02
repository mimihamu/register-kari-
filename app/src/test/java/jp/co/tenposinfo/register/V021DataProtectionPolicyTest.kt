package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V021DataProtectionPolicyTest {
    @Test fun manifestRoundTripPreservesHashAndCounts() {
        val source = BackupManifest(createdAt = 123456789L, appVersion = "0.21.0-dev.1", databaseUserVersion = 4, databaseSha256 = "a".repeat(64), tableCounts = mapOf("sales" to 10L, "sale_items" to 24L))
        assertEquals(source, BackupManifestCodec.decode(BackupManifestCodec.encode(source)))
    }
    @Test fun unsafeBackupNameIsRejected() {
        assertTrue(runCatching { BackupFilePolicy.requireSafe("TSUGUREGI_backup_20260802_120000.tgbak") }.isSuccess)
        assertTrue(runCatching { BackupFilePolicy.requireSafe("../register.db.tgbak") }.isFailure)
        assertTrue(runCatching { BackupFilePolicy.requireSafe("backup.zip") }.isFailure)
    }
    @Test fun restoreRequiresClosedAndDrainedRegister() {
        assertTrue(DataRestorePolicy.mayStage(RestoreBlockers()))
        val blocked = RestoreBlockers(activeBusinessSessions = 1, heldTickets = 2, pendingSalePrintJobs = 1, pendingOutbox = 3)
        assertFalse(DataRestorePolicy.mayStage(blocked)); assertEquals(4, DataRestorePolicy.reasons(blocked).size)
    }
    @Test fun requiredTableSetIncludesFinancialAndRecoveryData() {
        assertTrue("sales" in DataProtectionTablePolicy.requiredTables); assertTrue("sale_payments" in DataProtectionTablePolicy.requiredTables)
        assertTrue("reversal_transactions" in DataProtectionTablePolicy.requiredTables); assertTrue("business_sessions" in DataProtectionTablePolicy.requiredTables)
        assertTrue("sync_outbox" in DataProtectionTablePolicy.requiredTables); assertTrue("operation_audit" in DataProtectionTablePolicy.requiredTables)
    }
}
