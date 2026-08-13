package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V115BackendDataAtomicityTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun v115AtomicityGuardsRemainPresent() {
        val register = source("RegisterDatabase.kt")
        val receipt = source("Receipt.kt")
        val advanced = source("AdvancedOperationsStore.kt")
        assertTrue(register.contains("fun claimNextPrintableJob(): PrintJobRecord?"))
        assertTrue(register.contains("fun claimPrintJob(jobId: Long): PrintJobRecord?"))
        assertTrue(register.contains("id = ? AND status = ? AND attempt_count = ?"))
        assertTrue(register.contains("if (current.status == PrintJobStatus.COMPLETED)"))
        assertTrue(receipt.contains("database.claimNextPrintableJob()"))
        assertTrue(advanced.contains("requireReversalSnapshotCurrent(selected, type)"))
        assertTrue(advanced.contains("requireSessionStillOpen(session.id)"))
        assertTrue(advanced.contains("PrinterRetrySafety.classify(error)"))
    }

    @Test
    fun v116RecoveryGuardsRemainPresent() {
        val restore = source("DataRestoreBootstrapV086.kt")
        val gate = source("DatabaseRecoveryIntegrityV116.kt")
        val activity = source("DataProtectionActivity.kt")
        assertTrue(restore.contains("PRAGMA wal_checkpoint(TRUNCATE)"))
        assertTrue(restore.contains("wal.length() == 0L"))
        assertTrue(restore.contains("DatabaseRecoveryIntegrityV116.migrateAndVerify(context)"))
        assertTrue(restore.contains("DatabaseRecoveryIntegrityV116.verifyFinal(context)"))
        assertTrue(restore.contains("PendingRestoreWriteFenceV116.remove(database)"))
        assertTrue(restore.contains("PRAGMA foreign_key_check"))
        assertTrue(gate.contains("DatabaseStartupSchemaBootstrapV085.ensureBeforeUi(appContext)"))
        assertTrue(gate.contains("DataProtectionManager(appContext).diagnose()"))
        assertTrue(gate.contains("AppUpdateDatabaseHealthCheckV089.inspect(appContext)"))
        assertTrue(gate.contains("EXPECTED_DATABASE_USER_VERSION = 4"))
        assertTrue(gate.contains("PendingRestoreWriteFenceV116"))
        assertTrue(gate.contains("CREATE TRIGGER IF NOT EXISTS"))
        assertTrue(gate.contains("RAISE(ABORT"))
        assertTrue(gate.contains("RestoreReservationCoordinatorV116"))
        assertTrue(gate.contains("idx_print_jobs_status"))
        assertTrue(gate.contains("idx_settlement_session"))
        assertTrue(activity.contains("RestoreReservationCoordinatorV116.stage(appContext, manager, file, pin)"))
        assertTrue(activity.contains("RestoreReservationCoordinatorV116.cancel(appContext, manager, pin)"))
    }

    @Test
    fun v116IdentityDocsAndCiArePinned() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        val notes = File(root, "docs/V1.15_RELEASE_NOTES.md").readText()
        val protection = File(root, "docs/V0.21_DATA_PROTECTION.md").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(gradle.contains("versionCode = 146"))
        assertTrue(gradle.contains("versionName = \"1.16.0-dev.1\""))
        assertTrue(notes.contains("v1.16.0-dev.1 DB整合性"))
        assertTrue(notes.contains("復元予約中"))
        assertTrue(notes.contains("最終総合実機試験"))
        assertTrue(protection.contains("v1.16 WAL-safe rollback"))
        assertTrue(protection.contains("復元予約中の書込みフェンス"))
        assertTrue(workflow.contains("DB_RECOVERY_WAL_TRUNCATE_HANDOFF=true"))
        assertTrue(workflow.contains("DB_RECOVERY_MIGRATION_ROLLBACK_BOUNDARY=true"))
        assertTrue(workflow.contains("DB_RECOVERY_FINAL_SCHEMA_GATE=true"))
        assertTrue(workflow.contains("DB_RECOVERY_PENDING_RESTORE_WRITE_FENCE=true"))
    }

    private fun source(name: String): String =
        File(root, "app/src/main/java/jp/co/tenposinfo/register/$name").readText()
}
