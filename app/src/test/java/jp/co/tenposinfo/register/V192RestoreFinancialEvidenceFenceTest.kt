package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.92: restored backups must never erase unresolved terminal/draft evidence. */
class V192RestoreFinancialEvidenceFenceTest {
    private fun code(name: String): String =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test fun noPaymentEvidenceAllowsOrdinaryRestore() {
        assertFalse(RestoreFinancialEvidenceV192.mustBlock(0L, 0L))
    }

    @Test fun unknownExternalPaymentOrOrphanedDraftMustBlock() {
        assertTrue(RestoreFinancialEvidenceV192.mustBlock(1L, 0L))
        assertTrue(RestoreFinancialEvidenceV192.mustBlock(0L, 1L))
        assertTrue(RestoreFinancialEvidenceV192.mustBlock(2L, 4L))
        assertTrue(RestoreFinancialEvidenceV192.mustBlock(-1L, 0L))
        assertTrue(RestoreFinancialEvidenceV192.mustBlock(0L, -1L))
    }

    @Test fun bothRestoreReservationPathsCheckBeforeStaging() {
        val coordinator = code("DatabaseRecoveryIntegrityV116.kt")
            .substringAfter("internal object RestoreReservationCoordinatorV116")
        assertTrue(coordinator.contains("RestoreFinancialEvidenceV192.requireSafeToReplace("))
        assertTrue(coordinator.indexOf("RestoreFinancialEvidenceV192.requireSafeToReplace(") <
            coordinator.indexOf("PendingRestoreWriteFenceV116.install(appContext)"))

        val direct = code("DataProtection.kt")
            .substringAfter("    fun stageRestore(\n        fileName: String,\n        managerPin: String,\n        migrationRequest:")
            .substringBefore("    fun cancelPendingRestore(")
        assertTrue(direct.contains("RestoreFinancialEvidenceV192.requireSafeToReplace("))
        assertTrue(direct.indexOf("RestoreFinancialEvidenceV192.requireSafeToReplace(") <
            direct.indexOf("writeRestorePlan("))
    }

    @Test fun startupChecksOldDbBeforeSnapshotAndReplace() {
        val restore = code("DataRestoreBootstrapV086.kt")
        val apply = restore.substringAfter("internal object PendingRestoreApplierV086 {")
            .substringBefore("    private fun verifyRestoredDatabase(")
        assertTrue(apply.contains("RestoreFinancialEvidenceV192.requireSafeToReplace(database)"))
        assertTrue(apply.indexOf("RestoreFinancialEvidenceV192.requireSafeToReplace(database)") <
            apply.indexOf("RestoreRollbackSafetyV086.createVerifiedSnapshot(database, restoreDir)"))
        assertTrue(apply.indexOf("RestoreFinancialEvidenceV192.requireSafeToReplace(database)") <
            apply.indexOf("DataProtectionManager.atomicReplace(pending, database)"))
        assertTrue(apply.contains("return failWithoutReplacement("))
    }

    @Test fun guardIsReadOnlyAndChecksBothTablesWithLegacyFallback() {
        val guard = code("RestoreFinancialEvidenceV192.kt")
        assertTrue(guard.contains("SQLiteDatabase.OPEN_READONLY"))
        assertTrue(guard.contains("payment_external_pending_v188"))
        assertTrue(guard.contains("payment_draft_allocations"))
        assertTrue(guard.contains("if (!exists) return 0L"))
        assertFalse(guard.contains("db.delete("))
        assertFalse(guard.contains("db.insert("))
        assertFalse(guard.contains("db.execSQL("))
    }
}
