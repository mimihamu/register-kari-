package jp.co.tenposinfo.register

import android.content.Context
import android.content.SharedPreferences

internal enum class AppUpdateOperationalStateV090 {
    SUCCESS_CONFIRMED,
    STARTUP_PENDING,
    DB_HEALTH_BLOCKED,
    LEDGER_NOT_ESTABLISHED,
}

internal data class AppUpdateDiagnosticsSnapshotV090(
    val current: AppReleaseIdentityV088,
    val lastSuccessful: AppReleaseIdentityV088?,
    val lastSuccessfulAt: Long?,
    val pending: PendingAppStartupV088?,
    val incomplete: PendingAppStartupV088?,
    val databaseHealthFailure: AppUpdateDatabaseHealthFailureEvidenceV089?,
) {
    val state: AppUpdateOperationalStateV090
        get() = when {
            databaseHealthFailure?.target == current && pending?.target == current ->
                AppUpdateOperationalStateV090.DB_HEALTH_BLOCKED
            pending?.target == current -> AppUpdateOperationalStateV090.STARTUP_PENDING
            lastSuccessful == current -> AppUpdateOperationalStateV090.SUCCESS_CONFIRMED
            else -> AppUpdateOperationalStateV090.LEDGER_NOT_ESTABLISHED
        }
}

/**
 * v0.90: v0.88/v0.89で保存した更新起動状態を、業務DBを書き換えずに現場診断へ公開する。
 *
 * SharedPreferencesとBuildConfigを読むだけで、更新状態の変更・証跡削除・DB操作は行わない。
 */
internal object AppUpdateDiagnosticsV090 {
    private const val PREFS = "app_update_transition_v088"

    private const val KEY_LAST_SUCCESS_NAME = "last_success_name"
    private const val KEY_LAST_SUCCESS_CODE = "last_success_code"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"

    private const val KEY_PENDING_SOURCE_NAME = "pending_source_name"
    private const val KEY_PENDING_SOURCE_CODE = "pending_source_code"
    private const val KEY_PENDING_TARGET_NAME = "pending_target_name"
    private const val KEY_PENDING_TARGET_CODE = "pending_target_code"
    private const val KEY_PENDING_STARTED_AT = "pending_started_at"
    private const val KEY_PENDING_ATTEMPTS = "pending_attempts"

    private const val KEY_INCOMPLETE_SOURCE_NAME = "incomplete_source_name"
    private const val KEY_INCOMPLETE_SOURCE_CODE = "incomplete_source_code"
    private const val KEY_INCOMPLETE_TARGET_NAME = "incomplete_target_name"
    private const val KEY_INCOMPLETE_TARGET_CODE = "incomplete_target_code"
    private const val KEY_INCOMPLETE_STARTED_AT = "incomplete_started_at"
    private const val KEY_INCOMPLETE_ATTEMPTS = "incomplete_attempts"

    fun read(context: Context): AppUpdateDiagnosticsSnapshotV090 {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppUpdateDiagnosticsSnapshotV090(
            current = AppReleaseIdentityV088(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            lastSuccessful = readIdentity(prefs, KEY_LAST_SUCCESS_NAME, KEY_LAST_SUCCESS_CODE),
            lastSuccessfulAt = prefs.getLong(KEY_LAST_SUCCESS_AT, -1L).takeIf { it >= 0L },
            pending = readTransition(
                prefs,
                KEY_PENDING_SOURCE_NAME,
                KEY_PENDING_SOURCE_CODE,
                KEY_PENDING_TARGET_NAME,
                KEY_PENDING_TARGET_CODE,
                KEY_PENDING_STARTED_AT,
                KEY_PENDING_ATTEMPTS,
            ),
            incomplete = readTransition(
                prefs,
                KEY_INCOMPLETE_SOURCE_NAME,
                KEY_INCOMPLETE_SOURCE_CODE,
                KEY_INCOMPLETE_TARGET_NAME,
                KEY_INCOMPLETE_TARGET_CODE,
                KEY_INCOMPLETE_STARTED_AT,
                KEY_INCOMPLETE_ATTEMPTS,
            ),
            databaseHealthFailure = AppUpdateDatabaseHealthEvidenceV089.read(appContext),
        )
    }

    private fun readIdentity(
        prefs: SharedPreferences,
        nameKey: String,
        codeKey: String,
    ): AppReleaseIdentityV088? {
        val name = prefs.getString(nameKey, null)?.takeIf { it.isNotBlank() } ?: return null
        val code = prefs.getInt(codeKey, -1)
        return if (code >= 0) AppReleaseIdentityV088(name, code) else null
    }

    private fun readTransition(
        prefs: SharedPreferences,
        sourceNameKey: String,
        sourceCodeKey: String,
        targetNameKey: String,
        targetCodeKey: String,
        startedAtKey: String,
        attemptsKey: String,
    ): PendingAppStartupV088? {
        val target = readIdentity(prefs, targetNameKey, targetCodeKey) ?: return null
        val startedAt = prefs.getLong(startedAtKey, -1L)
        val attempts = prefs.getInt(attemptsKey, 0)
        if (startedAt < 0L || attempts <= 0) return null
        return PendingAppStartupV088(
            source = readIdentity(prefs, sourceNameKey, sourceCodeKey),
            target = target,
            startedAt = startedAt,
            attemptCount = attempts,
        )
    }
}