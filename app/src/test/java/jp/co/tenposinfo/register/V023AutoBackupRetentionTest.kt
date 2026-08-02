package jp.co.tenposinfo.register

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V023AutoBackupRetentionTest {
    @Test
    fun zSettlementIsRequestedOnlyAfterSuccessfulCommit() {
        assertTrue(AutoBackupTriggerPolicy.shouldEnqueue(SettlementReportType.Z_SETTLEMENT, settlementCommitted = true))
        assertFalse(AutoBackupTriggerPolicy.shouldEnqueue(SettlementReportType.Z_SETTLEMENT, settlementCommitted = false))
        assertFalse(AutoBackupTriggerPolicy.shouldEnqueue(SettlementReportType.X_INSPECTION, settlementCommitted = true))
    }

    @Test
    fun sameSettlementUsesSameUniqueWorkName() {
        assertEquals(
            AutoBackupTriggerPolicy.uniqueZWorkName(1234L),
            AutoBackupTriggerPolicy.uniqueZWorkName(1234L),
        )
        assertFalse(AutoBackupTriggerPolicy.uniqueZWorkName(1234L) == AutoBackupTriggerPolicy.uniqueZWorkName(1235L))
    }

    @Test
    fun manualBackupsAreNeverAutomaticDeletionCandidates() {
        val entries = listOf(
            entry("manual.tgbak", 1L, BackupCreationReason.MANUAL),
            entry("manual-auto.tgbak", 2L, BackupCreationReason.MANUAL_AUTO),
            entry("imported.tgbak", 3L, null),
        )
        assertTrue(AutoBackupRetentionPolicy.selectDeletionCandidates(entries).isEmpty())
    }

    @Test
    fun zRetentionKeepsFourteenBusinessDaysAndDeletesOlderOnes() {
        val baseDate = LocalDate.of(2026, 8, 3)
        val entries = (0 until 16).map { offset ->
            val date = baseDate.minusDays(offset.toLong())
            entry(
                fileName = "z-$date.tgbak",
                createdAt = 10_000L - offset,
                reason = BackupCreationReason.Z_SETTLEMENT,
                businessDate = date.toString(),
            )
        }
        val deleted = AutoBackupRetentionPolicy.selectDeletionCandidates(entries)
        assertEquals(2, deleted.size)
        assertTrue("z-${baseDate.minusDays(14)}.tgbak" in deleted)
        assertTrue("z-${baseDate.minusDays(15)}.tgbak" in deleted)
        assertFalse("z-$baseDate.tgbak" in deleted)
    }

    @Test
    fun latestNormalBackupIsAlwaysRetained() {
        val entries = listOf(
            entry("old-z.tgbak", 1L, BackupCreationReason.Z_SETTLEMENT, "2025-01-01"),
            entry("latest-z.tgbak", 999L, BackupCreationReason.Z_SETTLEMENT, "2024-01-01"),
        )
        val deleted = AutoBackupRetentionPolicy.selectDeletionCandidates(entries, zBusinessDays = 1)
        assertFalse("latest-z.tgbak" in deleted)
    }

    @Test
    fun pendingRestoreAndUnverifiedOrCorruptBackupsAreProtected() {
        val entries = listOf(
            entry("pending.tgbak", 1L, BackupCreationReason.Z_SETTLEMENT, "2020-01-01", pending = true),
            entry("verifying.tgbak", 2L, BackupCreationReason.Z_SETTLEMENT, "2020-01-02", state = AutoBackupFileState.VERIFYING),
            entry("corrupt.tgbak", 3L, BackupCreationReason.Z_SETTLEMENT, "2020-01-03", state = AutoBackupFileState.CORRUPT),
            entry("new.tgbak", 4L, BackupCreationReason.Z_SETTLEMENT, "2026-08-03"),
        )
        val deleted = AutoBackupRetentionPolicy.selectDeletionCandidates(entries, zBusinessDays = 1)
        assertFalse("pending.tgbak" in deleted)
        assertFalse("verifying.tgbak" in deleted)
        assertFalse("corrupt.tgbak" in deleted)
    }

    @Test
    fun monthlyRetentionKeepsLatestBackupForTwelveMonths() {
        val entries = (0 until 13).flatMap { monthOffset ->
            val month = LocalDate.of(2026, 8, 1).minusMonths(monthOffset.toLong())
            val base = month.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            listOf(
                entry("periodic-$month-old.tgbak", base, BackupCreationReason.PERIODIC),
                entry("periodic-$month-new.tgbak", base + 1_000L, BackupCreationReason.PERIODIC),
            )
        }
        val deleted = AutoBackupRetentionPolicy.selectDeletionCandidates(entries)
        assertEquals(14, deleted.size)
        assertTrue("periodic-${LocalDate.of(2025, 8, 1)}-old.tgbak" in deleted)
        assertTrue("periodic-${LocalDate.of(2025, 8, 1)}-new.tgbak" in deleted)
        assertFalse("periodic-${LocalDate.of(2026, 8, 1)}-new.tgbak" in deleted)
    }

    @Test
    fun lowStorageBoundaryStopsBeforeWriting() {
        val databaseBytes = 64L * 1024L * 1024L
        val required = AutoBackupStoragePolicy.estimatedWorkingBytes(databaseBytes) +
            AutoBackupStoragePolicy.safetyMarginBytes(databaseBytes)
        assertTrue(AutoBackupStoragePolicy.hasCapacity(required, databaseBytes))
        assertFalse(AutoBackupStoragePolicy.hasCapacity(required - 1L, databaseBytes))
    }

    @Test
    fun retentionDoesNotDeleteExcludedFiles() {
        val entries = listOf(
            entry("manual.tgbak", 1L, BackupCreationReason.MANUAL),
            entry("invalid.tgbak", 2L, BackupCreationReason.Z_SETTLEMENT, "2020-01-01", valid = false),
            entry("old-z.tgbak", 3L, BackupCreationReason.Z_SETTLEMENT, "2020-01-02"),
            entry("new-z.tgbak", 4L, BackupCreationReason.Z_SETTLEMENT, "2026-08-03"),
        )
        val deleted = AutoBackupRetentionPolicy.selectDeletionCandidates(entries, zBusinessDays = 1)
        assertEquals(setOf("old-z.tgbak"), deleted)
    }

    private fun entry(
        fileName: String,
        createdAt: Long,
        reason: BackupCreationReason?,
        businessDate: String? = null,
        pending: Boolean = false,
        state: AutoBackupFileState = AutoBackupFileState.READY,
        valid: Boolean = true,
    ) = BackupRetentionEntry(
        fileName = fileName,
        createdAt = createdAt,
        valid = valid,
        reason = reason,
        businessDate = businessDate,
        state = state,
        pendingRestore = pending,
    )
}
