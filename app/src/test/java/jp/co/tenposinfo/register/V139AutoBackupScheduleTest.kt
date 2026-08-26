package jp.co.tenposinfo.register

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V139AutoBackupScheduleTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test
    fun designatedWeekdaySchedulesNextSelectedWeekdayAtConfiguredHour() {
        val now = ZonedDateTime.of(2026, 8, 24, 4, 0, 0, 0, zone) // Monday
        val expected = ZonedDateTime.of(2026, 8, 28, 3, 0, 0, 0, zone) // Friday

        val actual = AutoBackupSettingsPolicy.nextRunMillis(
            nowMillis = now.toInstant().toEpochMilli(),
            preferredHour = 3,
            zoneId = zone,
            cadence = PeriodicBackupCadence.WEEKLY,
            preferredWeekday = 5,
        )

        assertEquals(expected.toInstant().toEpochMilli(), actual)
    }

    @Test
    fun selectedWeekdayAfterConfiguredHourMovesToFollowingWeek() {
        val now = ZonedDateTime.of(2026, 8, 28, 4, 0, 0, 0, zone) // Friday after 03:00
        val expected = ZonedDateTime.of(2026, 9, 4, 3, 0, 0, 0, zone)

        val actual = AutoBackupSettingsPolicy.nextRunMillis(
            nowMillis = now.toInstant().toEpochMilli(),
            preferredHour = 3,
            zoneId = zone,
            cadence = PeriodicBackupCadence.WEEKLY,
            preferredWeekday = 5,
        )

        assertEquals(expected.toInstant().toEpochMilli(), actual)
    }

    @Test
    fun dailyScheduleStillUsesNextConfiguredHour() {
        val now = ZonedDateTime.of(2026, 8, 24, 2, 0, 0, 0, zone)
        val expected = ZonedDateTime.of(2026, 8, 24, 3, 0, 0, 0, zone)

        val actual = AutoBackupSettingsPolicy.nextRunMillis(
            nowMillis = now.toInstant().toEpochMilli(),
            preferredHour = 3,
            zoneId = zone,
            cadence = PeriodicBackupCadence.DAILY,
            preferredWeekday = 7,
        )

        assertEquals(expected.toInstant().toEpochMilli(), actual)
    }

    @Test
    fun formalDefaultsKeepPostSettlementBackupEnabled() {
        val settings = AutoBackupSettings()
        assertTrue(settings.periodicEnabled)
        assertTrue(settings.settlementAutoBackupEnabled)
        assertEquals(PeriodicBackupCadence.DAILY, settings.cadence)
        assertEquals(DEFAULT_PERIODIC_BACKUP_WEEKDAY, settings.preferredWeekday)
    }

    @Test
    fun productSourcesExposeDesignatedWeekdayAndSettlementToggle() {
        val mainRoot = File("src/main/java/jp/co/tenposinfo/register")
        val scheduler = File(mainRoot, "AutoBackup.kt").readText()
        val settings = File(mainRoot, "AutoBackupSettings.kt").readText()
        val ui = File(mainRoot, "AutoBackupSettingsActivity.kt").readText()

        assertTrue(settings.contains("WEEKLY(\"指定曜日\", 7)"))
        assertTrue(settings.contains("preferredWeekday"))
        assertTrue(settings.contains("settlementAutoBackupEnabled"))
        val zMethod = scheduler.substringAfter("fun enqueueZSettlement(").substringBefore("fun enqueueManualNow(")
        val gateAt = zMethod.indexOf("settlementAutoBackupEnabled")
        val enqueueAt = zMethod.indexOf("enqueue(", startIndex = gateAt.coerceAtLeast(0))
        assertTrue(gateAt >= 0)
        assertTrue(enqueueAt > gateAt)
        assertTrue(zMethod.contains("uniqueZWorkName(settlementId)"))
        assertTrue(ui.contains("指定曜日"))
        assertTrue(ui.contains("Z精算後バックアップ"))
        assertTrue(ui.contains("settlementAutoBackupEnabled"))
    }
}
