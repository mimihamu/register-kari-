package jp.co.tenposinfo.register

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V033PeriodicBackupSettingsTest {
    private fun source(name: String) =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun nextRunUsesTodayBeforePreferredHourAndTomorrowAfterIt() {
        val before = ZonedDateTime.of(2026, 8, 4, 2, 30, 0, 0, tokyo)
        val beforeNext = AutoBackupSettingsPolicy.nextRunMillis(
            before.toInstant().toEpochMilli(),
            preferredHour = 3,
            zoneId = tokyo,
        )
        assertEquals(
            ZonedDateTime.of(2026, 8, 4, 3, 0, 0, 0, tokyo).toInstant().toEpochMilli(),
            beforeNext,
        )

        val atHour = ZonedDateTime.of(2026, 8, 4, 3, 0, 0, 0, tokyo)
        val afterNext = AutoBackupSettingsPolicy.nextRunMillis(
            atHour.toInstant().toEpochMilli(),
            preferredHour = 3,
            zoneId = tokyo,
        )
        assertEquals(
            ZonedDateTime.of(2026, 8, 5, 3, 0, 0, 0, tokyo).toInstant().toEpochMilli(),
            afterNext,
        )
    }

    @Test
    fun settingsValidationRejectsUnsafeRetentionAndHourValues() {
        assertThrows(IllegalArgumentException::class.java) {
            AutoBackupSettingsPolicy.validated(AutoBackupSettings(preferredHour = 24))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutoBackupSettingsPolicy.validated(AutoBackupSettings(zRetentionBusinessDays = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutoBackupSettingsPolicy.validated(AutoBackupSettings(monthlyRetentionMonths = 37))
        }
        val sanitized = AutoBackupSettingsPolicy.sanitized(
            AutoBackupSettings(
                preferredHour = -1,
                zRetentionBusinessDays = 999,
                monthlyRetentionMonths = -10,
            ),
        )
        assertEquals(0, sanitized.preferredHour)
        assertEquals(MAX_Z_BACKUP_BUSINESS_DAYS, sanitized.zRetentionBusinessDays)
        assertEquals(MIN_MONTHLY_BACKUP_MONTHS, sanitized.monthlyRetentionMonths)
    }

    @Test
    fun failureNotificationsOnlyFireForFailuresAndLowStorage() {
        assertTrue(AutoBackupFailureNotificationPolicy.shouldNotify(true, AutoBackupResultState.FAILED))
        assertTrue(AutoBackupFailureNotificationPolicy.shouldNotify(true, AutoBackupResultState.SKIPPED_LOW_STORAGE))
        assertFalse(AutoBackupFailureNotificationPolicy.shouldNotify(true, AutoBackupResultState.CREATED))
        assertFalse(AutoBackupFailureNotificationPolicy.shouldNotify(false, AutoBackupResultState.FAILED))
    }

    @Test
    fun periodicInputUsesPeriodicReasonAndSystemActor() {
        val data = AutoBackupScheduler.periodicInputData()
        assertEquals(BackupCreationReason.PERIODIC, AutoBackupScheduler.reason(data))
        assertEquals("システム（定期）", AutoBackupScheduler.actorName(data))
        assertNull(AutoBackupScheduler.businessDate(data))
        assertNull(AutoBackupScheduler.settlementId(data))
    }

    @Test
    fun configurableRetentionValuesAreApplied() {
        fun entry(name: String, date: String, createdAt: Long) = BackupRetentionEntry(
            fileName = name,
            createdAt = createdAt,
            valid = true,
            reason = BackupCreationReason.Z_SETTLEMENT,
            businessDate = date,
            state = AutoBackupFileState.READY,
            pendingRestore = false,
        )
        val entries = listOf(
            entry("z-1.tgbak", "2026-08-01", 1L),
            entry("z-2.tgbak", "2026-08-02", 2L),
            entry("z-3.tgbak", "2026-08-03", 3L),
        )
        val deletion = AutoBackupRetentionPolicy.selectDeletionCandidates(
            entries = entries,
            zBusinessDays = 2,
            monthlyMonths = 12,
        )
        assertEquals(setOf("z-1.tgbak"), deletion)
    }

    @Test
    fun schedulerAndUiPreserveZSettlementBackupAndAddPeriodicOperation() {
        val settings = source("AutoBackupSettings.kt")
        val worker = source("AutoBackup.kt")
        val notification = source("AutoBackupNotification.kt")
        val activity = source("AutoBackupSettingsActivity.kt")
        val protection = source("DataProtectionActivity.kt")
        val application = source("RegisterApplication.kt")
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(settings.contains("PeriodicWorkRequestBuilder<AutoBackupWorker>"))
        assertTrue(settings.contains("ExistingPeriodicWorkPolicy.UPDATE"))
        assertTrue(settings.contains("ExistingPeriodicWorkPolicy.KEEP"))
        assertTrue(settings.contains("setRequiresBatteryNotLow(true)"))
        assertTrue(settings.contains("setRequiresStorageNotLow(true)"))
        assertTrue(worker.contains("BackupCreationReason.PERIODIC"))
        assertTrue(worker.contains("DATA_BACKUP_AUTO_REQUESTED"))
        assertTrue(worker.contains("AutoBackupSettingsStore(appContext).load()"))
        assertTrue(notification.contains("data_backup_failures"))
        assertTrue(notification.contains("DataProtectionActivity::class.java"))
        assertTrue(activity.contains("Z精算後バックアップは常時有効"))
        assertTrue(activity.contains("定期バックアップ・通知設定"))
        assertTrue(activity.contains("DATA_BACKUP_SETTINGS_UPDATED"))
        assertTrue(activity.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue(protection.contains("定期・保存世代・失敗通知を設定"))
        assertTrue(application.contains("AutoBackupPeriodicScheduler.apply(this)"))
        assertTrue(application.contains("is AutoBackupSettingsActivity"))
        assertTrue(manifest.contains("android:name=\".AutoBackupSettingsActivity\""))
    }
}
