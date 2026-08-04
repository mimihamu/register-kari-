package jp.co.tenposinfo.register

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

private const val AUTO_BACKUP_SETTINGS_PREFS = "auto_backup_settings_v2"

const val DEFAULT_PERIODIC_BACKUP_HOUR = 3
const val MIN_Z_BACKUP_BUSINESS_DAYS = 1
const val MAX_Z_BACKUP_BUSINESS_DAYS = 90
const val MIN_MONTHLY_BACKUP_MONTHS = 1
const val MAX_MONTHLY_BACKUP_MONTHS = 36

enum class PeriodicBackupCadence(
    val displayName: String,
    val intervalDays: Int,
) {
    DAILY("毎日", 1),
    WEEKLY("7日ごと", 7),
}

data class AutoBackupSettings(
    val periodicEnabled: Boolean = true,
    val cadence: PeriodicBackupCadence = PeriodicBackupCadence.DAILY,
    val preferredHour: Int = DEFAULT_PERIODIC_BACKUP_HOUR,
    val zRetentionBusinessDays: Int = DEFAULT_Z_BACKUP_BUSINESS_DAYS,
    val monthlyRetentionMonths: Int = DEFAULT_MONTHLY_BACKUP_MONTHS,
    val failureNotificationsEnabled: Boolean = true,
)

data class AutoBackupScheduleApplyResult(
    val periodicEnabled: Boolean,
    val nextScheduledAt: Long?,
    val cadence: PeriodicBackupCadence,
)

object AutoBackupSettingsPolicy {
    fun sanitized(settings: AutoBackupSettings): AutoBackupSettings = settings.copy(
        preferredHour = settings.preferredHour.coerceIn(0, 23),
        zRetentionBusinessDays = settings.zRetentionBusinessDays.coerceIn(
            MIN_Z_BACKUP_BUSINESS_DAYS,
            MAX_Z_BACKUP_BUSINESS_DAYS,
        ),
        monthlyRetentionMonths = settings.monthlyRetentionMonths.coerceIn(
            MIN_MONTHLY_BACKUP_MONTHS,
            MAX_MONTHLY_BACKUP_MONTHS,
        ),
    )

    fun validated(settings: AutoBackupSettings): AutoBackupSettings {
        require(settings.preferredHour in 0..23) { "実行時刻は0～23時で指定してください" }
        require(settings.zRetentionBusinessDays in MIN_Z_BACKUP_BUSINESS_DAYS..MAX_Z_BACKUP_BUSINESS_DAYS) {
            "Z精算バックアップ保持営業日は${MIN_Z_BACKUP_BUSINESS_DAYS}～${MAX_Z_BACKUP_BUSINESS_DAYS}日で指定してください"
        }
        require(settings.monthlyRetentionMonths in MIN_MONTHLY_BACKUP_MONTHS..MAX_MONTHLY_BACKUP_MONTHS) {
            "定期バックアップ保持月数は${MIN_MONTHLY_BACKUP_MONTHS}～${MAX_MONTHLY_BACKUP_MONTHS}か月で指定してください"
        }
        return settings
    }

    fun nextRunMillis(
        nowMillis: Long,
        preferredHour: Int,
        zoneId: ZoneId,
    ): Long {
        require(preferredHour in 0..23)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        var candidate = now.toLocalDate().atTime(preferredHour, 0).atZone(zoneId)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    fun nextRunDescription(settings: AutoBackupSettings, nextScheduledAt: Long?): String = when {
        !settings.periodicEnabled -> "定期バックアップは無効"
        nextScheduledAt == null -> "定期バックアップの再登録が必要"
        else -> "${settings.cadence.displayName}・${settings.preferredHour}時台（端末状況により遅れて実行）"
    }
}

class AutoBackupSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AUTO_BACKUP_SETTINGS_PREFS,
        Context.MODE_PRIVATE,
    )

    fun load(): AutoBackupSettings = AutoBackupSettingsPolicy.sanitized(
        AutoBackupSettings(
            periodicEnabled = preferences.getBoolean("periodic_enabled", true),
            cadence = preferences.getString("periodic_cadence", PeriodicBackupCadence.DAILY.name)
                ?.let { runCatching { PeriodicBackupCadence.valueOf(it) }.getOrNull() }
                ?: PeriodicBackupCadence.DAILY,
            preferredHour = preferences.getInt("preferred_hour", DEFAULT_PERIODIC_BACKUP_HOUR),
            zRetentionBusinessDays = preferences.getInt("z_retention_business_days", DEFAULT_Z_BACKUP_BUSINESS_DAYS),
            monthlyRetentionMonths = preferences.getInt("monthly_retention_months", DEFAULT_MONTHLY_BACKUP_MONTHS),
            failureNotificationsEnabled = preferences.getBoolean("failure_notifications_enabled", true),
        ),
    )

    fun save(settings: AutoBackupSettings): AutoBackupSettings {
        val validated = AutoBackupSettingsPolicy.validated(settings)
        preferences.edit()
            .putBoolean("periodic_enabled", validated.periodicEnabled)
            .putString("periodic_cadence", validated.cadence.name)
            .putInt("preferred_hour", validated.preferredHour)
            .putInt("z_retention_business_days", validated.zRetentionBusinessDays)
            .putInt("monthly_retention_months", validated.monthlyRetentionMonths)
            .putBoolean("failure_notifications_enabled", validated.failureNotificationsEnabled)
            .apply()
        return validated
    }
}

object AutoBackupPeriodicScheduler {
    const val UNIQUE_WORK_NAME = "tsuguregi-periodic-data-backup"
    private const val WORK_TAG = "tsuguregi-periodic-data-backup"

    fun apply(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        replaceExisting: Boolean = false,
    ): AutoBackupScheduleApplyResult {
        val appContext = context.applicationContext
        val settings = AutoBackupSettingsStore(appContext).load()
        val workManager = WorkManager.getInstance(appContext)
        if (!settings.periodicEnabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            AutoBackupStatusStore(appContext).scheduled(null)
            return AutoBackupScheduleApplyResult(false, null, settings.cadence)
        }

        val nextRun = AutoBackupSettingsPolicy.nextRunMillis(
            nowMillis = nowMillis,
            preferredHour = settings.preferredHour,
            zoneId = zoneId,
        )
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            settings.cadence.intervalDays.toLong(),
            TimeUnit.DAYS,
        )
            .setInitialDelay((nextRun - nowMillis).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setInputData(AutoBackupScheduler.periodicInputData())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        val statusStore = AutoBackupStatusStore(appContext)
        val recordedNext = statusStore.load().nextScheduledAt
        val effectiveNext = if (replaceExisting || recordedNext == null) nextRun else recordedNext
        statusStore.scheduled(effectiveNext)
        return AutoBackupScheduleApplyResult(true, effectiveNext, settings.cadence)
    }

    fun estimatedNextAfterExecution(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val settings = AutoBackupSettingsStore(context.applicationContext).load()
        if (!settings.periodicEnabled) return null
        return Instant.ofEpochMilli(nowMillis)
            .atZone(zoneId)
            .plusDays(settings.cadence.intervalDays.toLong())
            .withHour(settings.preferredHour)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()
    }
}
