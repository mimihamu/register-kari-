package jp.co.tenposinfo.register

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V139AutoBackupScheduleTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test
    fun dailyScheduleUsesNextConfiguredHour() {
        val before = ZonedDateTime.of(2026, 8, 24, 2, 0, 0, 0, zone)
        val after = ZonedDateTime.of(2026, 8, 24, 4, 0, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 8, 24, 3, 0, 0, 0, zone).toInstant().toEpochMilli(),
            AutoBackupSettingsPolicy.nextRunMillis(before.toInstant().toEpochMilli(), 3, zone),
        )
        assertEquals(
            ZonedDateTime.of(2026, 8, 25, 3, 0, 0, 0, zone).toInstant().toEpochMilli(),
            AutoBackupSettingsPolicy.nextRunMillis(after.toInstant().toEpochMilli(), 3, zone),
        )
    }

    @Test
    fun legacyDisabledOrWeeklySettingsAreMigratedToMandatoryDailyAndSettlement() {
        val migrated = AutoBackupSettingsPolicy.sanitized(
            AutoBackupSettings(
                periodicEnabled = false,
                cadence = PeriodicBackupCadence.WEEKLY,
                preferredWeekday = 5,
                settlementAutoBackupEnabled = false,
            ),
        )
        assertTrue(migrated.periodicEnabled)
        assertEquals(PeriodicBackupCadence.DAILY, migrated.cadence)
        assertTrue(migrated.settlementAutoBackupEnabled)
        assertEquals(5, migrated.preferredWeekday)
    }

    @Test
    fun formalRetentionDefaultsToThirtyAndRejectsOutsideSevenTo365() {
        assertEquals(30, AutoBackupSettings().retentionGenerations)
        assertThrows(IllegalArgumentException::class.java) {
            AutoBackupSettingsPolicy.validated(AutoBackupSettings(retentionGenerations = 6))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutoBackupSettingsPolicy.validated(AutoBackupSettings(retentionGenerations = 366))
        }
        assertEquals(7, AutoBackupSettingsPolicy.sanitized(AutoBackupSettings(retentionGenerations = 1)).retentionGenerations)
        assertEquals(365, AutoBackupSettingsPolicy.sanitized(AutoBackupSettings(retentionGenerations = 999)).retentionGenerations)
    }

    @Test
    fun generationRetentionDeletesOldestSuccessfulAutomaticBackupsOnly() {
        val automatic = (1L..10L).map { index ->
            BackupRetentionEntry(
                fileName = "auto-$index.tgbak",
                createdAt = index,
                valid = true,
                reason = if (index % 2L == 0L) BackupCreationReason.PERIODIC else BackupCreationReason.Z_SETTLEMENT,
                businessDate = "2026-08-${index.toString().padStart(2, '0')}",
                state = AutoBackupFileState.READY,
                pendingRestore = false,
            )
        }
        val manual = BackupRetentionEntry(
            fileName = "manual.tgbak",
            createdAt = 0L,
            valid = true,
            reason = BackupCreationReason.MANUAL,
            businessDate = null,
            state = AutoBackupFileState.READY,
            pendingRestore = false,
        )
        val deletion = AutoBackupRetentionPolicy.selectGenerationDeletionCandidates(automatic + manual, generations = 7)
        assertEquals(setOf("auto-1.tgbak", "auto-2.tgbak", "auto-3.tgbak"), deletion)
        assertFalse("manual.tgbak" in deletion)
    }

    @Test
    fun capacityReliefNeverSelectsNewestSuccessfulAutomaticGeneration() {
        val entries = (1L..3L).map { index ->
            BackupRetentionEntry(
                fileName = "auto-$index.tgbak",
                createdAt = index,
                valid = true,
                reason = BackupCreationReason.PERIODIC,
                businessDate = null,
                state = AutoBackupFileState.READY,
                pendingRestore = false,
            )
        }
        assertEquals(listOf("auto-1.tgbak", "auto-2.tgbak"), AutoBackupRetentionPolicy.selectCapacityDeletionCandidates(entries))
    }

    @Test
    fun productSourcesEnforceFormalBkp001Contract() {
        val mainRoot = File("src/main/java/jp/co/tenposinfo/register")
        val worker = File(mainRoot, "AutoBackup.kt").readText()
        val settings = File(mainRoot, "AutoBackupSettings.kt").readText()
        val ui = File(mainRoot, "AutoBackupSettingsActivity.kt").readText()

        assertTrue(settings.contains("DEFAULT_BACKUP_RETENTION_GENERATIONS = 30"))
        assertTrue(settings.contains("MIN_BACKUP_RETENTION_GENERATIONS = 7"))
        assertTrue(settings.contains("MAX_BACKUP_RETENTION_GENERATIONS = 365"))
        assertTrue(settings.contains("\"backup.retention\""))
        assertTrue(settings.contains("periodicEnabled = true"))
        assertTrue(settings.contains("cadence = PeriodicBackupCadence.DAILY"))
        assertTrue(settings.contains("settlementAutoBackupEnabled = true"))
        assertTrue(worker.contains("selectGenerationDeletionCandidates"))
        assertTrue(worker.contains("selectCapacityDeletionCandidates"))
        assertTrue(worker.contains("capacityReliefDatabaseBytes"))
        assertTrue(worker.contains("DataProtectionManager(appContext)"))
        assertTrue(worker.contains("manager.createBackup(actorName)"))
        val zMethod = worker.substringAfter("fun enqueueZSettlement(").substringBefore("fun enqueueManualNow(")
        assertFalse(zMethod.contains("DATA_BACKUP_Z_SKIPPED_DISABLED"))
        assertTrue(zMethod.contains("uniqueZWorkName(settlementId)"))
        assertTrue(ui.contains("1日1回の暗号化バックアップは常時有効"))
        assertTrue(ui.contains("Z精算後バックアップは常時有効"))
        assertTrue(ui.contains("バックアップ保持世代（7～365）"))
    }
}
